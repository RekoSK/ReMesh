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
import com.rekosk.remesh.data.ResolvedRoute
import com.rekosk.remesh.data.model.RepeaterContactRef
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
import com.rekosk.remesh.data.SavedNodeSummary
import com.rekosk.remesh.data.model.Channel
import com.rekosk.remesh.data.model.Contact
import com.rekosk.remesh.data.model.ContactExtras
import com.rekosk.remesh.data.coverage.CoverageDefaults
import com.rekosk.remesh.data.coverage.CoverageEngine
import com.rekosk.remesh.data.coverage.CoverageResult
import com.rekosk.remesh.data.coverage.RadioParams
import com.rekosk.remesh.data.coverage.TerrainDem
import com.rekosk.remesh.data.model.ConversationSummary
import com.rekosk.remesh.data.model.CoveragePoint
import com.rekosk.remesh.data.model.DiscoveredNodeInfo
import com.rekosk.remesh.data.model.LoggedPacket
import com.rekosk.remesh.data.model.MeshMessage
import com.rekosk.remesh.data.model.NearbyNode
import com.rekosk.remesh.data.model.NodePosition
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.data.model.RecentAdvert
import com.rekosk.remesh.data.model.TraceHop
import com.rekosk.remesh.data.model.NodeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

/** How many noise-floor readings the live chart keeps on screen. */
private const val NOISE_FLOOR_SAMPLES = 60

/** Marker id for our own node on the map; the "c:"-prefixed contact ids never collide. */
const val SELF_NODE_ID = "self"

/** Outcome of a zero-hop ping, shown in the ping dialog. */
data class PingResult(val success: Boolean, val message: String)

/** A completed path trace: the hops plus how long the round trip took. */
data class TraceResult(
    val hops: List<TraceHop>,
    val elapsedMs: Long,
    /** How well our own node heard the returning trace, in dB. */
    val finalSnr: Float,
    val selfName: String,
)

class MeshViewModel(application: Application) : AndroidViewModel(application) {

    // Process-scoped: the connection notification service reads the same instance.
    private val repository = MeshContainer.repository(application)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val isSyncing: StateFlow<Boolean> = repository.isSyncing
    val isRadioConnected: StateFlow<Boolean> = repository.isRadioConnected
    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val devices: StateFlow<List<DiscoveredDevice>> = repository.devices
    val connectionRssi: StateFlow<Int?> = repository.connectionRssi
    val lastError: StateFlow<String?> = repository.lastError
    val selfName: StateFlow<String?> = repository.selfName
    val selfInfo: StateFlow<MeshFrame.SelfInfo?> = repository.selfInfo
    val deviceInfo: StateFlow<MeshFrame.DeviceInfo?> = repository.deviceInfo
    val storage: StateFlow<MeshFrame.Battery?> = repository.storage

    val channels: StateFlow<List<Channel>> = repository.channels

    /** Channels plus active DM threads, with unread counts, for the messaging list. */
    val conversations: StateFlow<List<ConversationSummary>> = repository.conversations

    /** Clears a conversation's unread badge. Called when its chat screen opens. */
    fun markRead(conversationId: String) = repository.markConversationRead(conversationId)

