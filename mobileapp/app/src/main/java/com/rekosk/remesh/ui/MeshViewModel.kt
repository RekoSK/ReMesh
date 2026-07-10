package com.rekosk.remesh.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rekosk.remesh.ble.ChannelCrypto
import com.rekosk.remesh.ble.ConnectionState
import com.rekosk.remesh.ble.DiscoveredDevice
import com.rekosk.remesh.ble.MeshCoreProtocol
import com.rekosk.remesh.ble.MeshFrame
import com.rekosk.remesh.data.RepeaterName
import com.rekosk.remesh.data.ResolvedRepeat
import com.rekosk.remesh.data.SharedContact
import com.rekosk.remesh.data.channelUri
import com.rekosk.remesh.data.parseContactUri
import com.rekosk.remesh.data.resolveRepeaterName
import com.rekosk.remesh.data.config.ConfigCodec
import com.rekosk.remesh.data.config.ConfigFile
import com.rekosk.remesh.data.config.ConfigSection
import com.rekosk.remesh.data.ContactAppPrefs
import com.rekosk.remesh.data.ExperimentalPrefs
import com.rekosk.remesh.data.MeshContainer
import com.rekosk.remesh.data.MessagePrefs
import com.rekosk.remesh.data.NotificationPrefs
import com.rekosk.remesh.data.model.Channel
import com.rekosk.remesh.data.model.Contact
import com.rekosk.remesh.data.model.LoggedPacket
import com.rekosk.remesh.data.model.MeshMessage
import com.rekosk.remesh.data.model.NearbyNode
import com.rekosk.remesh.data.model.RecentAdvert
import com.rekosk.remesh.data.model.TraceHop
import com.rekosk.remesh.data.model.NodeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How many noise-floor readings the live chart keeps on screen. */
private const val NOISE_FLOOR_SAMPLES = 60

class MeshViewModel(application: Application) : AndroidViewModel(application) {

    // Process-scoped: the connection notification service reads the same instance.
    private val repository = MeshContainer.repository(application)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val isSyncing: StateFlow<Boolean> = repository.isSyncing
    val isRadioConnected: StateFlow<Boolean> = repository.isRadioConnected
    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val devices: StateFlow<List<DiscoveredDevice>> = repository.devices
    val lastError: StateFlow<String?> = repository.lastError
    val selfName: StateFlow<String?> = repository.selfName
    val selfInfo: StateFlow<MeshFrame.SelfInfo?> = repository.selfInfo
    val deviceInfo: StateFlow<MeshFrame.DeviceInfo?> = repository.deviceInfo
    val storage: StateFlow<MeshFrame.Battery?> = repository.storage

    val channels: StateFlow<List<Channel>> = repository.channels

