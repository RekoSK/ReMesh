package com.rekosk.remesh.data

import android.util.Log
import com.rekosk.remesh.ble.ChannelCrypto
import com.rekosk.remesh.ble.ConnectionState
import com.rekosk.remesh.ble.MeshBleException
import com.rekosk.remesh.ble.MeshCoreBleClient
import com.rekosk.remesh.ble.MeshCoreProtocol
import com.rekosk.remesh.ble.MeshFrame
import com.rekosk.remesh.data.model.Channel
import com.rekosk.remesh.data.model.ChannelKind
import com.rekosk.remesh.data.model.Contact
import com.rekosk.remesh.data.model.DeliveryState
import com.rekosk.remesh.data.model.HeardRepeat
import com.rekosk.remesh.data.model.LoggedPacket
import com.rekosk.remesh.data.model.NearbyNode
import com.rekosk.remesh.data.model.RecentAdvert
import com.rekosk.remesh.data.config.AutoAddConfig
import com.rekosk.remesh.data.config.ChannelConfig
import com.rekosk.remesh.data.config.ConfigCodec
import com.rekosk.remesh.data.config.ConfigFile
import com.rekosk.remesh.data.config.ConfigSection
import com.rekosk.remesh.data.config.ContactConfig
import com.rekosk.remesh.data.config.OtherConfig
import com.rekosk.remesh.data.config.PositionConfig
import com.rekosk.remesh.data.config.RadioConfig
import com.rekosk.remesh.data.model.MeshMessage
import com.rekosk.remesh.data.model.NodeSettings
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.data.model.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel as CoroutineChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import kotlin.random.Random

private const val TAG = "MeshRepository"

/** Guard against a firmware that never answers NO_MORE_MESSAGES. */
private const val MAX_DRAIN_ITERATIONS = 200

/**
 * Owns the node's state as the app sees it. Everything above this class works in
 * app models and never sees a protocol frame.
 */