    val contacts: StateFlow<List<Contact>> =
        combine(repository.contacts, _query) { contacts, query ->
            if (query.isBlank()) contacts
            else contacts.filter { it.name.contains(query, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Unfiltered contacts, for pickers that must ignore the search box. */
    val allContacts: StateFlow<List<Contact>> = repository.contacts

    /**
     * Every node we can place on the map: our own node (when it has a position) plus
     * every contact whose last advert carried coordinates. Both are read from the
     * *persisted* domain state, so the map still works when the companion is offline.
     * A 0/0 fix means "unknown", so those are dropped rather than pinned off Africa.
     */
    val nodePositions: StateFlow<List<NodePosition>> =
        combine(repository.contacts, repository.selfPosition) { contacts, self ->
            buildList {
                self?.takeIf { it.latE6 != 0 || it.lonE6 != 0 }?.let {
                    add(
                        NodePosition(
                            id = SELF_NODE_ID,
                            name = it.name,
                            type = NodeType.CHAT,
                            latitude = it.latE6 / 1e6,
                            longitude = it.lonE6 / 1e6,
                            isSelf = true,
                        )
                    )
                }
                contacts.forEach { c ->
                    if (c.latE6 != 0 || c.lonE6 != 0) {
                        add(
                            NodePosition(
                                id = c.id,
                                name = c.name,
                                type = c.type,
                                latitude = c.latE6 / 1e6,
                                longitude = c.lonE6 / 1e6,
                                lastSeenEpochMs = c.lastSeenEpochMs,
                            )
                        )
                    }
                }
            }
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
    fun readConnectionRssi() = repository.readConnectionRssi()
    fun clearError() = repository.clearError()

    fun connect(address: String) {
        viewModelScope.launch { runCatching { repository.connect(address) } }
    }

    fun disconnect() = repository.disconnect()

    /** Nodes with saved data, for the connect screen's offline section. */
    val savedNodes: StateFlow<List<SavedNodeSummary>> = repository.savedNodes

    /** Opens a saved node's stored data for offline reading. Ignored while connected. */
    fun openSavedNode(key: String) = repository.openSavedNode(key)

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

    /** One entry per overheard copy of an incoming [messageId], hops resolved to names. */
    fun messageRoutes(conversationId: String, messageId: String): List<ResolvedRoute> {
        val message = repository.messages.value[conversationId]
            ?.firstOrNull { it.id == messageId }
            ?: return emptyList()
        val contacts = repository.rawContacts.value
        return message.routes.map { route ->
            ResolvedRoute(
                hops = route.pathHashesHex.map { hash ->
                    ResolvedRepeat(hash, resolveRepeaterName(hash, contacts), route.snr, route.rssi)
                },
                snr = route.snr,
            )
        }
    }

    /** Known contacts whose key starts with a route hop's [hashHex]; the tap targets. */
    fun repeaterContactsForHash(hashHex: String): List<RepeaterContactRef> =
        repository.contactsMatchingHash(hashHex)

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

    // ---------------- contact detail ----------------

    /** Live view of a single contact, for its detail screen. */
    fun contactFlow(id: String): StateFlow<Contact?> =
        repository.contacts
            .map { list -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.contactById(id))

    /** The fields the list model drops: full key, coordinates, distance, current path. */
    fun contactExtras(id: String): ContactExtras? {
        val raw = repository.rawContactById(id) ?: return null
        val self = repository.selfInfo.value
        val lat = if (raw.latE6 != 0 || raw.lonE6 != 0) raw.latE6 / 1e6 else null
        val lon = if (raw.latE6 != 0 || raw.lonE6 != 0) raw.lonE6 / 1e6 else null
        val distance = if (lat != null && lon != null && self != null &&
            (self.latE6 != 0 || self.lonE6 != 0)
        ) {
            haversineKm(self.latE6 / 1e6, self.lonE6 / 1e6, lat, lon)
        } else {
            null
        }
        // out_path_len packs a hash-size mode and a hop count (see toContact); the real
        // route is hops * hashSize bytes, not the raw byte. 0xFF means no route (flood).
        val outPathHex = when {
            (raw.outPathLen and 0xFF) == MeshCoreProtocol.PathInfo.DIRECT -> null
            else -> {
                val byteLen = MeshCoreProtocol.PathInfo.hops(raw.outPathLen and 0xFF) *
                    MeshCoreProtocol.PathInfo.hashSizeBytes(raw.outPathLen and 0xFF)
                raw.outPath.take(byteLen).joinToString("") { "%02x".format(it) }
            }
        }
        return ContactExtras(
            publicKeyHex = raw.publicKey.joinToString("") { "%02x".format(it) },
            latitude = lat,
            longitude = lon,
            distanceKm = distance,
            lastAdvertEpochMs = raw.lastAdvertEpochSec.takeIf { it > 0 }?.times(1000),
            outPathHex = outPathHex,
            pathHashSizeBytes = pathHashSizeBytes(),
        )
    }

    fun setFavorite(contactId: String, favorite: Boolean) =
        repository.setFavorite(contactId, favorite)

    fun resetContactRoute(contactId: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            val error = repository.resetPath(contactId)
            _isBusy.value = false
            onResult(error)
        }
    }

    /** [hashesHex] is repeater hashes, comma-separated or joined; empty = zero-hop direct. */
    fun setContactRoute(contactId: String, hashesHex: String, onResult: (String?) -> Unit) {
        val clean = sanitizeHex(hashesHex)
        val path = if (clean.isEmpty()) {
            ByteArray(0)
        } else {
            runCatching { with(ChannelCrypto) { clean.decodeHex() } }.getOrNull()
                ?: return onResult("Enter whole repeater hashes in hex")
        }
        viewModelScope.launch {
            _isBusy.value = true
            val error = repository.setOutPath(contactId, path)
            _isBusy.value = false
            onResult(error)
        }
    }

    /** The path the last advert from this contact took to reach us. [onResult] gets
     *  "Direct", the hash bytes, or null when we hold no record. */
    fun inboundPath(contactId: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val advert = repository.advertPathFor(contactId)
            onResult(
                advert?.let {
                    if (it.hops == 0) "Direct"
                    else it.path.joinToString(" ") { b -> "%02X".format(b) }
                },
            )
        }
    }

    fun renameContact(contactId: String, newName: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            val error = repository.renameContact(contactId, newName)
            _isBusy.value = false
            onResult(error)
        }
    }

    fun removeContact(contactId: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            val error = repository.removeContact(contactId)
            _isBusy.value = false
            onResult(error)
        }
    }