    val contacts: StateFlow<List<Contact>> =
        combine(repository.contacts, _query) { contacts, query ->
            if (query.isBlank()) contacts
            else contacts.filter { it.name.contains(query, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalContactCount: StateFlow<Int> =
        repository.contacts
            .map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun startScan() = repository.startScan()
    fun stopScan() = repository.stopScan()
    fun clearError() = repository.clearError()

    fun connect(address: String) {
        viewModelScope.launch { runCatching { repository.connect(address) } }
    }

    fun disconnect() = repository.disconnect()

    /** Read-only refresh: pull contacts and queued messages. Transmits nothing. */
    fun sync() {
        viewModelScope.launch { repository.sync() }
    }

    /** Transmits our advert. [flood] crosses the mesh; false is direct range only. */
    fun sendAdvert(flood: Boolean) {
        viewModelScope.launch { repository.sendAdvert(flood) }
    }

    /** `meshcore://contact/add?...` for this node, or null before the handshake. */
    fun selfContactUri(): String? = repository.selfContactUri()

    /** The one live figure on the settings screen: flash used vs total. */
    fun refreshStorage() {
        viewModelScope.launch { repository.refreshStorage() }
    }

    private val _isTelemetryRefreshing = MutableStateFlow(false)
    val isTelemetryRefreshing: StateFlow<Boolean> = _isTelemetryRefreshing.asStateFlow()

    /** Pull-to-refresh on the telemetry screen. [onError] fires only on failure. */
    fun refreshTelemetry(onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            _isTelemetryRefreshing.value = true
            repository.refreshTelemetry()?.let(onError)
            _isTelemetryRefreshing.value = false
        }
    }

    private val _isSavingSettings = MutableStateFlow(false)
    val isSavingSettings: StateFlow<Boolean> = _isSavingSettings.asStateFlow()

    // ---------------- the eight settings sub-screens ----------------

    /** App-side preferences; nothing here reaches the node. */
    private val prefs = MeshContainer.preferences(application)

    // Null until DataStore has been read once, so a screen never seeds its draft
    // from the compiled-in defaults and then overwrites the stored values.
    val messagePrefs: StateFlow<MessagePrefs?> =
        prefs.messages.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val notificationPrefs: StateFlow<NotificationPrefs?> =
        prefs.notifications.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val contactAppPrefs: StateFlow<ContactAppPrefs?> =
        prefs.contacts.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val experimentalPrefs: StateFlow<ExperimentalPrefs?> =
        prefs.experimental.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val autoAdd: StateFlow<MeshFrame.AutoAddConfig?> = repository.autoAdd
    val customVars: StateFlow<MeshFrame.CustomVars?> = repository.customVars

    // Node writes, as suspend functions so uploadSettings can sequence them with
    // preference writes. Each returns null on success or an error message.

    suspend fun writeBlePin(pin: Int): String? = repository.setBlePin(pin)

    suspend fun writeAutoAddConfig(config: Int, maxHops: Int): String? =
        repository.setAutoAddConfig(config, maxHops)

    suspend fun writeTelemetry(base: Int, location: Int, environment: Int): String? =
        repository.setOtherParams(
            telemetryBase = base,
            telemetryLocation = location,
            telemetryEnvironment = environment,
        )

    /** [totalAcks] is what the user picks (1 or 2); the node stores one less. */
    suspend fun writeDirectMessageAcks(totalAcks: Int): String? =
        repository.setOtherParams(multiAcks = MeshCoreProtocol.MultiAcks.extraOf(totalAcks))

    suspend fun writeGpsEnabled(enabled: Boolean): String? = repository.setGpsEnabled(enabled)

    suspend fun writePathHashMode(mode: Int): String? = repository.setPathHashMode(mode)

    fun exportPrivateKey(onResult: (Result<ByteArray>) -> Unit) {
        viewModelScope.launch { onResult(repository.exportPrivateKey()) }
    }

    fun importPrivateKey(keyPair: ByteArray, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _isSavingSettings.value = true
            val error = repository.importPrivateKey(keyPair)
            _isSavingSettings.value = false
            onResult(error)
        }
    }

    /**
     * Uploads a screen's queued edits: app preferences first (they cannot fail),
     * then the node write if there is one. [onResult] gets null on success.
     */
    fun uploadSettings(
        savePrefs: (suspend () -> Unit)? = null,
        nodeWrite: (suspend () -> String?)? = null,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            _isSavingSettings.value = true
            savePrefs?.invoke()
            val error = nodeWrite?.invoke()
            _isSavingSettings.value = false
            onResult(error)
        }
    }

    // ---------------- configuration import / export ----------------

    val rawChannels: StateFlow<List<MeshFrame.ChannelInfo>> = repository.rawChannels
    val rawContacts: StateFlow<List<MeshFrame.Contact>> = repository.rawContacts

    private val _importProgress = MutableStateFlow<String?>(null)
    val importProgress: StateFlow<String?> = _importProgress.asStateFlow()