class MeshRepository(
    private val client: MeshCoreBleClient,
    private val scope: CoroutineScope,
) {
    val connectionState: StateFlow<ConnectionState> = client.state
    val devices: StateFlow<List<com.rekosk.remesh.ble.DiscoveredDevice>> = client.devices

    val isRadioConnected: StateFlow<Boolean> =
        client.state
            .map { it is ConnectionState.Ready }
            .stateIn(scope, SharingStarted.Eagerly, false)

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<MeshMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<MeshMessage>>> = _messages.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _selfInfo = MutableStateFlow<MeshFrame.SelfInfo?>(null)
    val selfInfo: StateFlow<MeshFrame.SelfInfo?> = _selfInfo.asStateFlow()

    private val _deviceInfo = MutableStateFlow<MeshFrame.DeviceInfo?>(null)
    val deviceInfo: StateFlow<MeshFrame.DeviceInfo?> = _deviceInfo.asStateFlow()

    /** Battery + flash usage, refreshed on demand by the settings screen. */
    private val _storage = MutableStateFlow<MeshFrame.Battery?>(null)
    val storage: StateFlow<MeshFrame.Battery?> = _storage.asStateFlow()

    private val _selfName = MutableStateFlow<String?>(null)
    val selfName: StateFlow<String?> = _selfName.asStateFlow()

    /** Conversation id -> the 6-byte key prefix a DM is addressed to. */
    private val contactKeys = mutableMapOf<String, ByteArray>()

    /** Newest `lastmod` we hold, so re-syncs can ask for a delta. */
    private var contactsSince = 0L

    /** How many channel slots this node has. DEVICE_INFO tells us at handshake. */
    private var maxChannels = MAX_CHANNEL_SLOTS

    private val syncMutex = Mutex()

    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 32)

    /** Fire-once notifications for the UI and the notification service. */
    val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    // Conflated: while a drain or a contact reload is running, further requests
    // collapse into one more pass. The push collector must never block on them --
    // it also carries the LOG_RX_DATA firehose that heard-repeat matching needs.
    private val drainRequests = CoroutineChannel<Unit>(CoroutineChannel.CONFLATED)
    private val contactReloadRequests = CoroutineChannel<Unit>(CoroutineChannel.CONFLATED)

    init {
        scope.launch {
            for (unused in drainRequests) {
                runCatching { drainMessages() }
                    .onFailure { Log.w(TAG, "drain after MSG_WAITING failed", it) }
            }
        }
        scope.launch {
            for (unused in contactReloadRequests) {
                runCatching { loadContacts() }
                    .onFailure { Log.w(TAG, "contact reload after advert failed", it) }
            }
        }
        scope.launch {
            client.pushes.collect { frame ->
                when (frame) {
                    // The node is telling us its queue is non-empty.
                    is MeshFrame.MessagesWaiting -> drainRequests.trySend(Unit)
                    // Our own node overheard a packet. Might be a repeat of ours.
                    is MeshFrame.LogRxData -> {
                        logPacket(frame)
                        onHeardPacket(frame)
                    }
                    is MeshFrame.Unhandled -> when (frame.code) {
                        // A new node advertised; its contact row may be new.
                        MeshCoreProtocol.Push.NEW_ADVERT -> {
                            contactReloadRequests.trySend(Unit)
                            _events.tryEmit(MeshEvent.NewContact(advertName(frame.raw)))
                        }
                        MeshCoreProtocol.Push.ADVERT -> contactReloadRequests.trySend(Unit)
                        MeshCoreProtocol.Push.CONTACTS_FULL -> _events.tryEmit(MeshEvent.ContactsFull)
                        else -> Unit
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * PUSH_CODE_NEW_ADVERT carries a whole contact row (`writeContactRespFrame`): the
     * same 148-byte layout as a CONTACT reply, differing only in the opcode. Rebuild
     * that opcode so the existing decoder can read it.
     */
    private fun advertName(raw: ByteArray): String {
        if (raw.size < MeshCoreProtocol.CONTACT_FRAME_SIZE) return "A new node"
        val asContact = raw.copyOf().also { it[0] = MeshCoreProtocol.Resp.CONTACT.toByte() }
        val name = (MeshCoreProtocol.decode(asContact) as? MeshFrame.Contact)?.name
        return name?.ifBlank { null } ?: "A new node"
    }

    fun startScan() = client.startScan()
    fun stopScan() = client.stopScan()
    fun clearError() { _lastError.value = null }

    suspend fun connect(address: String) {
        _lastError.value = null
        try {
            client.connect(address)
            handshake()
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Connection failed"
            throw e
        }
    }

    fun disconnect() {
        client.disconnect()
        _contacts.value = emptyList()
        _channels.value = emptyList()
        _messages.value = emptyMap()
        contactKeys.clear()
        synchronized(sentPayloads) { sentPayloads.clear() }
        contactsSince = 0L
        _selfName.value = null
        _selfInfo.value = null
        _deviceInfo.value = null
        _storage.value = null
        _autoAdd.value = null
        _customVars.value = null
    }

    /**
     * The sequence the protocol doc mandates after the link is up. Order matters:
     * APP_START must come first, and the device clock has to be set before any
     * outgoing message, or the node's replay protection rejects our timestamps.
     */
    private suspend fun handshake() = syncMutex.withLock {
        _isSyncing.value = true
        try {
            val self = client.commandSingle(MeshCoreProtocol.encodeAppStart("ReMesh"))
            if (self is MeshFrame.SelfInfo) {
                _selfInfo.value = self
                _selfName.value = self.name
            }

            val info = client.commandSingle(MeshCoreProtocol.encodeDeviceQuery())
            if (info is MeshFrame.DeviceInfo) _deviceInfo.value = info
            maxChannels = (info as? MeshFrame.DeviceInfo)?.maxChannels?.coerceIn(1, MAX_CHANNEL_SLOTS)
                ?: MAX_CHANNEL_SLOTS

            client.commandSingle(
                MeshCoreProtocol.encodeSetDeviceTime(System.currentTimeMillis() / 1000),
            )

            loadContactsLocked()
            loadChannelsLocked()
            // Anything the node hands over now was queued while we were away.
            drainMessagesLocked(whileDisconnected = true)
        } finally {
            _isSyncing.value = false
        }
        // Feeds the settings screens. refreshNodeExtras takes no lock of its own.
        refreshNodeExtras()
    }

    /**
     * Re-read contacts and pull anything queued. Read-only: this transmits
     * nothing on the radio. Advertising is a separate, explicit action --
     * see [sendAdvert].
     */
    suspend fun sync() = syncMutex.withLock {
        if (connectionState.value !is ConnectionState.Ready) {
            _lastError.value = "Not connected to a node"
            return
        }
        _isSyncing.value = true
        try {
            loadContactsLocked()
            drainMessagesLocked()
        } catch (e: MeshBleException) {
            _lastError.value = e.message
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Transmits our own advert. [flood] sends it across the mesh; otherwise it
     * is zero-hop, reaching only nodes in direct radio range.
     */
    suspend fun sendAdvert(flood: Boolean) {
        if (connectionState.value !is ConnectionState.Ready) {
            _lastError.value = "Not connected to a node"
            return
        }
        try {
            val reply = client.commandSingle(MeshCoreProtocol.encodeSendSelfAdvert(flood))
            if (reply is MeshFrame.Error) _lastError.value = reply.message
        } catch (e: MeshBleException) {
            _lastError.value = e.message
        }
    }

    /**
     * Writes the settings the user actually changed, then re-reads SELF_INFO so the
     * screen shows what the node accepted rather than what we asked for.
     *
     * Returns null on success, or the first error message. Each sub-command is
     * independent: a rejected radio change does not roll back an accepted rename,
     * because the firmware has already persisted it with `savePrefs()`.
     */
    suspend fun applySettings(original: NodeSettings, edited: NodeSettings): String? {
        if (connectionState.value !is ConnectionState.Ready) return "Not connected to a node"

        val self = _selfInfo.value ?: return "Node info not loaded yet"
        edited.validate(self.txPowerCeiling)?.let { return it }

        return try {
            if (edited.name != original.name) {
                failureOf(client.commandSingle(MeshCoreProtocol.encodeSetAdvertName(edited.name)))
                    ?.let { return "Name: $it" }
            }

            if (edited.latE6 != original.latE6 || edited.lonE6 != original.lonE6) {
                failureOf(
                    client.commandSingle(
                        MeshCoreProtocol.encodeSetAdvertLatLon(edited.latE6, edited.lonE6),
                    ),
                )?.let { return "Location: $it" }
            }

            if (edited.shareLocation != original.shareLocation) {
                // SET_OTHER_PARAMS overwrites every byte it receives, so the three
                // fields we are not changing must be echoed back at their current values.
                failureOf(
                    client.commandSingle(
                        MeshCoreProtocol.encodeSetOtherParams(
                            manualAddContacts = self.manualAddContacts,
                            telemetryMode = self.telemetryMode,
                            advertLocPolicy = if (edited.shareLocation) {
                                MeshCoreProtocol.AdvertLoc.SHARE
                            } else {
                                MeshCoreProtocol.AdvertLoc.NONE
                            },
                            multiAcks = self.multiAcks,
                        ),
                    ),
                )?.let { return "Location sharing: $it" }
            }

            if (edited.radioDiffers(original)) {
                failureOf(
                    client.commandSingle(
                        MeshCoreProtocol.encodeSetRadioParams(
                            freqKhz = edited.freqKhz,
                            bandwidthHz = edited.bandwidthHz,
                            spreadingFactor = edited.spreadingFactor,
                            codingRate = edited.codingRate,
                            // Omitting this byte would reset client repeat to off.
                            clientRepeat = _deviceInfo.value?.clientRepeat ?: false,
                        ),
                    ),
                )?.let { return "Radio: $it" }
            }

            if (edited.txPowerDbm != original.txPowerDbm) {
                failureOf(
                    client.commandSingle(MeshCoreProtocol.encodeSetRadioTxPower(edited.txPowerDbm)),
                )?.let { return "TX power: $it" }
            }

            refreshSelfInfo()
            null
        } catch (e: MeshBleException) {
            e.message ?: "Write failed"
        }
    }

    /** APP_START is the only command that returns a fresh SELF_INFO. */
    private suspend fun refreshSelfInfo() {
        val reply = client.commandSingle(MeshCoreProtocol.encodeAppStart("ReMesh"))
        if (reply is MeshFrame.SelfInfo) {
            _selfInfo.value = reply
            _selfName.value = reply.name
        }
    }

    private fun failureOf(frame: MeshFrame): String? = when (frame) {
        is MeshFrame.Ok -> null
        is MeshFrame.Error -> frame.message
        else -> "Unexpected reply 0x%02X".format(frame.code)
    }

    // ---------------- node settings screens ----------------

    /** Raw frames kept alongside the UI models, because a config export needs the
     *  fields the UI throws away: channel secrets, contact flags and path bytes. */
    private val _rawContacts = MutableStateFlow<List<MeshFrame.Contact>>(emptyList())
    val rawContacts: StateFlow<List<MeshFrame.Contact>> = _rawContacts.asStateFlow()

    private val _rawChannels = MutableStateFlow<List<MeshFrame.ChannelInfo>>(emptyList())
    val rawChannels: StateFlow<List<MeshFrame.ChannelInfo>> = _rawChannels.asStateFlow()

    private val _autoAdd = MutableStateFlow<MeshFrame.AutoAddConfig?>(null)
    val autoAdd: StateFlow<MeshFrame.AutoAddConfig?> = _autoAdd.asStateFlow()

    private val _customVars = MutableStateFlow<MeshFrame.CustomVars?>(null)
    val customVars: StateFlow<MeshFrame.CustomVars?> = _customVars.asStateFlow()

    /** Reads the settings the extra screens edit. Called after the handshake. */
    suspend fun refreshNodeExtras() {
        if (connectionState.value !is ConnectionState.Ready) return
        runCatching {
            val reply = client.commandSingle(MeshCoreProtocol.encodeGetAutoAddConfig())
            if (reply is MeshFrame.AutoAddConfig) _autoAdd.value = reply
        }
        runCatching {
            val reply = client.commandSingle(MeshCoreProtocol.encodeGetCustomVars())
            if (reply is MeshFrame.CustomVars) _customVars.value = reply
        }
    }

    suspend fun setBlePin(pin: Int): String? = write("Bluetooth PIN") {
        client.commandSingle(MeshCoreProtocol.encodeSetDevicePin(pin))
    }

    suspend fun setAutoAddConfig(config: Int, maxHops: Int): String? {
        val error = write("Contact settings") {
            client.commandSingle(MeshCoreProtocol.encodeSetAutoAddConfig(config, maxHops))
        }
        if (error == null) _autoAdd.value = MeshFrame.AutoAddConfig(config, maxHops)
        return error
    }

    suspend fun setPathHashMode(mode: Int): String? = write("Path hash mode") {
        client.commandSingle(MeshCoreProtocol.encodeSetPathHashMode(mode))
    }

    suspend fun setGpsEnabled(enabled: Boolean): String? {
        val error = write("GPS") {
            client.commandSingle(MeshCoreProtocol.encodeSetGpsEnabled(enabled))
        }
        if (error == null) {
            val values = _customVars.value?.values.orEmpty() + ("gps" to if (enabled) "1" else "0")
            _customVars.value = MeshFrame.CustomVars(values)
        }
        return error
    }

    /**
     * Telemetry modes and the direct-message ack count share `CMD_SET_OTHER_PARAMS`,
     * which overwrites every field it receives -- so both callers go through here
     * and pass the values they are not changing.
     */
    suspend fun setOtherParams(
        telemetryBase: Int = MeshCoreProtocol.Telemetry.base(selfTelemetryMode()),
        telemetryLocation: Int = MeshCoreProtocol.Telemetry.location(selfTelemetryMode()),
        telemetryEnvironment: Int = MeshCoreProtocol.Telemetry.environment(selfTelemetryMode()),
        multiAcks: Int = _selfInfo.value?.multiAcks ?: 1,
    ): String? {
        val self = _selfInfo.value ?: return "Node info not loaded yet"
        val error = write("Settings") {
            client.commandSingle(
                MeshCoreProtocol.encodeSetOtherParams(
                    manualAddContacts = self.manualAddContacts,
                    telemetryMode = MeshCoreProtocol.Telemetry.pack(
                        telemetryBase,
                        telemetryLocation,
                        telemetryEnvironment,
                    ),
                    advertLocPolicy = self.advertLocPolicy,
                    multiAcks = multiAcks,
                ),
            )
        }
        if (error == null) refreshSelfInfo()
        return error
    }

    private fun selfTelemetryMode(): Int = _selfInfo.value?.telemetryMode ?: 0

    /** Returns the 64-byte identity keypair, or an error message. */
    suspend fun exportPrivateKey(): Result<ByteArray> = runCatching {
        if (connectionState.value !is ConnectionState.Ready) error("Not connected to a node")
        when (val reply = client.commandSingle(MeshCoreProtocol.encodeExportPrivateKey())) {
            is MeshFrame.PrivateKey -> reply.keyPair
            is MeshFrame.Disabled -> error("This firmware was built without private key export")
            is MeshFrame.Error -> error(reply.message)
            else -> error("Unexpected reply 0x%02X".format(reply.code))
        }
    }

    suspend fun importPrivateKey(keyPair: ByteArray): String? {
        if (keyPair.size != 64) return "A private key must be 64 bytes (128 hex characters)"
        val error = write("Import key") {
            client.commandSingle(MeshCoreProtocol.encodeImportPrivateKey(keyPair))
        }
        // The node adopts a new identity, so everything we cached about it is stale.
        if (error == null) refreshSelfInfo()
        return error
    }

    // ---------------- configuration import / export ----------------

    /** Builds the config file for [sections], fetching the private key only if asked. */
    suspend fun buildConfig(sections: Set<ConfigSection>): Result<ConfigFile> = runCatching {
        val self = _selfInfo.value ?: error("Not connected to a node")

        val privateKey = if (ConfigSection.PRIVATE_KEY in sections) {
            exportPrivateKey().getOrThrow().toHex()
        } else {
            null
        }

        ConfigFile(
            name = if (ConfigSection.NAME in sections) self.name else null,
            // The reference app writes the public key alongside the private one.
            publicKey = if (ConfigSection.PRIVATE_KEY in sections) self.publicKey.toHex() else null,
            privateKey = privateKey,
            radio = if (ConfigSection.RADIO !in sections) null else RadioConfig(
                frequency = self.radioFreqKhz.toInt(),
                bandwidth = self.radioBandwidthHz.toInt(),
                spreadingFactor = self.spreadingFactor,
                codingRate = self.codingRate,
                txPower = self.txPower,
            ),
            position = if (ConfigSection.POSITION !in sections) null else PositionConfig(
                latitude = self.latE6 / 1e6,
                longitude = self.lonE6 / 1e6,
            ),
            other = if (ConfigSection.OTHER !in sections) null else OtherConfig(
                manualAddContacts = if (self.manualAddContacts) 1 else 0,
                advertLocationPolicy = self.advertLocPolicy,
            ),
            autoAdd = if (ConfigSection.AUTO_ADD !in sections) null else {
                val cfg = _autoAdd.value ?: error("Auto add settings not loaded yet")
                AutoAddConfig(
                    chat = cfg.has(MeshCoreProtocol.AutoAdd.CHAT),
                    repeater = cfg.has(MeshCoreProtocol.AutoAdd.REPEATER),
                    roomServer = cfg.has(MeshCoreProtocol.AutoAdd.ROOM),
                    sensor = cfg.has(MeshCoreProtocol.AutoAdd.SENSOR),
                    overwriteOldest = cfg.has(MeshCoreProtocol.AutoAdd.OVERWRITE_OLDEST),
                    maxHops = cfg.maxHops,
                )
            },
            channels = if (ConfigSection.CHANNELS !in sections) null else {
                _rawChannels.value.map { ChannelConfig(it.name, it.secret.toHex()) }
            },
            contacts = if (ConfigSection.CONTACTS !in sections) null else {
                _rawContacts.value.map { contact ->
                    ContactConfig(
                        type = contact.type,
                        name = contact.name,
                        // The firmware has no per-contact nickname; only the app does.
                        customName = null,
                        publicKey = contact.publicKey.toHex(),
                        flags = contact.flags,
                        latitude = contact.latE6 / 1e6,
                        longitude = contact.lonE6 / 1e6,
                        lastAdvert = contact.lastAdvertEpochSec,
                        lastModified = contact.lastModEpochSec,
                        outPathList = ConfigCodec.formatOutPath(contact.outPathLen, contact.outPath),
                    )
                }
            },
        )
    }

    /**
     * Applies [sections] of [config] to the node, one command at a time. Returns a
     * per-section report rather than a single error, because the firmware persists
     * each accepted command immediately -- a later failure cannot undo an earlier
     * success, so the user needs to know exactly how far it got.
     */
    suspend fun applyConfig(
        config: ConfigFile,
        sections: Set<ConfigSection>,
        onProgress: (String) -> Unit = {},
    ): List<String> {
        if (connectionState.value !is ConnectionState.Ready) return listOf("Not connected to a node")
        val errors = mutableListOf<String>()

        suspend fun step(label: String, block: suspend () -> String?) {
            onProgress(label)
            runCatching { block() }
                .onSuccess { error -> error?.let { errors += "$label: $it" } }
                .onFailure { errors += "$label: ${it.message}" }
        }

        if (ConfigSection.NAME in sections) config.name?.let { name ->
            step("Name") { write("Name") { client.commandSingle(MeshCoreProtocol.encodeSetAdvertName(name)) } }
        }
        if (ConfigSection.PRIVATE_KEY in sections) config.privateKey?.let { hex ->
            step("Private key") { importPrivateKey(hex.hexToBytes()) }
        }
        if (ConfigSection.RADIO in sections) config.radio?.let { radio ->
            step("Radio settings") {
                write("Radio settings") {
                    client.commandSingle(
                        MeshCoreProtocol.encodeSetRadioParams(
                            freqKhz = radio.frequency,
                            bandwidthHz = radio.bandwidth,
                            spreadingFactor = radio.spreadingFactor,
                            codingRate = radio.codingRate,
                            clientRepeat = _deviceInfo.value?.clientRepeat ?: false,
                        ),
                    )
                }
            }
            step("TX power") {
                write("TX power") {
                    client.commandSingle(MeshCoreProtocol.encodeSetRadioTxPower(radio.txPower))
                }
            }
        }
        if (ConfigSection.POSITION in sections) config.position?.let { position ->
            step("Position") {
                write("Position") {
                    client.commandSingle(
                        MeshCoreProtocol.encodeSetAdvertLatLon(
                            latE6 = Math.round(position.latitude * 1e6).toInt(),
                            lonE6 = Math.round(position.longitude * 1e6).toInt(),
                        ),
                    )
                }
            }
        }
        if (ConfigSection.OTHER in sections) config.other?.let { other ->
            val self = _selfInfo.value
            step("Other settings") {
                write("Other settings") {
                    client.commandSingle(
                        MeshCoreProtocol.encodeSetOtherParams(
                            manualAddContacts = other.manualAddContacts != 0,
                            telemetryMode = self?.telemetryMode ?: 0,
                            advertLocPolicy = other.advertLocationPolicy,
                            multiAcks = self?.multiAcks ?: 0,
                        ),
                    )
                }
            }
        }
        if (ConfigSection.AUTO_ADD in sections) config.autoAdd?.let { auto ->
            var bits = 0
            if (auto.chat) bits = bits or MeshCoreProtocol.AutoAdd.CHAT
            if (auto.repeater) bits = bits or MeshCoreProtocol.AutoAdd.REPEATER
            if (auto.roomServer) bits = bits or MeshCoreProtocol.AutoAdd.ROOM
            if (auto.sensor) bits = bits or MeshCoreProtocol.AutoAdd.SENSOR
            if (auto.overwriteOldest) bits = bits or MeshCoreProtocol.AutoAdd.OVERWRITE_OLDEST
            step("Auto add settings") { setAutoAddConfig(bits, auto.maxHops) }
        }
        if (ConfigSection.CHANNELS in sections) config.channels?.let { channels ->
            channels.forEachIndexed { index, channel ->
                step("Channel ${index + 1}/${channels.size}") {
                    write("Channel '${channel.name}'") {
                        client.commandSingle(
                            MeshCoreProtocol.encodeSetChannel(
                                index = index,
                                name = channel.name,
                                secret = channel.secret.hexToBytes(),
                            ),
                        )
                    }
                }
            }
        }
        if (ConfigSection.CONTACTS in sections) config.contacts?.let { contacts ->
            contacts.forEachIndexed { index, contact ->
                step("Contact ${index + 1}/${contacts.size}") {
                    write("Contact '${contact.name}'") {
                        client.commandSingle(
                            MeshCoreProtocol.encodeAddUpdateContact(
                                publicKey = contact.publicKey.hexToBytes(),
                                type = contact.type,
                                flags = contact.flags,
                                outPathLen = contact.outPathList?.let { it.length / 2 } ?: -1,
                                outPath = contact.outPathList?.hexToBytes() ?: ByteArray(0),
                                name = contact.name,
                                lastAdvertEpochSec = contact.lastAdvert,
                                latE6 = Math.round(contact.latitude * 1e6).toInt(),
                                lonE6 = Math.round(contact.longitude * 1e6).toInt(),
                                lastModEpochSec = contact.lastModified,
                            ),
                        )
                    }
                }
            }
        }

        // Whatever landed, the node is now the source of truth again.
        runCatching { refreshSelfInfo() }
        runCatching { refreshNodeExtras() }
        return errors
    }

    /** Runs a single command and maps its reply to null (ok) or an error string. */
    private suspend fun write(label: String, block: suspend () -> MeshFrame): String? {
        if (connectionState.value !is ConnectionState.Ready) return "Not connected to a node"
        return try {
            when (val reply = block()) {
                is MeshFrame.Ok -> null
                is MeshFrame.Error -> "$label: ${reply.message}"
                is MeshFrame.Disabled -> "$label: not supported by this firmware"
                else -> "$label: unexpected reply 0x%02X".format(reply.code)
            }
        } catch (e: MeshBleException) {
            e.message ?: "$label: write failed"
        } catch (e: IllegalArgumentException) {
            e.message ?: "$label: invalid value"
        }
    }

    /**
     * Everything the telemetry screen shows: battery from the node, position from
     * a fresh SELF_INFO. Both are reads; nothing is transmitted on the radio.
     */
    suspend fun refreshTelemetry(): String? {
        if (connectionState.value !is ConnectionState.Ready) return "Not connected to a node"
        return try {
            refreshStorage()
            refreshSelfInfo()
            null
        } catch (e: MeshBleException) {
            e.message ?: "Could not read telemetry"
        }
    }

    /** Reads flash usage off the node (`CMD_GET_BATT_AND_STORAGE`). Read-only. */
    suspend fun refreshStorage() {
        if (connectionState.value !is ConnectionState.Ready) return
        try {
            val reply = client.commandSingle(MeshCoreProtocol.encodeGetBatteryAndStorage())
            if (reply is MeshFrame.Battery) _storage.value = reply
        } catch (e: MeshBleException) {
            _lastError.value = e.message
        }
    }

    /**
     * Our node as a shareable `meshcore://contact/add` link (see docs/qr_codes.md).
     * Null until the APP_START handshake has returned SELF_INFO.
     */
    fun selfContactUri(): String? {
        val info = _selfInfo.value ?: return null
        return contactUri(info.name, info.publicKey, info.advType)
    }

    private suspend fun loadContacts() = syncMutex.withLock { loadContactsLocked() }

    private suspend fun loadContactsLocked() {
        val frames = client.command(
            MeshCoreProtocol.encodeGetContacts(contactsSince),
            isTerminal = { it is MeshFrame.EndOfContacts || it is MeshFrame.Error },
            timeoutMs = 20_000, // a full contact list is many frames
        )
        (frames.lastOrNull() as? MeshFrame.EndOfContacts)?.let {
            if (it.mostRecentLastMod > 0) contactsSince = it.mostRecentLastMod
        }

        val rows = frames.filterIsInstance<MeshFrame.Contact>()
        if (rows.isEmpty()) return

        _rawContacts.update { existing ->
            val byKey = existing.associateBy { it.publicKey.toList() }.toMutableMap()
            rows.forEach { byKey[it.publicKey.toList()] = it }
            byKey.values.toList()
        }

        val fetched = rows.map { row ->
            val contact = row.toContact()
            contactKeys[contact.id] = row.keyPrefix
            contact
        }

        // A delta sync only returns changed rows, so merge rather than replace.
        _contacts.update { existing ->
            val byId = existing.associateBy { it.id }.toMutableMap()
            fetched.forEach { byId[it.id] = it }
            byId.values.sortedWith(
                compareByDescending<Contact> { it.lastSeenEpochMs ?: 0L }.thenBy { it.name },
            )
        }
    }

    private suspend fun loadChannelsLocked() {
        val found = mutableListOf<Channel>()
        val raw = mutableListOf<MeshFrame.ChannelInfo>()
        for (index in 0 until maxChannels) {
            val frame = runCatching {
                client.commandSingle(MeshCoreProtocol.encodeGetChannel(index))
            }.getOrNull() ?: continue
            val info = frame as? MeshFrame.ChannelInfo ?: continue // ERR = empty slot
            if (info.isEmpty) continue
            raw += info
            found += Channel(
                id = channelConversationId(info.index),
                index = info.index,
                name = info.name,
                kind = channelKind(info.name, info.secret),
            )
        }
        _channels.value = found
        _rawChannels.value = raw
    }

    private suspend fun drainMessages() = syncMutex.withLock { drainMessagesLocked() }

    /**
     * Pull queued messages one at a time until the node says it has no more.
     *
     * [whileDisconnected] marks the burst the node hands over right after a
     * handshake: those piled up while the app was away, and the user asked to be
     * notified about them under a separate preference.
     */
    private suspend fun drainMessagesLocked(whileDisconnected: Boolean = false) {
        repeat(MAX_DRAIN_ITERATIONS) {
            val frame = client.commandSingle(MeshCoreProtocol.encodeSyncNextMessage())
            when (frame) {
                is MeshFrame.NoMoreMessages -> return
                is MeshFrame.ContactMessage -> appendContactMessage(frame, whileDisconnected)
                is MeshFrame.ChannelMessage -> appendChannelMessage(frame, whileDisconnected)
                is MeshFrame.Error -> return
                else -> Unit // datagrams and anything else we do not render yet
            }
        }
        Log.w(TAG, "stopped draining after $MAX_DRAIN_ITERATIONS messages")
    }

    private fun appendContactMessage(frame: MeshFrame.ContactMessage, whileDisconnected: Boolean) {
        val id = contactConversationId(frame.keyPrefix)
        val author = _contacts.value.firstOrNull { it.id == id }?.name ?: "Unknown"
        val added = append(
            id,
            MeshMessage(
                id = "${id}-${frame.timestampEpochSec}-${frame.text.hashCode()}",
                author = author,
                text = frame.text,
                timestampEpochMs = frame.timestampEpochSec * 1000,
                isOutgoing = false,
                receivedEpochMs = System.currentTimeMillis(),
                hopCount = MeshCoreProtocol.PathInfo.hops(frame.pathLen),
                pathHashSizeBytes = MeshCoreProtocol.PathInfo.hashSizeBytes(frame.pathLen),
                isDirectRoute = MeshCoreProtocol.PathInfo.isDirect(frame.pathLen),
                snr = frame.snr,
            ),
        )
        if (added) {
            _events.tryEmit(
                MeshEvent.MessageReceived(
                    conversationId = id,
                    conversationTitle = author,
                    author = author,
                    text = frame.text,
                    isChannel = false,
                    whileDisconnected = whileDisconnected,
                ),
            )
        }
    }

    private fun appendChannelMessage(frame: MeshFrame.ChannelMessage, whileDisconnected: Boolean) {
        val id = channelConversationId(frame.channelIndex)
        val (sender, body) = splitChannelSender(frame.text)
        val author = sender ?: "Unknown"
        val added = append(
            id,
            MeshMessage(
                id = "${id}-${frame.timestampEpochSec}-${frame.text.hashCode()}",
                author = author,
                text = body,
                timestampEpochMs = frame.timestampEpochSec * 1000,
                isOutgoing = false,
                receivedEpochMs = System.currentTimeMillis(),
                hopCount = MeshCoreProtocol.PathInfo.hops(frame.pathLen),
                pathHashSizeBytes = MeshCoreProtocol.PathInfo.hashSizeBytes(frame.pathLen),
                isDirectRoute = MeshCoreProtocol.PathInfo.isDirect(frame.pathLen),
                snr = frame.snr,
            ),
        )
        if (added) {
            _events.tryEmit(
                MeshEvent.MessageReceived(
                    conversationId = id,
                    conversationTitle = channelById(id)?.name ?: "Channel",
                    author = author,
                    text = body,
                    isChannel = true,
                    whileDisconnected = whileDisconnected,
                ),
            )
        }
    }

    // ---------------- heard repeats ----------------

    /** Where a message we sent lives, so a heard repeat can be attributed to it. */
    private data class SentMessage(val conversationId: String, val messageId: String)

    /**
     * Payload bytes (hex) of each channel message we sent -> where it lives.
     *
     * The payload is the whole handle we have: AES-ECB has no nonce, so the bytes a
     * repeater re-transmits are byte-for-byte the ones our node originated. Bounded,
     * because a long chat session would otherwise grow it without limit.
     */
    private val sentPayloads = object : LinkedHashMap<String, SentMessage>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: Map.Entry<String, SentMessage>): Boolean =
            size > MAX_TRACKED_SENDS
    }

    /**
     * Our node overheard a packet. If it is one of ours coming back off a repeater,
     * record who re-transmitted it and how well we heard them.
     *
     * A packet with an empty path is the original as somebody else first sent it, not
     * a repeat, so it is ignored -- our own transmissions never come back to us anyway.
     */
    private fun onHeardPacket(frame: MeshFrame.LogRxData) {
        val packet = frame.packet() ?: return
        if (packet.payloadType != MeshCoreProtocol.PacketHeader.PAYLOAD_TYPE_GRP_TXT) return
        val repeaterHash = packet.lastPathHash() ?: return

        val sent = synchronized(sentPayloads) { sentPayloads[packet.payload.toHex()] } ?: return
        val hashHex = repeaterHash.toHex().uppercase()
        val heard = HeardRepeat(
            hashHex = hashHex,
            snr = frame.snr,
            rssi = frame.rssi,
            heardEpochMs = System.currentTimeMillis(),
        )

        updateMessage(sent.conversationId, sent.messageId) { message ->
            // The same repeater can be heard twice (its own retry, or a longer path
            // ending at it). One row per repeater, keeping the first reading.
            if (message.heardRepeats.any { it.hashHex == hashHex }) {
                message
            } else {
                message.copy(
                    deliveryState = DeliveryState.CONFIRMED,
                    heardRepeats = message.heardRepeats + heard,
                )
            }
        }
    }

    private fun updateMessage(
        conversationId: String,
        messageId: String,
        transform: (MeshMessage) -> MeshMessage,
    ) {
        _messages.update { all ->
            val conversation = all[conversationId] ?: return@update all
            val index = conversation.indexOfFirst { it.id == messageId }
            if (index < 0) return@update all
            all + (conversationId to conversation.toMutableList().also {
                it[index] = transform(it[index])
            })
        }
    }

    /** Forgets a message locally. The node has no delete command; nothing is sent. */
    fun deleteMessage(conversationId: String, messageId: String) {
        _messages.update { all ->
            val conversation = all[conversationId] ?: return@update all
            all + (conversationId to conversation.filterNot { it.id == messageId })
        }
        synchronized(sentPayloads) {
            sentPayloads.entries.removeAll { it.value.messageId == messageId }
        }
    }

    /** Returns false when this message was already stored, so callers do not re-notify. */
    private fun append(conversationId: String, message: MeshMessage): Boolean {
        var added = false
        _messages.update { all ->
            val existing = all[conversationId].orEmpty()
            // The node can re-deliver a message we already stored.
            if (existing.any { it.id == message.id }) {
                all
            } else {
                added = true
                all + (conversationId to (existing + message).sortedBy { it.timestampEpochMs })
            }
        }
        return added
    }

    /**
     * Sends [text] to a contact or a channel, depending on the id's prefix.
     * The local echo is appended only after the node confirms, so a bubble on
     * screen always means the radio accepted it.
     */
    suspend fun send(conversationId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        if (connectionState.value !is ConnectionState.Ready) {
            _lastError.value = "Not connected to a node"
            return
        }
        val epochSec = System.currentTimeMillis() / 1000

        try {
            val accepted: Boolean
            var onAirPayload: ByteArray? = null

            if (conversationId.startsWith(CHANNEL_PREFIX)) {
                val index = conversationId.removePrefix(CHANNEL_PREFIX).toInt()
                // Worked out before the send: after it, the node has already begun
                // transmitting and a repeat could reach us at any moment.
                onAirPayload = expectedChannelPayload(index, body, epochSec)
                val reply = client.command(
                    MeshCoreProtocol.encodeSendChannelTextMessage(index, body, epochSec),
                    // Firmware answers writeOKFrame() here, not RESP_CODE_SENT.
                    isTerminal = { it is MeshFrame.Ok || it is MeshFrame.Error },
                ).last()
                accepted = reply is MeshFrame.Ok
            } else {
                val prefix = contactKeys[conversationId]
                    ?: run { _lastError.value = "Unknown contact"; return }
                val reply = client.command(
                    MeshCoreProtocol.encodeSendTextMessage(prefix, body, epochSec),
                    isTerminal = { it is MeshFrame.Sent || it is MeshFrame.Error },
                ).last()
                accepted = reply is MeshFrame.Sent
            }

            if (!accepted) {
                _lastError.value = "Node rejected the message"
                return
            }

            val messageId = "local-${System.nanoTime()}"
            append(
                conversationId,
                MeshMessage(
                    id = messageId,
                    author = _selfName.value ?: "You",
                    text = body,
                    timestampEpochMs = epochSec * 1000,
                    isOutgoing = true,
                    // One tick: the node accepted it and put it on the air. A second
                    // tick waits for a repeater to be overheard forwarding it.
                    deliveryState = DeliveryState.SENT,
                ),
            )
            onAirPayload?.let { payload ->
                synchronized(sentPayloads) {
                    sentPayloads[payload.toHex()] = SentMessage(conversationId, messageId)
                }
            }
        } catch (e: MeshBleException) {
            _lastError.value = e.message
        }
    }

    /**
     * The bytes our node is about to put on the air for this channel message, or
     * null if we cannot reproduce them (no channel secret, or no node name yet).
     * Without them the message simply never earns its second tick.
     */
    private fun expectedChannelPayload(
        channelIndex: Int,
        text: String,
        epochSec: Long,
    ): ByteArray? {
        val secret = _rawChannels.value.firstOrNull { it.index == channelIndex }?.secret ?: return null
        val senderName = _selfName.value ?: return null
        return runCatching {
            ChannelCrypto.groupTextPayload(secret, epochSec, senderName, text)
        }.onFailure { Log.w(TAG, "cannot predict on-air payload", it) }.getOrNull()
    }

    // ---------------- channel management ----------------

    /**
     * Writes [secret] into the node's first free channel slot. Returns null on
     * success, else a message to show.
     */
    suspend fun addChannel(name: String, secret: ByteArray): String? {
        if (connectionState.value !is ConnectionState.Ready) return "Not connected to a node"
        if (name.isBlank()) return "Enter a channel name"
        if (secret.size != ChannelCrypto.SECRET_SIZE) {
            return "A channel key must be ${ChannelCrypto.SECRET_SIZE} bytes (32 hex characters)"
        }

        val existing = _rawChannels.value
        existing.firstOrNull { it.secret.contentEquals(secret) }?.let {
            return "Already joined as '${it.name}'"
        }
        val used = existing.map { it.index }.toSet()
        val slot = (0 until maxChannels).firstOrNull { it !in used }
            ?: return "All $maxChannels channel slots are in use"

        val error = write("Channel") {
            client.commandSingle(MeshCoreProtocol.encodeSetChannel(slot, name, secret))
        }
        if (error == null) syncMutex.withLock { loadChannelsLocked() }
        return error
    }

    // ---------------- tools ----------------

    private val _packetLog = MutableStateFlow<List<LoggedPacket>>(emptyList())

    /** Live view of everything the radio hears, newest first. Bounded, RAM only. */
    val packetLog: StateFlow<List<LoggedPacket>> = _packetLog.asStateFlow()

    fun clearPacketLog() { _packetLog.value = emptyList() }

    private fun logPacket(frame: MeshFrame.LogRxData) {
        val packet = frame.packet()
        val entry = LoggedPacket(
            receivedEpochMs = System.currentTimeMillis(),
            snr = frame.snr,
            rssi = frame.rssi,
            sizeBytes = frame.raw.size,
            payloadType = packet?.payloadType,
            hops = packet?.hops ?: 0,
            isFlood = packet?.isFlood ?: false,
            lastPathHash = packet?.lastPathHash()?.toHex()?.uppercase(),
        )
        _packetLog.update { (listOf(entry) + it).take(MAX_LOGGED_PACKETS) }
    }

    /**
     * Reads the node's `advert_paths` table by asking about every contact we know.
     *
     * There is no bulk command: `CMD_GET_ADVERT_PATH` answers for one key and returns
     * ERR_CODE_NOT_FOUND for the rest. The table only holds 16 entries, so most of
     * these queries come back empty, and that is the expected case rather than a fault.
     */
    suspend fun recentAdverts(): List<RecentAdvert> {
        if (connectionState.value !is ConnectionState.Ready) return emptyList()

        val found = mutableListOf<RecentAdvert>()
        for (contact in _rawContacts.value) {
            val reply = runCatching {
                client.commandSingle(MeshCoreProtocol.encodeGetAdvertPath(contact.publicKey))
            }.getOrNull() ?: continue
            val advert = reply as? MeshFrame.AdvertPath ?: continue // ERR = not in the table

            found += RecentAdvert(
                name = contact.name.ifBlank { "(unnamed)" },
                publicKey = contact.publicKey,
                type = nodeTypeOf(contact.type),
                receivedEpochMs = advert.recvEpochSec * 1000,
                hops = advert.hops,
                isDirect = advert.hops == 0,
            )
        }
        return found.sortedByDescending { it.receivedEpochMs }
    }

    /** One reading of the radio's noise floor, for the live chart. */
    suspend fun radioStats(): MeshFrame.RadioStats? {
        if (connectionState.value !is ConnectionState.Ready) return null
        return runCatching {
            client.commandSingle(MeshCoreProtocol.encodeGetStats(MeshCoreProtocol.Stats.RADIO))
        }.getOrNull() as? MeshFrame.RadioStats
    }

    /**
     * Transmits a zero-hop discovery request and collects the replies for [windowMs].
     *
     * Nodes rate-limit their answers, so a repeat scan moments later can legitimately
     * come back empty even where a node is plainly in range.
     */
    suspend fun discoverNearbyNodes(
        windowMs: Long = 8_000,
        onFound: (NearbyNode) -> Unit,
    ): String? {
        if (connectionState.value !is ConnectionState.Ready) return "Not connected to a node"

        val tag = Random.nextInt(1, Int.MAX_VALUE)
        val seen = mutableSetOf<String>()

        val collector = scope.launch {
            client.pushes.collect { frame ->
                val control = frame as? MeshFrame.ControlData ?: return@collect
                parseDiscoveryReply(control, tag)?.let { node ->
                    if (seen.add(node.publicKey.toHex())) onFound(node)
                }
            }
        }

        return try {
            val reply = client.commandSingle(MeshCoreProtocol.encodeNodeDiscoverRequest(tag))
            if (reply is MeshFrame.Error) return reply.message
            withTimeoutOrNull(windowMs) { awaitCancellation() }
            null
        } catch (e: MeshBleException) {
            e.message ?: "Scan failed"
        } finally {
            collector.cancel()
        }
    }

    /** `[0]=0x9<type>, [1]=SNR they heard us at (x4), [2..5]=tag, [6..]=public key`. */
    private fun parseDiscoveryReply(control: MeshFrame.ControlData, tag: Int): NearbyNode? {
        val payload = control.payload
        if (payload.size < 6 + MeshCoreProtocol.PUB_KEY_SIZE) return null
        val first = payload[0].toInt() and 0xFF
        if (!MeshCoreProtocol.NodeDiscovery.isResponse(first)) return null

        val echoedTag = (payload[2].toInt() and 0xFF) or ((payload[3].toInt() and 0xFF) shl 8) or
            ((payload[4].toInt() and 0xFF) shl 16) or ((payload[5].toInt() and 0xFF) shl 24)
        if (echoedTag != tag) return null // somebody else's scan

        val publicKey = payload.copyOfRange(6, 6 + MeshCoreProtocol.PUB_KEY_SIZE)
        val known = _rawContacts.value.firstOrNull { it.publicKey.contentEquals(publicKey) }
        return NearbyNode(
            name = known?.name?.ifBlank { null },
            publicKey = publicKey,
            type = nodeTypeOf(MeshCoreProtocol.NodeDiscovery.nodeTypeOf(first)),
            // Two directions of the same link: they report how well they heard us.
            inboundSnr = control.snr,
            outboundSnr = payload[1].toInt() / 4.0f,
            rssi = control.rssi,
        )
    }

    /**
     * Traces a path through [repeaterHashes], collecting the SNR at each hop.
     *
     * The reply is a push, not a command reply, so this waits for a TRACE_DATA whose
     * tag matches ours. A trace that reaches a dead repeater simply never comes back.
     */
    suspend fun traceRoute(
        repeaterHashes: ByteArray,
        hashSizeBytes: Int,
        timeoutMs: Long = 30_000,
    ): Result<MeshFrame.TraceData> {
        if (connectionState.value !is ConnectionState.Ready) {
            return Result.failure(IllegalStateException("Not connected to a node"))
        }
        val tag = Random.nextInt(1, Int.MAX_VALUE)

        return runCatching {
            coroutineScope {
                val trace = async {
                    withTimeoutOrNull(timeoutMs) {
                        client.pushes
                            .filterIsInstance<MeshFrame.TraceData>()
                            .first { it.tag == tag }
                    }
                }
                val reply = client.command(
                    MeshCoreProtocol.encodeSendTracePath(tag, 0, repeaterHashes, hashSizeBytes),
                    isTerminal = { it is MeshFrame.Sent || it is MeshFrame.Error },
                ).last()
                if (reply is MeshFrame.Error) {
                    trace.cancel()
                    error(reply.message)
                }
                trace.await() ?: error("No repeater answered the trace")
            }
        }
    }

    /** Adds a contact by hand, the way `CMD_ADD_UPDATE_CONTACT` expects it. */
    suspend fun addContact(name: String, type: Int, publicKey: ByteArray): String? {
        if (publicKey.size != MeshCoreProtocol.PUB_KEY_SIZE) {
            return "A public key is 64 hexadecimal characters"
        }
        if (name.isBlank()) return "Enter a name"

        val error = write("Contact") {
            client.commandSingle(
                MeshCoreProtocol.encodeAddUpdateContact(
                    publicKey = publicKey,
                    type = type,
                    flags = 0,
                    // -1: no route known, so the first message floods.
                    outPathLen = -1,
                    outPath = ByteArray(0),
                    name = name.trim(),
                    lastAdvertEpochSec = 0,
                    latE6 = 0,
                    lonE6 = 0,
                    lastModEpochSec = System.currentTimeMillis() / 1000,
                ),
            )
        }
        if (error == null) loadContacts()
        return error
    }

    /**
     * Frees a channel slot by overwriting it with an empty name and a zeroed key.
     *
     * There is no delete command: `CMD_SET_CHANNEL` is all the firmware offers. A
     * zeroed secret is inert, because `MACThenDecrypt` can never authenticate a packet
     * against it, and `loadChannelsLocked` then treats the slot as free.
     */
    suspend fun deleteChannel(index: Int): String? {
        if (connectionState.value !is ConnectionState.Ready) return "Not connected to a node"

        val error = write("Channel") {
            client.commandSingle(
                MeshCoreProtocol.encodeSetChannel(index, "", ByteArray(ChannelCrypto.SECRET_SIZE)),
            )
        }
        if (error == null) {
            syncMutex.withLock { loadChannelsLocked() }
            // Its messages only ever lived in memory, and the conversation is now
            // unreachable: the slot may be reused by a completely different channel.
            _messages.update { it - channelConversationId(index) }
        }
        return error
    }

    /** The 16-byte key of a channel we hold, for sharing it. */
    fun channelSecret(index: Int): ByteArray? =
        _rawChannels.value.firstOrNull { it.index == index }?.secret

    fun channelById(id: String): Channel? = _channels.value.firstOrNull { it.id == id }
    fun contactById(id: String): Contact? = _contacts.value.firstOrNull { it.id == id }

    companion object {
        const val CHANNEL_PREFIX = "ch:"
        const val CONTACT_PREFIX = "c:"

        /** `MAX_GROUP_CHANNELS` in the firmware. DEVICE_INFO may report fewer. */
        const val MAX_CHANNEL_SLOTS = 8

        /** How many sent messages stay eligible for a late heard-repeat report. */
        const val MAX_TRACKED_SENDS = 32

        /** The reference app keeps the last 500 packets, and clears them on restart. */
        const val MAX_LOGGED_PACKETS = 500

        /**
         * The shareable contact link, per docs/qr_codes.md:
         * `meshcore://contact/add?name=<urlencoded>&public_key=<64 hex>&type=<adv type>`
         */
        fun contactUri(name: String, publicKey: ByteArray, advType: Int): String {
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val key = publicKey.joinToString("") { "%02x".format(it) }
            return "meshcore://contact/add?name=$encodedName&public_key=$key&type=$advType"
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        /** Throws on malformed input; callers wrap in runCatching. */
        fun String.hexToBytes(): ByteArray {
            require(length % 2 == 0) { "hex string must have an even length" }
            return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        fun channelConversationId(index: Int) = "$CHANNEL_PREFIX$index"

        fun contactConversationId(keyPrefix: ByteArray) =
            CONTACT_PREFIX + keyPrefix.joinToString("") { "%02x".format(it) }

        /**
         * Channel text arrives as `"<sender>: <message>"` because
         * `BaseChatMesh::sendGroupMessage` prepends the node name. Returns a null
         * sender when the prefix is absent.
         */
        fun splitChannelSender(raw: String): Pair<String?, String> {
            val sep = raw.indexOf(": ")
            if (sep <= 0) return null to raw
            return raw.substring(0, sep) to raw.substring(sep + 2)
        }

        /**
         * The key, not the slot, is what makes a channel public: the firmware happens
         * to pre-configure the public channel at slot 0, but a user who joins it again
         * lands wherever there is room.
         */
        fun channelKind(name: String, secret: ByteArray): ChannelKind = when {
            name.startsWith("#") -> ChannelKind.HASHTAG
            secret.contentEquals(ChannelCrypto.PUBLIC_SECRET) -> ChannelKind.PUBLIC
            else -> ChannelKind.PRIVATE
        }

        fun nodeTypeOf(advType: Int): NodeType = when (advType) {
            MeshCoreProtocol.AdvType.REPEATER -> NodeType.REPEATER
            MeshCoreProtocol.AdvType.ROOM -> NodeType.ROOM
            MeshCoreProtocol.AdvType.SENSOR -> NodeType.SENSOR
            else -> NodeType.CHAT
        }
    }
}

internal fun MeshFrame.Contact.toContact(): Contact = Contact(
    id = MeshRepository.contactConversationId(keyPrefix),
    name = name.ifBlank { "(unnamed)" },
    type = MeshRepository.nodeTypeOf(type),
    // int8_t: -1 means the node has no known route, so traffic floods.
    route = if (outPathLen < 0) Route.Flood else Route.Hops(outPathLen),
    // `lastmod`, not `last_advert_timestamp`. The firmware stamps lastmod from its own
    // RTC -- "update last heard time" -- while last_advert carries the timestamp the
    // *sender* wrote into its advert, and exists only for replay protection. Plenty of
    // nodes run an unset or drifting clock: their adverts claim to be from 2024, or
    // from two days in the future, which would read as "Last seen just now".
    lastSeenEpochMs = lastModEpochSec.takeIf { it > 0 }?.times(1000),
    hasLocation = latE6 != 0 || lonE6 != 0,
)