    /** Deletes several contacts in one pass; reports the first error, if any. */
    fun removeContacts(ids: Set<String>, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            var firstError: String? = null
            for (id in ids) {
                val error = repository.removeContact(id)
                if (error != null && firstError == null) firstError = error
            }
            _isBusy.value = false
            onResult(firstError)
        }
    }

    /** `meshcore://contact/add?...` for a contact we hold, or null if unknown. */
    fun contactShareUri(contactId: String): String? {
        val raw = repository.rawContactById(contactId) ?: return null
        return com.rekosk.remesh.data.MeshRepository.contactUri(raw.name, raw.publicKey, raw.type)
    }

    // ---------------- node location ----------------

    /** Location updates queued while offline, keyed by "self" or a contact id. */
    val pendingLocations: StateFlow<Map<String, Pair<Int, Int>>> = repository.pendingLocations

    /** Our node's current position in 1e-6 degrees, or null when unknown (for the picker pin). */
    fun selfLocation(): Pair<Int, Int>? {
        val self = repository.selfInfo.value
        if (self != null && (self.latE6 != 0 || self.lonE6 != 0)) return self.latE6 to self.lonE6
        return repository.selfPosition.value?.let { it.latE6 to it.lonE6 }
    }

    /**
     * A contact's stored position in 1e-6 degrees, or null when unknown. Reads the domain
     * contact (persisted, available offline — the same source the Map screen uses) rather
     * than the connection-only raw record.
     */
    fun contactLocation(contactId: String): Pair<Int, Int>? =
        repository.contacts.value.firstOrNull { it.id == contactId }
            ?.takeIf { it.latE6 != 0 || it.lonE6 != 0 }
            ?.let { it.latE6 to it.lonE6 }

    /** Great-circle distance in km from our node to the given point, or null if either is unknown. */
    fun distanceKmTo(latE6: Int, lonE6: Int): Double? {
        if (latE6 == 0 && lonE6 == 0) return null
        val self = selfLocation() ?: return null
        return haversineKm(self.first / 1e6, self.second / 1e6, latE6 / 1e6, lonE6 / 1e6)
    }

    // ---------------- signal coverage ----------------
    // Deliberately stateless: points and terrain live in the coverage screen's composition
    // (via the screen-scoped [TerrainDem]) and are forgotten when it closes. Nothing is
    // persisted to disk or retained in the view model.

    private val coverageEngine = CoverageEngine()

    /**
     * Baseline radio parameters: the connected node's radio config when available (frequency,
     * TX power, SF/bandwidth-derived sensitivity), else MeshCore-typical defaults. Per-point
     * overrides are applied on top in [computeCoverage].
     */
    fun defaultRadioParams(): RadioParams {
        val self = repository.selfInfo.value
        return RadioParams(
            freqMHz = self?.radioFreqKhz?.let { it / 1000.0 } ?: CoverageDefaults.DEFAULT_FREQ_MHZ,
            txPowerDbm = self?.txPower?.toDouble() ?: CoverageDefaults.DEFAULT_TX_DBM,
            sensitivityDbm = self?.let { CoverageDefaults.sensitivityDbm(it.spreadingFactor, it.radioBandwidthHz) }
                ?: CoverageDefaults.DEFAULT_SENS_DBM,
        )
    }

    /**
     * Computes the terrain signal-coverage heatmap for a coverage point, tinted [baseColorArgb],
     * honouring the point's optional radio overrides. The compute range is derived from the
     * resulting link budget. Heavy (terrain-tile fetch + viewshed math on background
     * dispatchers); returns null on failure.
     */
    suspend fun computeCoverage(point: CoveragePoint, baseColorArgb: Int, dem: TerrainDem): CoverageResult? {
        val defaults = defaultRadioParams()
        val params = defaults.copy(
            freqMHz = point.freqMhz ?: defaults.freqMHz,
            txPowerDbm = point.txPowerDbm ?: defaults.txPowerDbm,
            sensitivityDbm = point.rxSensitivityDbm ?: defaults.sensitivityDbm,
            txAntennaM = point.antennaM ?: CoverageDefaults.TX_ANTENNA_M,
        )
        return computeCoverageAt(point.latE6, point.lonE6, params, baseColorArgb, dem)
    }

    /** Theoretical coverage for a repeater: node radio defaults + a repeater-mast antenna. */
    suspend fun computeRepeaterCoverage(
        latE6: Int,
        lonE6: Int,
        baseColorArgb: Int,
        dem: TerrainDem,
    ): CoverageResult? = computeCoverageAt(
        latE6, lonE6,
        defaultRadioParams().copy(txAntennaM = CoverageDefaults.REPEATER_ANTENNA_M),
        baseColorArgb,
        dem,
    )

    private suspend fun computeCoverageAt(
        latE6: Int,
        lonE6: Int,
        params: RadioParams,
        baseColorArgb: Int,
        dem: TerrainDem,
    ): CoverageResult? = runCatching {
        val resolved = params.withDerivedRange()
        val lat = latE6 / 1e6
        val lon = lonE6 / 1e6
        val sampler = withContext(Dispatchers.IO) { dem.prepare(lat, lon, resolved.maxRangeM) }
        coverageEngine.compute(lat, lon, resolved, baseColorArgb, sampler)
    }.getOrNull()

    fun setSelfLocation(latE6: Int, lonE6: Int, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            val error = repository.setSelfLocation(latE6, lonE6)
            _isBusy.value = false
            onResult(error)
        }
    }

    fun setContactLocation(contactId: String, latE6: Int, lonE6: Int, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            val error = repository.setContactLocation(contactId, latE6, lonE6)
            _isBusy.value = false
            onResult(error)
        }
    }

    /** Fires a zero-hop ping; [onResult] gets a human-readable outcome line. */
    fun pingZeroHop(contactId: String, onResult: (PingResult) -> Unit) {
        viewModelScope.launch {
            repository.pingZeroHop(contactId)
                .onSuccess { onResult(PingResult(true, formatPing(it))) }
                .onFailure { onResult(PingResult(false, it.message ?: "No response")) }
        }
    }

    private fun formatPing(data: MeshFrame.TraceData): String {
        val theyHeardUs = data.hopSnrs.firstOrNull()
        val builder = StringBuilder("Reachable directly")
        theyHeardUs?.let { builder.append("\nThey heard us at ${"%.2f".format(it)} dB") }
        builder.append("\nWe heard the reply at ${"%.2f".format(data.finalSnr)} dB")
        return builder.toString()
    }

    /** Keeps only hex digits, so a user can type "aa,bb, cc" and mean "aabbcc". */
    private fun sanitizeHex(input: String): String =
        input.filter { it in "0123456789abcdefABCDEF" }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).pow(2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).pow(2)
        return r * 2 * Math.asin(Math.sqrt(a))
    }

    // ---------------- tools ----------------

    val packetLog: StateFlow<List<LoggedPacket>> = repository.packetLog

    fun clearPacketLog() = repository.clearPacketLog()

    /** Accumulated across refreshes and persisted per node; see the repository. */
    val recentAdverts: StateFlow<List<RecentAdvert>> = repository.recentAdverts

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    /** Walks the node's advert table. Read-only: nothing is transmitted. */
    fun refreshRecentAdverts() {
        viewModelScope.launch {
            _isDiscovering.value = true
            repository.refreshRecentAdverts()
            _isDiscovering.value = false
        }
    }

    private val _nearbyNodes = MutableStateFlow<List<NearbyNode>>(emptyList())
    val nearbyNodes: StateFlow<List<NearbyNode>> = _nearbyNodes.asStateFlow()

    /**
     * Live facts about a discovered (not-yet-contact) node, so its contact menu can show
     * hop count / signal before it is added. Merges a recent advert and a nearby-scan reply
     * for the same key; null when neither is currently known.
     */
    fun discoveredNode(publicKeyHex: String): DiscoveredNodeInfo? {
        val key = runCatching { with(ChannelCrypto) { publicKeyHex.decodeHex() } }.getOrNull() ?: return null
        val advert = recentAdverts.value.firstOrNull { it.publicKey.contentEquals(key) }
        val nearby = nearbyNodes.value.firstOrNull { it.publicKey.contentEquals(key) }
        if (advert == null && nearby == null) return null
        return DiscoveredNodeInfo(
            name = advert?.name ?: nearby?.name,
            type = advert?.type ?: nearby?.type ?: NodeType.CHAT,
            hops = advert?.hops,
            isDirect = advert?.isDirect,
            inboundSnr = nearby?.inboundSnr,
            outboundSnr = nearby?.outboundSnr,
            rssi = nearby?.rssi,
            lastHeardEpochMs = advert?.receivedEpochMs,
        )
    }

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

    private val _trace = MutableStateFlow<TraceResult?>(null)

    /** The last completed trace, or null before one has run. */
    val trace: StateFlow<TraceResult?> = _trace.asStateFlow()

    private val _isTracing = MutableStateFlow(false)
    val isTracing: StateFlow<Boolean> = _isTracing.asStateFlow()

    /**
     * Repeaters the user has picked on the map, in tap order, as contact ids. Shared
     * between the map trace-picker and the Path trace screen so the two can hand the
     * selection back and forth; the path hex is derived from these at the chosen size.
     */
    private val _tracePicks = MutableStateFlow<List<String>>(emptyList())
    val tracePicks: StateFlow<List<String>> = _tracePicks.asStateFlow()

    /** Adds a repeater to the trace path, or removes it if already picked. */
    fun toggleTracePick(contactId: String) {
        _tracePicks.update { if (contactId in it) it - contactId else it + contactId }
    }

    fun clearTracePicks() {
        _tracePicks.value = emptyList()
    }

    /**
     * Traces the route through [hashesHex], a concatenation of repeater hashes.
     * [onError] fires when no repeater answers, which is the usual failure.
     */
    fun traceRoute(hashesHex: String, hashSizeBytes: Int, onError: (String) -> Unit) {
        val hashes = runCatching { with(ChannelCrypto) { sanitizeHex(hashesHex).decodeHex() } }
            .getOrNull()
        if (hashes == null || hashes.isEmpty() || hashes.size % hashSizeBytes != 0) {
            onError("Enter whole repeater hashes, ${hashSizeBytes * 2} hex characters each")
            return
        }
        viewModelScope.launch {
            _isTracing.value = true
            _trace.value = null
            val started = System.currentTimeMillis()
            repository.traceRoute(hashes, hashSizeBytes)
                .onSuccess {
                    _trace.value = TraceResult(
                        hops = toTraceHops(it),
                        elapsedMs = System.currentTimeMillis() - started,
                        finalSnr = it.finalSnr,
                        selfName = repository.selfName.value ?: "This node",
                    )
                }
                .onFailure { onError(it.message ?: "The trace did not come back") }
            _isTracing.value = false
        }
    }

    private fun toTraceHops(data: MeshFrame.TraceData): List<TraceHop> {
        val contacts = repository.rawContacts.value
        return (0 until data.hops).map { index ->
            val hashHex = data.hashAt(index).joinToString("") { "%02X".format(it) }
            val resolved = resolveRepeaterName(hashHex, contacts)
            TraceHop(
                hashHex = hashHex,
                name = (resolved as? RepeaterName.Known)?.name,
                snr = data.hopSnrs[index],
                candidates = when (resolved) {
                    is RepeaterName.Known -> listOf(resolved.name)
                    is RepeaterName.Duplicated -> resolved.names
                    is RepeaterName.Unknown -> emptyList()
                },
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