    /** Serialises the chosen sections and hands back the JSON text to write out. */
    fun buildConfigJson(sections: Set<ConfigSection>, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            _isSavingSettings.value = true
            val result = repository.buildConfig(sections).map(ConfigCodec::encode)
            _isSavingSettings.value = false
            onResult(result)
        }
    }

    /** [onResult] gets the list of per-section failures; empty means everything landed. */
    fun applyConfig(
        config: ConfigFile,
        sections: Set<ConfigSection>,
        onResult: (List<String>) -> Unit,
    ) {
        viewModelScope.launch {
            _isSavingSettings.value = true
            val errors = repository.applyConfig(config, sections) { _importProgress.value = it }
            _importProgress.value = null
            _isSavingSettings.value = false
            onResult(errors)
        }
    }

    suspend fun persist(prefs: MessagePrefs) = this.prefs.save(prefs)
    suspend fun persist(prefs: NotificationPrefs) = this.prefs.save(prefs)
    suspend fun persist(prefs: ContactAppPrefs) = this.prefs.save(prefs)
    suspend fun persist(prefs: ExperimentalPrefs) = this.prefs.save(prefs)

    /** Uploads changed settings. [onResult] gets null on success, else an error to show. */
    fun saveSettings(
        original: NodeSettings,
        edited: NodeSettings,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            _isSavingSettings.value = true
            val error = repository.applySettings(original, edited)
            _isSavingSettings.value = false
            onResult(error)
        }
    }

    fun send(conversationId: String, text: String) {
        viewModelScope.launch { repository.send(conversationId, text) }
    }

    fun deleteMessage(conversationId: String, messageId: String) =
        repository.deleteMessage(conversationId, messageId)

    /** One row per repeater overheard forwarding [messageId], names resolved. */
    fun heardRepeats(conversationId: String, messageId: String): List<ResolvedRepeat> {
        val message = repository.messages.value[conversationId]
            ?.firstOrNull { it.id == messageId }
            ?: return emptyList()
        val contacts = repository.rawContacts.value
        return message.heardRepeats.map { heard ->
            ResolvedRepeat(
                hashHex = heard.hashHex,
                name = resolveRepeaterName(heard.hashHex, contacts),
                snr = heard.snr,
                rssi = heard.rssi,
            )
        }
    }

    // ---------------- adding channels ----------------

    private val _isAddingChannel = MutableStateFlow(false)
    val isAddingChannel: StateFlow<Boolean> = _isAddingChannel.asStateFlow()

    /**
     * Writes a channel into the node's first free slot. [onResult] gets null on
     * success, else the reason -- a bad key, a duplicate, or no slots left.
     */
    fun addChannel(name: String, secret: ByteArray, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _isAddingChannel.value = true
            val error = repository.addChannel(name.trim(), secret)
            _isAddingChannel.value = false
            onResult(error)
        }
    }

    fun joinPublicChannel(name: String, onResult: (String?) -> Unit) =
        addChannel(name, ChannelCrypto.PUBLIC_SECRET, onResult)

    fun createPrivateChannel(name: String, onResult: (String?) -> Unit) =
        addChannel(name, ChannelCrypto.randomSecret(), onResult)

    /** [secretHex] is the 32-character key the other members already share. */
    fun joinPrivateChannel(name: String, secretHex: String, onResult: (String?) -> Unit) {
        val secret = runCatching { with(ChannelCrypto) { secretHex.decodeHex() } }.getOrNull()
        if (secret == null || secret.size != ChannelCrypto.SECRET_SIZE) {
            onResult("A secret key is 32 hexadecimal characters")
            return
        }
        addChannel(name, secret, onResult)
    }

    /** The key is derived from the name, so everyone typing "#slovakia" lands together. */
    fun joinHashtagChannel(name: String, onResult: (String?) -> Unit) {
        val trimmed = name.trim()
        ChannelCrypto.validateHashtag(trimmed)?.let { return onResult(it) }
        addChannel(trimmed, ChannelCrypto.hashtagSecret(trimmed), onResult)
    }

    /** Frees the node's channel slot. [onResult] gets null on success. */
    fun deleteChannel(index: Int, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _isAddingChannel.value = true
            val error = repository.deleteChannel(index)
            _isAddingChannel.value = false
            onResult(error)
        }
    }

    /** `meshcore://channel/add?...` for [index], or null if we hold no such channel. */
    fun channelShareUri(index: Int): String? {
        val channel = repository.channels.value.firstOrNull { it.index == index } ?: return null
        val secret = repository.channelSecret(index) ?: return null
        return channelUri(channel.name, secret)
    }

    /** The channel's key as the 32 lowercase hex characters others must type in. */
    fun channelSecretHex(index: Int): String? =
        repository.channelSecret(index)?.joinToString("") { "%02x".format(it) }

    fun channelByIndex(index: Int): Channel? =
        repository.channels.value.firstOrNull { it.index == index }

    // ---------------- add contact ----------------

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    /** [publicKeyHex] is the 64 hex characters of the node's identity key. */
    fun addContact(name: String, type: Int, publicKeyHex: String, onResult: (String?) -> Unit) {
        val key = runCatching { with(ChannelCrypto) { publicKeyHex.decodeHex() } }.getOrNull()
        if (key == null || key.size != MeshCoreProtocol.PUB_KEY_SIZE) {
            onResult("A public key is 64 hexadecimal characters")
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            val error = repository.addContact(name, type, key)
            _isBusy.value = false
            onResult(error)
        }
    }

    /** Reads a `meshcore://contact/add` link out of the clipboard, if there is one. */
    fun parseContactLink(text: String): SharedContact? = parseContactUri(text)

    // ---------------- tools ----------------

    val packetLog: StateFlow<List<LoggedPacket>> = repository.packetLog

    fun clearPacketLog() = repository.clearPacketLog()

    private val _recentAdverts = MutableStateFlow<List<RecentAdvert>>(emptyList())
    val recentAdverts: StateFlow<List<RecentAdvert>> = _recentAdverts.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    /**
     * Walks the node's advert table. Read-only: nothing is transmitted.
     *
     * Results accumulate across refreshes. The node's table holds only the last 16
     * adverts and is lost when it reboots, so a refresh that no longer sees a node
     * does not mean its advert never arrived -- only that it has aged out.
     */
    fun refreshRecentAdverts() {
        viewModelScope.launch {
            _isDiscovering.value = true
            val fresh = repository.recentAdverts()
            _recentAdverts.update { existing ->
                val byKey = existing.associateBy { it.publicKey.toList() }.toMutableMap()
                fresh.forEach { byKey[it.publicKey.toList()] = it }
                byKey.values.sortedByDescending { it.receivedEpochMs }
            }
            _isDiscovering.value = false
        }
    }

    private val _nearbyNodes = MutableStateFlow<List<NearbyNode>>(emptyList())
    val nearbyNodes: StateFlow<List<NearbyNode>> = _nearbyNodes.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Transmits a zero-hop scan and collects whoever answers. [onError] fires on failure. */
    fun scanNearbyNodes(onError: (String) -> Unit = {}) {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            _nearbyNodes.value = emptyList()
            val error = repository.discoverNearbyNodes { node ->
                _nearbyNodes.update { existing ->
                    (existing + node).sortedByDescending { it.inboundSnr }
                }
            }
            _isScanning.value = false
            error?.let(onError)
        }
    }

    private val _noiseFloor = MutableStateFlow<List<Int>>(emptyList())

    /** The last few noise-floor readings, oldest first, for the live chart. */
    val noiseFloor: StateFlow<List<Int>> = _noiseFloor.asStateFlow()

    private val _radioStats = MutableStateFlow<MeshFrame.RadioStats?>(null)
    val radioStats: StateFlow<MeshFrame.RadioStats?> = _radioStats.asStateFlow()

    fun sampleNoiseFloor() {
        viewModelScope.launch {
            val stats = repository.radioStats() ?: return@launch
            _radioStats.value = stats
            _noiseFloor.update { (it + stats.noiseFloorDbm).takeLast(NOISE_FLOOR_SAMPLES) }
        }
    }

    fun clearNoiseFloor() {
        _noiseFloor.value = emptyList()
    }

    private val _trace = MutableStateFlow<List<TraceHop>?>(null)

    /** The last completed trace, or null before one has run. */
    val trace: StateFlow<List<TraceHop>?> = _trace.asStateFlow()

    private val _isTracing = MutableStateFlow(false)
    val isTracing: StateFlow<Boolean> = _isTracing.asStateFlow()

    /**
     * Traces the route through [hashesHex], a concatenation of repeater hashes.
     * [onError] fires when no repeater answers, which is the usual failure.
     */
    fun traceRoute(hashesHex: String, hashSizeBytes: Int, onError: (String) -> Unit) {
        val hashes = runCatching { with(ChannelCrypto) { hashesHex.decodeHex() } }.getOrNull()
        if (hashes == null || hashes.isEmpty() || hashes.size % hashSizeBytes != 0) {
            onError("Enter whole repeater hashes, ${hashSizeBytes * 2} hex characters each")
            return
        }
        viewModelScope.launch {
            _isTracing.value = true
            _trace.value = null
            repository.traceRoute(hashes, hashSizeBytes)
                .onSuccess { _trace.value = toTraceHops(it) }
                .onFailure { onError(it.message ?: "The trace did not come back") }
            _isTracing.value = false
        }
    }

    private fun toTraceHops(data: MeshFrame.TraceData): List<TraceHop> {
        val contacts = repository.rawContacts.value
        return (0 until data.hops).map { index ->
            val hashHex = data.hashAt(index).joinToString("") { "%02X".format(it) }
            TraceHop(
                hashHex = hashHex,
                name = (resolveRepeaterName(hashHex, contacts) as? RepeaterName.Known)?.name,
                snr = data.hopSnrs[index],
            )
        }
    }

    /** The mesh's path hash size, which a trace's input must match. */
    fun pathHashSizeBytes(): Int =
        MeshCoreProtocol.PathHashMode.hashSizeBytes(repository.deviceInfo.value?.pathHashMode ?: 0)

    private val messageFlows = mutableMapOf<String, StateFlow<List<MeshMessage>>>()

    /** Cached per conversation so recomposition doesn't spawn a new flow each time. */
    fun messagesFor(conversationId: String): StateFlow<List<MeshMessage>> =
        messageFlows.getOrPut(conversationId) {
            repository.messages
                .map { it[conversationId].orEmpty() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    /** Title + subtitle for the chat top bar, for either a channel or a direct contact. */
    fun conversationTitle(conversationId: String): Pair<String, String> {
        repository.channelById(conversationId)?.let { channel ->
            val kind = channel.kind.name.lowercase().replaceFirstChar { it.uppercase() }
            return channel.name to "$kind • Unread: ${channel.unreadCount}"
        }
        repository.contactById(conversationId)?.let { contact ->
            return contact.name to "Direct message"
        }
        return conversationId to ""
    }

    fun isChannel(conversationId: String): Boolean = repository.channelById(conversationId) != null

    // No onCleared() disconnect: the BLE link belongs to MeshContainer and is torn
    // down by MeshConnectionService, which outlives this ViewModel.
}
