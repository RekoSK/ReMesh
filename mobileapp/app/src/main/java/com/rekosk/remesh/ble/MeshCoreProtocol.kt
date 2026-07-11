package com.rekosk.remesh.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Encoder/decoder for the MeshCore companion protocol.
 *
 * Field layouts were read out of the ReCore firmware itself
 * (`examples/companion_radio/MyMesh.cpp`), not from `docs/companion_protocol.md`,
 * because the doc and the firmware disagree in places -- see [Resp.OK] on
 * channel sends.
 *
 * Everything here is pure: no Android, no BLE. That is what makes it testable.
 */
object MeshCoreProtocol {

    val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /** App writes commands here. */
    val RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Firmware notifies responses here. */
    val TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** BaseSerialInterface.h: one protocol frame per BLE write/notify, capped at this. */
    const val MAX_FRAME_SIZE = 176

    const val PUB_KEY_SIZE = 32
    const val MAX_PATH_SIZE = 64

    /** Contact rows are fixed width: 1 + 32 + 1 + 1 + 1 + 64 + 32 + 4 + 4 + 4 + 4. */
    const val CONTACT_FRAME_SIZE = 148

    /** The firmware truncates text so the frame fits; keep the app in step. */
    const val MAX_TEXT_LEN = 133

    object Cmd {
        const val APP_START = 1
        const val SEND_TXT_MSG = 2
        const val SEND_CHANNEL_TXT_MSG = 3
        const val GET_CONTACTS = 4
        const val ADD_UPDATE_CONTACT = 9
        const val RESET_PATH = 13
        const val REMOVE_CONTACT = 15
        const val SET_DEVICE_TIME = 6
        const val SEND_SELF_ADVERT = 7
        const val SET_ADVERT_NAME = 8
        const val SYNC_NEXT_MESSAGE = 10
        const val SET_RADIO_PARAMS = 11
        const val SET_RADIO_TX_POWER = 12
        const val SET_ADVERT_LATLON = 14
        const val GET_BATT_AND_STORAGE = 20
        const val DEVICE_QUERY = 22
        const val EXPORT_PRIVATE_KEY = 23
        const val IMPORT_PRIVATE_KEY = 24
        const val GET_CHANNEL = 31
        const val SET_CHANNEL = 32
        const val SEND_TRACE_PATH = 36
        const val SET_DEVICE_PIN = 37
        const val GET_ADVERT_PATH = 42
        const val SEND_CONTROL_DATA = 55
        const val GET_STATS = 56
        const val SET_OTHER_PARAMS = 38
        const val GET_CUSTOM_VARS = 40
        const val SET_CUSTOM_VAR = 41
        const val SET_AUTOADD_CONFIG = 58
        const val GET_AUTOADD_CONFIG = 59
        const val SET_PATH_HASH_MODE = 61
    }

    /** `_prefs.autoadd_config` bit flags (MyMesh.cpp). */
    object AutoAdd {
        const val OVERWRITE_OLDEST = 1 shl 0
        const val CHAT = 1 shl 1
        const val REPEATER = 1 shl 2
        const val ROOM = 1 shl 3
        const val SENSOR = 1 shl 4

        val ALL_TYPES = CHAT or REPEATER or ROOM or SENSOR

        /** The firmware clamps to 64, and the UI documents the range as 0..63. */
        const val MAX_HOPS_LIMIT = 63

        /** 0 means "no hop limit" -- the UI leaves the field blank. */
        const val MAX_HOPS_UNLIMITED = 0
    }

    /**
     * `telemetry_mode` packs three 2-bit fields. Only 0 (no) and 1 (yes) are
     * offered by this app; 2 means "for permitted contacts", which needs a
     * per-contact permission list we do not have yet.
     */
    object Telemetry {
        const val NO = 0
        const val YES = 1
        const val PERMITTED_CONTACTS = 2

        fun pack(base: Int, location: Int, environment: Int): Int =
            (base and 0x03) or ((location and 0x03) shl 2) or ((environment and 0x03) shl 4)

        fun base(mode: Int): Int = mode and 0x03
        fun location(mode: Int): Int = (mode shr 2) and 0x03
        fun environment(mode: Int): Int = (mode shr 4) and 0x03
    }

    /**
     * `path_hash_mode` selects how many bytes each repeater contributes to a path.
     * Mode N encodes a hash size of N+1 bytes; MAX_PATH_SIZE (64) bytes of path
     * then caps the hop count. Mode 3 is reserved by the firmware.
     */
    object PathHashMode {
        const val MIN = 0
        const val MAX = 2

        fun hashSizeBytes(mode: Int): Int = mode + 1
        fun maxHops(mode: Int): Int = MAX_PATH_SIZE / hashSizeBytes(mode)
    }

    /**
     * `multi_acks` counts the *extra* acks a node transmits after the first one
     * (`MyMesh::getExtraAckTransmitCount`), so the total sent is `multi_acks + 1`.
     * `CommonCLI.cpp` clamps the stored value with `constrain(multi_acks, 0, 1)`,
     * which is why the user-facing choice is only 1 or 2 total acks.
     */
    object MultiAcks {
        const val EXTRA_MIN = 0
        const val EXTRA_MAX = 1

        /** What the user picks: the total number of acks transmitted. */
        val TOTAL_OPTIONS = listOf(1, 2)

        fun totalOf(extra: Int): Int = extra.coerceIn(EXTRA_MIN, EXTRA_MAX) + 1

        fun extraOf(total: Int): Int = (total - 1).coerceIn(EXTRA_MIN, EXTRA_MAX)
    }

    /** Matches `BATT_MIN_MILLIVOLTS`/`BATT_MAX_MILLIVOLTS` in the firmware's UITask. */
    object Battery {
        const val MIN_MILLIVOLTS = 3000
        const val MAX_MILLIVOLTS = 4200

        fun percentOf(millivolts: Int): Int {
            val pc = (millivolts - MIN_MILLIVOLTS) * 100 / (MAX_MILLIVOLTS - MIN_MILLIVOLTS)
            return pc.coerceIn(0, 100)
        }
    }

    /**
     * The `path_len` byte carried by a received-message frame and by a raw packet.
     * `Packet.h` packs two fields into it: the upper 2 bits hold the path hash size
     * minus one, the lower 6 bits the number of hops. A whole byte of 0xFF is the
     * companion firmware's separate "arrived by a direct route" marker, which is
     * distinguishable because hash-size mode 3 is reserved and never transmitted.
     */
    object PathInfo {
        const val DIRECT = 0xFF

        fun isDirect(pathLenByte: Int): Boolean = pathLenByte == DIRECT

        /** Hop count, or 0 for a direct route (nothing to show). */
        fun hops(pathLenByte: Int): Int = if (isDirect(pathLenByte)) 0 else pathLenByte and 0x3F

        /** Bytes each repeater contributes to the path: 1, 2 or 3. */
        fun hashSizeBytes(pathLenByte: Int): Int =
            if (isDirect(pathLenByte)) 1 else (pathLenByte shr 6) + 1
    }

    /** `Packet.h` header bit fields. Only the ones this app reads are listed. */
    object PacketHeader {
        const val ROUTE_TRANSPORT_FLOOD = 0x00
        const val ROUTE_FLOOD = 0x01
        const val ROUTE_DIRECT = 0x02
        const val ROUTE_TRANSPORT_DIRECT = 0x03

        const val PAYLOAD_TYPE_GRP_TXT = 0x05

        fun routeType(header: Int): Int = header and 0x03
        fun payloadType(header: Int): Int = (header shr 2) and 0x0F
        fun payloadVersion(header: Int): Int = (header shr 6) and 0x03

        /** Transport-routed packets carry four extra bytes before the path. */
        fun hasTransportCodes(header: Int): Boolean =
            routeType(header) == ROUTE_TRANSPORT_FLOOD || routeType(header) == ROUTE_TRANSPORT_DIRECT

        fun isFlood(header: Int): Boolean =
            routeType(header) == ROUTE_FLOOD || routeType(header) == ROUTE_TRANSPORT_FLOOD
    }

    /** BLE pairing PIN. Zero tells the firmware to generate a random one per pairing. */
    object BlePin {
        const val RANDOM = 0
        const val MIN_FIXED = 100_000
        const val MAX_FIXED = 999_999
    }

    /** `_prefs.advert_loc_policy`. Anything other than NONE puts lat/lon in the advert. */
    object AdvertLoc {
        const val NONE = 0
        const val SHARE = 1
        const val PREFS = 2
    }

    /** Bounds enforced by the firmware; sending outside them earns ERR_CODE_ILLEGAL_ARG. */
    object RadioLimits {
        const val FREQ_KHZ_MIN = 150_000
        const val FREQ_KHZ_MAX = 2_500_000
        const val BW_HZ_MIN = 7_000
        const val BW_HZ_MAX = 500_000
        const val SF_MIN = 5
        const val SF_MAX = 12
        const val CR_MIN = 5
        const val CR_MAX = 8

        const val TX_POWER_MIN = -9

        /**
         * The real ceiling is the board's `MAX_LORA_TX_POWER`, reported per-node as
         * `SELF_INFO.maxTxPower`. Variants in this tree build with 20 or 22; 22 is the
         * fallback when a node reports nothing usable.
         */
        const val TX_POWER_MAX_FALLBACK = 22

        /** Standard LoRa bandwidths, in kHz, within the firmware's 7..500 kHz window. */
        val BANDWIDTHS_KHZ = listOf(7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125.0, 250.0, 500.0)

        val SPREADING_FACTORS = (SF_MIN..SF_MAX).toList()
        val CODING_RATES = (CR_MIN..CR_MAX).toList()
    }

    object Resp {
        const val OK = 0
        const val ERR = 1
        const val PRIVATE_KEY = 14
        const val DISABLED = 15
        const val CUSTOM_VARS = 21
        const val AUTOADD_CONFIG = 25
        const val CONTACTS_START = 2
        const val CONTACT = 3
        const val END_OF_CONTACTS = 4
        const val SELF_INFO = 5
        const val SENT = 6
        const val CONTACT_MSG_RECV = 7
        const val CHANNEL_MSG_RECV = 8
        const val CURR_TIME = 9
        const val NO_MORE_MESSAGES = 10
        const val BATT_AND_STORAGE = 12
        const val DEVICE_INFO = 13
        const val CONTACT_MSG_RECV_V3 = 16
        const val CHANNEL_MSG_RECV_V3 = 17
        const val CHANNEL_INFO = 18
        const val ADVERT_PATH = 22
        const val STATS = 24
        const val CHANNEL_DATA_RECV = 27
    }

    /** `CMD_GET_STATS` sub-types. Only the radio block is read by this app. */
    object Stats {
        const val CORE = 0
        const val RADIO = 1
        const val PACKETS = 2
    }

    /**
     * Zero-hop "who is out there" control packets. A node answers only if the
     * request's type mask has its own advert type set, and repeaters rate-limit
     * their replies (`discover_limiter`), so a scan can come back empty.
     */
    object NodeDiscovery {
        const val REQUEST = 0x80
        const val RESPONSE = 0x90

        /** Set in the request's first byte to ask for an 8-byte key instead of 32. */
        const val FLAG_PREFIX_ONLY = 0x01

        /** The low nibble of a response's first byte carries the responder's type. */
        fun nodeTypeOf(firstByte: Int): Int = firstByte and 0x0F

        fun isResponse(firstByte: Int): Boolean = (firstByte and 0xF0) == RESPONSE

        /** A bit per `ADV_TYPE_*`: chat, repeater, room and sensor. */
        val ALL_TYPES: Int =
            (1 shl AdvType.CHAT) or (1 shl AdvType.REPEATER) or
                (1 shl AdvType.ROOM) or (1 shl AdvType.SENSOR)
    }

    /** Asynchronous notifications; never a reply to a pending command. */
    object Push {
        const val ADVERT = 0x80
        const val PATH_UPDATED = 0x81
        const val SEND_CONFIRMED = 0x82
        const val MSG_WAITING = 0x83
        const val RAW_DATA = 0x84
        const val LOGIN_SUCCESS = 0x85
        const val LOGIN_FAIL = 0x86
        const val STATUS_RESPONSE = 0x87
        const val LOG_RX_DATA = 0x88
        const val TRACE_DATA = 0x89
        const val NEW_ADVERT = 0x8A
        const val CONTROL_DATA = 0x8E
        const val CONTACT_DELETED = 0x8F
        const val CONTACTS_FULL = 0x90
    }

    object AdvType {
        const val NONE = 0
        const val CHAT = 1
        const val REPEATER = 2
        const val ROOM = 3
        const val SENSOR = 4
    }

    object TxtType {
        const val PLAIN = 0
        const val CLI_DATA = 1
        const val SIGNED_PLAIN = 2
    }

    fun isPush(code: Int): Boolean = code >= 0x80

    fun errorMessage(code: Int): String = when (code) {
        1 -> "Unsupported command"
        2 -> "Not found"
        3 -> "Device table full, retry later"
        4 -> "Bad state"
        5 -> "Storage I/O error"
        6 -> "Illegal argument"
        else -> "Unknown error ($code)"
    }

    // ---------------- encoders ----------------

    /** `[0]=1, [1..7] reserved, [8..] app name`. Firmware requires len >= 8. */
    fun encodeAppStart(appName: String): ByteArray {
        val name = appName.toByteArray(Charsets.UTF_8)
        val out = ByteArray(8 + name.size)
        out[0] = Cmd.APP_START.toByte()
        name.copyInto(out, 8)
        return out
    }

    /** `appProtocolVersion` tells the firmware which response variants we understand. */
    fun encodeDeviceQuery(appProtocolVersion: Int = 3): ByteArray =
        byteArrayOf(Cmd.DEVICE_QUERY.toByte(), appProtocolVersion.toByte())

    fun encodeSetDeviceTime(epochSeconds: Long): ByteArray =
        buffer(5).put(Cmd.SET_DEVICE_TIME.toByte()).putInt(epochSeconds.toInt()).array()

    /** `since` is the newest `lastmod` we already hold; 0 fetches everything. */
    fun encodeGetContacts(since: Long = 0): ByteArray =
        if (since <= 0) byteArrayOf(Cmd.GET_CONTACTS.toByte())
        else buffer(5).put(Cmd.GET_CONTACTS.toByte()).putInt(since.toInt()).array()

    fun encodeGetChannel(index: Int): ByteArray =
        byteArrayOf(Cmd.GET_CHANNEL.toByte(), index.toByte())

    fun encodeSyncNextMessage(): ByteArray = byteArrayOf(Cmd.SYNC_NEXT_MESSAGE.toByte())

    fun encodeGetBatteryAndStorage(): ByteArray = byteArrayOf(Cmd.GET_BATT_AND_STORAGE.toByte())

    fun encodeSendSelfAdvert(flood: Boolean = true): ByteArray =
        byteArrayOf(Cmd.SEND_SELF_ADVERT.toByte(), if (flood) 1 else 0)

    /** `[0]=8, [1..] name`. Firmware truncates to its `node_name` buffer. */
    fun encodeSetAdvertName(name: String): ByteArray {
        val body = truncateUtf8(name, MAX_FRAME_SIZE - 1)
        require(body.isNotEmpty()) { "name must not be empty (firmware needs len >= 2)" }
        return ByteArray(1 + body.size).also {
            it[0] = Cmd.SET_ADVERT_NAME.toByte()
            body.copyInto(it, 1)
        }
    }

    /** `[0]=14, [1..4]=lat*1e6, [5..8]=lon*1e6`, both signed little-endian. */
    fun encodeSetAdvertLatLon(latE6: Int, lonE6: Int): ByteArray =
        buffer(9).put(Cmd.SET_ADVERT_LATLON.toByte()).putInt(latE6).putInt(lonE6).array()

    /**
     * `[0]=11, [1..4]=freq kHz, [5..8]=bandwidth Hz, [9]=sf, [10]=cr, [11]=client_repeat`.
     *
     * The trailing repeat byte is optional in the firmware but defaults to 0 when
     * absent, which would silently turn client repeat off. Always send it.
     */
    fun encodeSetRadioParams(
        freqKhz: Int,
        bandwidthHz: Int,
        spreadingFactor: Int,
        codingRate: Int,
        clientRepeat: Boolean,
    ): ByteArray = buffer(12)
        .put(Cmd.SET_RADIO_PARAMS.toByte())
        .putInt(freqKhz)
        .putInt(bandwidthHz)
        .put(spreadingFactor.toByte())
        .put(codingRate.toByte())
        .put(if (clientRepeat) 1 else 0)
        .array()

    /** `[0]=12, [1]=dBm as int8`. Valid range is -9..maxTxPower. */
    fun encodeSetRadioTxPower(dbm: Int): ByteArray =
        byteArrayOf(Cmd.SET_RADIO_TX_POWER.toByte(), dbm.toByte())

    /**
     * `[0]=38, [1]=manual_add_contacts, [2]=telemetry bitfield, [3]=advert_loc_policy,
     * [4]=multi_acks`. The firmware overwrites each field it receives, so every byte
     * up to the one being changed must carry its current value.
     */
    fun encodeSetOtherParams(
        manualAddContacts: Boolean,
        telemetryMode: Int,
        advertLocPolicy: Int,
        multiAcks: Int,
    ): ByteArray = byteArrayOf(
        Cmd.SET_OTHER_PARAMS.toByte(),
        if (manualAddContacts) 1 else 0,
        telemetryMode.toByte(),
        advertLocPolicy.toByte(),
        // Coerced, not rejected: a node configured by another app may already hold an
        // out-of-range value, and CommonCLI would silently clamp it on the next boot.
        multiAcks.coerceIn(MultiAcks.EXTRA_MIN, MultiAcks.EXTRA_MAX).toByte(),
    )

    /**
     * `[0]=2, [1]=txt_type, [2]=attempt, [3..6]=timestamp, [7..12]=pubkey prefix, [13..]=text`.
     * The firmware demands len >= 14, so an empty text would be rejected.
     */
    fun encodeSendTextMessage(
        pubKeyPrefix: ByteArray,
        text: String,
        epochSeconds: Long,
        attempt: Int = 0,
        txtType: Int = TxtType.PLAIN,
    ): ByteArray {
        require(pubKeyPrefix.size >= 6) { "pubKeyPrefix must be at least 6 bytes" }
        val body = truncateUtf8(text, MAX_TEXT_LEN)
        val out = buffer(13 + body.size)
        out.put(Cmd.SEND_TXT_MSG.toByte())
        out.put(txtType.toByte())
        out.put(attempt.toByte())
        out.putInt(epochSeconds.toInt())
        out.put(pubKeyPrefix, 0, 6)
        out.put(body)
        return out.array()
    }

    /** `[0]=3, [1]=txt_type, [2]=channel_idx, [3..6]=timestamp, [7..]=text`. */
    fun encodeSendChannelTextMessage(
        channelIndex: Int,
        text: String,
        epochSeconds: Long,
        txtType: Int = TxtType.PLAIN,
    ): ByteArray {
        val body = truncateUtf8(text, MAX_TEXT_LEN)
        val out = buffer(7 + body.size)
        out.put(Cmd.SEND_CHANNEL_TXT_MSG.toByte())
        out.put(txtType.toByte())
        out.put(channelIndex.toByte())
        out.putInt(epochSeconds.toInt())
        out.put(body)
        return out.array()
    }

    /**
     * `[0]=37, [1..4]=pin`. Zero asks the node to generate a random PIN per pairing,
     * which only works on a node with a screen to display it. Otherwise a 6-digit PIN.
     */
    fun encodeSetDevicePin(pin: Int): ByteArray {
        require(pin == BlePin.RANDOM || pin in BlePin.MIN_FIXED..BlePin.MAX_FIXED) {
            "PIN must be 0 (random) or a 6-digit number"
        }
        return buffer(5).put(Cmd.SET_DEVICE_PIN.toByte()).putInt(pin).array()
    }

    fun encodeGetAutoAddConfig(): ByteArray = byteArrayOf(Cmd.GET_AUTOADD_CONFIG.toByte())

    /** `[0]=58, [1]=bitfield, [2]=max hops`. Max hops of 0 means no limit. */
    fun encodeSetAutoAddConfig(config: Int, maxHops: Int): ByteArray =
        byteArrayOf(Cmd.SET_AUTOADD_CONFIG.toByte(), config.toByte(), maxHops.toByte())

    /** `[0]=61, [1]=0, [2]=mode`. The firmware rejects mode >= 3. */
    fun encodeSetPathHashMode(mode: Int): ByteArray {
        require(mode in PathHashMode.MIN..PathHashMode.MAX) { "path hash mode must be 0..2" }
        return byteArrayOf(Cmd.SET_PATH_HASH_MODE.toByte(), 0, mode.toByte())
    }

    fun encodeGetCustomVars(): ByteArray = byteArrayOf(Cmd.GET_CUSTOM_VARS.toByte())

    /** `[0]=41, [1..]="name:value"`. GPS is toggled with name "gps", value "0"/"1". */
    fun encodeSetCustomVar(name: String, value: String): ByteArray {
        val body = "$name:$value".toByteArray(Charsets.UTF_8)
        require(body.size >= 3) { "firmware requires len >= 4" }
        return ByteArray(1 + body.size).also {
            it[0] = Cmd.SET_CUSTOM_VAR.toByte()
            body.copyInto(it, 1)
        }
    }

    fun encodeSetGpsEnabled(enabled: Boolean): ByteArray =
        encodeSetCustomVar("gps", if (enabled) "1" else "0")

    /**
     * `[0]=32, [1]=idx, [2..33]=name, [34..49]=16-byte secret`. The firmware also
     * accepts a 32-byte-secret form, but answers ERR_CODE_UNSUPPORTED_CMD to it.
     */
    fun encodeSetChannel(index: Int, name: String, secret: ByteArray): ByteArray {
        require(secret.size == 16) { "channel secret must be 16 bytes" }
        val out = ByteArray(50)
        out[0] = Cmd.SET_CHANNEL.toByte()
        out[1] = index.toByte()
        truncateUtf8(name, 31).copyInto(out, 2)
        secret.copyInto(out, 34)
        return out
    }

    /**
     * `CMD_ADD_UPDATE_CONTACT` takes the same 148-byte layout the node uses to
     * report a contact, only with opcode 9 in front (see `updateContactFromFrame`).
     */
    fun encodeAddUpdateContact(
        publicKey: ByteArray,
        type: Int,
        flags: Int,
        outPathLen: Int,
        outPath: ByteArray,
        name: String,
        lastAdvertEpochSec: Long,
        latE6: Int,
        lonE6: Int,
        lastModEpochSec: Long,
    ): ByteArray {
        require(publicKey.size == PUB_KEY_SIZE) { "public key must be 32 bytes" }
        val out = buffer(CONTACT_FRAME_SIZE)
        out.put(Cmd.ADD_UPDATE_CONTACT.toByte())
        out.put(publicKey)
        out.put(type.toByte())
        out.put(flags.toByte())
        out.put(outPathLen.toByte())
        val path = ByteArray(MAX_PATH_SIZE)
        outPath.copyInto(path, 0, 0, minOf(outPath.size, MAX_PATH_SIZE))
        out.put(path)
        val nameField = ByteArray(32)
        truncateUtf8(name, 31).copyInto(nameField)
        out.put(nameField)
        out.putInt(lastAdvertEpochSec.toInt())
        out.putInt(latE6)
        out.putInt(lonE6)
        out.putInt(lastModEpochSec.toInt())
        return out.array()
    }

    /**
     * `[0]=42, [1]=reserved, [2..33]=public key`. Answers ADVERT_PATH for a node in
     * the firmware's 16-entry `advert_paths` table, or ERR_CODE_NOT_FOUND -- most
     * contacts are simply not in it.
     */
    fun encodeGetAdvertPath(publicKey: ByteArray): ByteArray {
        require(publicKey.size == PUB_KEY_SIZE) { "public key must be 32 bytes" }
        return ByteArray(2 + PUB_KEY_SIZE).also {
            it[0] = Cmd.GET_ADVERT_PATH.toByte()
            publicKey.copyInto(it, 2)
        }
    }

    /** `[0]=13, [1..32]=public key`. Clears a contact's out path so traffic floods again. */
    fun encodeResetPath(publicKey: ByteArray): ByteArray {
        require(publicKey.size == PUB_KEY_SIZE) { "public key must be 32 bytes" }
        return ByteArray(1 + PUB_KEY_SIZE).also {
            it[0] = Cmd.RESET_PATH.toByte()
            publicKey.copyInto(it, 1)
        }
    }

    /** `[0]=15, [1..32]=public key`. Deletes the contact from the node's table. */
    fun encodeRemoveContact(publicKey: ByteArray): ByteArray {
        require(publicKey.size == PUB_KEY_SIZE) { "public key must be 32 bytes" }
        return ByteArray(1 + PUB_KEY_SIZE).also {
            it[0] = Cmd.REMOVE_CONTACT.toByte()
            publicKey.copyInto(it, 1)
        }
    }

    /** `[0]=56, [1]=stats type`. */
    fun encodeGetStats(type: Int): ByteArray =
        byteArrayOf(Cmd.GET_STATS.toByte(), type.toByte())

    /** `[0]=55, [1..]=control payload`, transmitted zero-hop. */
    fun encodeSendControlData(payload: ByteArray): ByteArray =
        ByteArray(1 + payload.size).also {
            it[0] = Cmd.SEND_CONTROL_DATA.toByte()
            payload.copyInto(it, 1)
        }

    /**
     * A node-discovery request: `[0]=0x80, [1]=type mask, [2..5]=tag`. Leaving bit 0
     * of the first byte clear asks responders for their whole 32-byte key, which is
     * what turns a reply into a contact we can add.
     */
    fun encodeNodeDiscoverRequest(tag: Int, typeMask: Int = NodeDiscovery.ALL_TYPES): ByteArray {
        val payload = buffer(6)
            .put(NodeDiscovery.REQUEST.toByte())
            .put(typeMask.toByte())
            .putInt(tag)
            .array()
        return encodeSendControlData(payload)
    }

    /**
     * `[0]=36, [1..4]=tag, [5..8]=auth, [9]=flags, [10..]=path`. The low two bits of
     * `flags` are a shift: each repeater in the path occupies `1 shl (flags and 3)`
     * bytes, and the firmware rejects a path that is not a whole number of them.
     */
    fun encodeSendTracePath(
        tag: Int,
        authCode: Int,
        path: ByteArray,
        pathHashSizeBytes: Int = 1,
    ): ByteArray {
        val shift = when (pathHashSizeBytes) {
            1 -> 0
            2 -> 1
            4 -> 2
            else -> throw IllegalArgumentException("path hash size must be 1, 2 or 4 bytes")
        }
        require(path.isNotEmpty()) { "a trace needs at least one repeater" }
        require(path.size % pathHashSizeBytes == 0) { "path must be whole repeater hashes" }
        require(path.size / pathHashSizeBytes <= MAX_PATH_SIZE) { "path is too long" }

        return buffer(10 + path.size)
            .put(Cmd.SEND_TRACE_PATH.toByte())
            .putInt(tag)
            .putInt(authCode)
            .put(shift.toByte())
            .put(path)
            .array()
    }

    fun encodeExportPrivateKey(): ByteArray = byteArrayOf(Cmd.EXPORT_PRIVATE_KEY.toByte())

    /** `[0]=24, [1..64]=keypair`. The firmware validates before adopting it. */
    fun encodeImportPrivateKey(keyPair: ByteArray): ByteArray {
        require(keyPair.size == 64) { "private key blob must be 64 bytes" }
        return ByteArray(65).also {
            it[0] = Cmd.IMPORT_PRIVATE_KEY.toByte()
            keyPair.copyInto(it, 1)
        }
    }

    // ---------------- decoder ----------------

    fun decode(frame: ByteArray): MeshFrame {
        if (frame.isEmpty()) return MeshFrame.Malformed(-1, "empty frame")
        val code = frame[0].toInt() and 0xFF
        return try {
            when (code) {
                Resp.OK -> MeshFrame.Ok(if (frame.size >= 5) readU32(frame, 1) else null)
                Resp.ERR -> MeshFrame.Error(if (frame.size >= 2) frame[1].toInt() and 0xFF else 0)
                Resp.CONTACTS_START -> MeshFrame.ContactsStart(readU32(frame, 1))
                Resp.CONTACT -> decodeContact(frame)
                Resp.END_OF_CONTACTS -> MeshFrame.EndOfContacts(
                    if (frame.size >= 5) readU32(frame, 1) else 0L,
                )
                Resp.SELF_INFO -> decodeSelfInfo(frame)
                Resp.SENT -> MeshFrame.Sent(
                    flood = frame[1].toInt() != 0,
                    expectedAck = readU32(frame, 2),
                    suggestedTimeoutMs = readU32(frame, 6),
                )
                Resp.NO_MORE_MESSAGES -> MeshFrame.NoMoreMessages
                Resp.DEVICE_INFO -> decodeDeviceInfo(frame)
                Resp.BATT_AND_STORAGE -> MeshFrame.Battery(
                    millivolts = readU16(frame, 1),
                    usedKb = if (frame.size >= 11) readU32(frame, 3) else 0L,
                    totalKb = if (frame.size >= 11) readU32(frame, 7) else 0L,
                    // Optional trailing charge flag; null when the node's firmware
                    // predates it (older builds send only the 11-byte reply).
                    charging = if (frame.size >= 12) frame[11].toInt() != 0 else null,
                )
                Resp.CHANNEL_INFO -> MeshFrame.ChannelInfo(
                    index = frame[1].toInt() and 0xFF,
                    name = cString(frame, 2, 32),
                    secret = frame.copyOfRange(34, 50),
                )
                Resp.CONTACT_MSG_RECV -> decodeContactMessage(frame, v3 = false)
                Resp.CONTACT_MSG_RECV_V3 -> decodeContactMessage(frame, v3 = true)
                Resp.CHANNEL_MSG_RECV -> decodeChannelMessage(frame, v3 = false)
                Resp.CHANNEL_MSG_RECV_V3 -> decodeChannelMessage(frame, v3 = true)
                Resp.CURR_TIME -> MeshFrame.CurrentTime(readU32(frame, 1))
                Resp.DISABLED -> MeshFrame.Disabled
                Resp.PRIVATE_KEY -> MeshFrame.PrivateKey(frame.copyOfRange(1, 65))
                Resp.AUTOADD_CONFIG -> MeshFrame.AutoAddConfig(
                    config = frame[1].toInt() and 0xFF,
                    maxHops = if (frame.size >= 3) frame[2].toInt() and 0xFF else 0,
                )
                Resp.CUSTOM_VARS -> MeshFrame.CustomVars(parseCustomVars(frame))
                Resp.ADVERT_PATH -> MeshFrame.AdvertPath(
                    recvEpochSec = readU32(frame, 1),
                    pathLen = frame[5].toInt() and 0xFF,
                    path = frame.copyOfRange(6, frame.size),
                )
                Resp.STATS -> decodeStats(frame)
                Push.CONTROL_DATA -> MeshFrame.ControlData(
                    snr = frame[1].toInt() / 4.0f,
                    rssi = frame[2].toInt(),
                    pathLen = frame[3].toInt() and 0xFF,
                    payload = frame.copyOfRange(4, frame.size),
                )
                Push.TRACE_DATA -> decodeTraceData(frame)
                Push.MSG_WAITING -> MeshFrame.MessagesWaiting
                Push.SEND_CONFIRMED -> MeshFrame.SendConfirmed(frame.copyOfRange(1, minOf(7, frame.size)))
                Push.LOG_RX_DATA -> MeshFrame.LogRxData(
                    // Both signed: snr is x4, rssi is already in dBm.
                    snr = frame[1].toInt() / 4.0f,
                    rssi = frame[2].toInt(),
                    raw = frame.copyOfRange(3, frame.size),
                )
                else -> MeshFrame.Unhandled(code, frame)
            }
        } catch (e: IndexOutOfBoundsException) {
            MeshFrame.Malformed(code, "frame too short: ${frame.size} bytes")
        }
    }

    private fun decodeContact(frame: ByteArray): MeshFrame {
        if (frame.size < CONTACT_FRAME_SIZE) {
            return MeshFrame.Malformed(Resp.CONTACT, "contact frame is ${frame.size} bytes")
        }
        var i = 1
        val pubKey = frame.copyOfRange(i, i + PUB_KEY_SIZE); i += PUB_KEY_SIZE
        val type = frame[i++].toInt() and 0xFF
        val flags = frame[i++].toInt() and 0xFF
        // Signed: the firmware stores -1 when no path is known, meaning "flood".
        val outPathLen = frame[i++].toInt()
        // Kept verbatim: the UI ignores them, but a config export round-trips them.
        val outPath = frame.copyOfRange(i, i + MAX_PATH_SIZE); i += MAX_PATH_SIZE
        val name = cString(frame, i, 32); i += 32
        val lastAdvert = readU32(frame, i); i += 4
        val lat = readI32(frame, i); i += 4
        val lon = readI32(frame, i); i += 4
        val lastMod = readU32(frame, i)
        return MeshFrame.Contact(
            publicKey = pubKey,
            type = type,
            flags = flags,
            outPathLen = outPathLen,
            outPath = outPath,
            name = name,
            lastAdvertEpochSec = lastAdvert,
            latE6 = lat,
            lonE6 = lon,
            lastModEpochSec = lastMod,
        )
    }

    private fun decodeSelfInfo(frame: ByteArray): MeshFrame {
        if (frame.size < 58) return MeshFrame.Malformed(Resp.SELF_INFO, "self info too short")
        return MeshFrame.SelfInfo(
            advType = frame[1].toInt() and 0xFF,
            // tx_power_dbm is int8_t in the firmware and can legitimately be negative.
            txPower = frame[2].toInt(),
            maxTxPower = frame[3].toInt(),
            publicKey = frame.copyOfRange(4, 36),
            latE6 = readI32(frame, 36),
            lonE6 = readI32(frame, 40),
            multiAcks = frame[44].toInt() and 0xFF,
            advertLocPolicy = frame[45].toInt() and 0xFF,
            telemetryMode = frame[46].toInt() and 0xFF,
            manualAddContacts = frame[47].toInt() != 0,
            // Firmware writes freq as MHz*1000 and bandwidth as kHz*1000.
            radioFreqKhz = readU32(frame, 48),
            radioBandwidthHz = readU32(frame, 52),
            spreadingFactor = frame[56].toInt() and 0xFF,
            codingRate = frame[57].toInt() and 0xFF,
            name = if (frame.size > 58) String(frame, 58, frame.size - 58, Charsets.UTF_8).trimEnd(' ') else "",
        )
    }

    private fun decodeDeviceInfo(frame: ByteArray): MeshFrame {
        val fwVer = frame[1].toInt() and 0xFF
        if (fwVer < 3 || frame.size < 80) {
            return MeshFrame.DeviceInfo(fwVer, 0, 0, 0, "", "", "", false, 0)
        }
        return MeshFrame.DeviceInfo(
            firmwareVersion = fwVer,
            maxContacts = (frame[2].toInt() and 0xFF) * 2,
            maxChannels = frame[3].toInt() and 0xFF,
            blePin = readU32(frame, 4),
            firmwareBuild = cString(frame, 8, 12),
            model = cString(frame, 20, 40),
            version = cString(frame, 60, 20),
            // v9+ only; absent on older firmware, and we must echo it back on SET_RADIO_PARAMS.
            clientRepeat = frame.size > 80 && frame[80].toInt() != 0,
            // v10+ only.
            pathHashMode = if (frame.size > 81) frame[81].toInt() and 0xFF else 0,
        )
    }

    private fun decodeContactMessage(frame: ByteArray, v3: Boolean): MeshFrame {
        var i = 1
        var snr: Float? = null
        if (v3) {
            snr = (frame[i].toInt()) / 4.0f
            i += 3 // snr + 2 reserved
        }
        val prefix = frame.copyOfRange(i, i + 6); i += 6
        val pathLen = frame[i++].toInt() and 0xFF
        val txtType = frame[i++].toInt() and 0xFF
        val timestamp = readU32(frame, i); i += 4
        if (txtType == TxtType.SIGNED_PLAIN) i += 4 // skip signature
        val text = String(frame, i, frame.size - i, Charsets.UTF_8).trimEnd(' ')
        return MeshFrame.ContactMessage(prefix, pathLen, txtType, timestamp, text, snr)
    }

    private fun decodeChannelMessage(frame: ByteArray, v3: Boolean): MeshFrame {
        var i = 1
        var snr: Float? = null
        if (v3) {
            snr = (frame[i].toInt()) / 4.0f
            i += 3
        }
        val channelIdx = frame[i++].toInt() and 0xFF
        val pathLen = frame[i++].toInt() and 0xFF
        val txtType = frame[i++].toInt() and 0xFF
        val timestamp = readU32(frame, i); i += 4
        val text = String(frame, i, frame.size - i, Charsets.UTF_8).trimEnd(' ')
        return MeshFrame.ChannelMessage(channelIdx, pathLen, txtType, timestamp, text, snr)
    }

    /**
     * Reverses `Dispatcher::tryParsePacket` on the bytes a LOG_RX_DATA push carries.
     * Returns null for anything the firmware itself would reject, so a corrupt or
     * future-format packet is dropped rather than mis-attributed to a repeater.
     */
    fun parseRawPacket(raw: ByteArray): RawPacket? {
        if (raw.isEmpty()) return null
        var i = 0
        val header = raw[i++].toInt() and 0xFF
        if (PacketHeader.payloadVersion(header) != 0) return null

        if (PacketHeader.hasTransportCodes(header)) {
            if (i + 4 > raw.size) return null
            i += 4
        }

        if (i >= raw.size) return null
        val pathLenByte = raw[i++].toInt() and 0xFF
        // Mode 3 is reserved. It is also what a 0xFF "direct" marker would look like,
        // and a raw packet never carries that marker.
        if ((pathLenByte shr 6) == 3) return null

        val hashSize = PathInfo.hashSizeBytes(pathLenByte)
        val pathByteLen = PathInfo.hops(pathLenByte) * hashSize
        if (pathByteLen > MAX_PATH_SIZE || i + pathByteLen > raw.size) return null

        val path = raw.copyOfRange(i, i + pathByteLen); i += pathByteLen
        return RawPacket(
            header = header,
            pathLenByte = pathLenByte,
            path = path,
            payload = raw.copyOfRange(i, raw.size),
        )
    }

    /** Only the radio block is decoded; the core and packet blocks have no screen. */
    private fun decodeStats(frame: ByteArray): MeshFrame {
        if (frame.size < 2) return MeshFrame.Malformed(Resp.STATS, "stats frame too short")
        val type = frame[1].toInt() and 0xFF
        if (type != Stats.RADIO) return MeshFrame.Unhandled(Resp.STATS, frame)
        if (frame.size < 14) return MeshFrame.Malformed(Resp.STATS, "radio stats too short")
        return MeshFrame.RadioStats(
            // int16: a noise floor is a negative dBm figure.
            noiseFloorDbm = readI16(frame, 2),
            lastRssi = frame[4].toInt(),
            lastSnr = frame[5].toInt() / 4.0f,
            txAirTimeSec = readU32(frame, 6),
            rxAirTimeSec = readU32(frame, 10),
        )
    }

    /**
     * `[0]=0x89, [1]=reserved, [2]=path_len, [3]=flags, [4..7]=tag, [8..11]=auth,
     * then `path_len` hash bytes, then one SNR per hop, then the SNR at this node.`
     *
     * `path_len` counts *bytes* of hashes, so the hop count is `path_len >> shift`.
     */
    private fun decodeTraceData(frame: ByteArray): MeshFrame {
        if (frame.size < 12) return MeshFrame.Malformed(Push.TRACE_DATA, "trace frame too short")
        val pathLen = frame[2].toInt() and 0xFF
        val flags = frame[3].toInt() and 0xFF
        val shift = flags and 0x03
        val hops = pathLen shr shift

        if (frame.size < 12 + pathLen + hops + 1) {
            return MeshFrame.Malformed(Push.TRACE_DATA, "trace frame truncated")
        }
        val hashes = frame.copyOfRange(12, 12 + pathLen)
        val snrs = frame.copyOfRange(12 + pathLen, 12 + pathLen + hops)
        return MeshFrame.TraceData(
            tag = readU32(frame, 4).toInt(),
            authCode = readU32(frame, 8).toInt(),
            hashSizeBytes = 1 shl shift,
            pathHashes = hashes,
            // Each hop's SNR, in the order the packet travelled.
            hopSnrs = snrs.map { it.toInt() / 4.0f },
            finalSnr = frame[12 + pathLen + hops].toInt() / 4.0f,
        )
    }

    /** The node sends `name:value` pairs joined by commas, with no trailing null. */
    private fun parseCustomVars(frame: ByteArray): Map<String, String> {
        if (frame.size <= 1) return emptyMap()
        var end = frame.size
        while (end > 1 && frame[end - 1] == 0.toByte()) end--
        val body = String(frame, 1, end - 1, Charsets.UTF_8)
        return body.split(',')
            .mapNotNull { pair ->
                val sep = pair.indexOf(':')
                if (sep <= 0) null else pair.substring(0, sep) to pair.substring(sep + 1)
            }
            .toMap()
    }

    // ---------------- byte helpers ----------------

    private fun buffer(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun readU32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun readI32(b: ByteArray, off: Int): Int = readU32(b, off).toInt()

    private fun readU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readI16(b: ByteArray, off: Int): Int = readU16(b, off).toShort().toInt()

    /** Reads a fixed-width, null-padded UTF-8 field. */
    private fun cString(b: ByteArray, off: Int, maxLen: Int): String {
        var end = off
        val limit = minOf(off + maxLen, b.size)
        while (end < limit && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    /** Cuts at a codepoint boundary so we never emit half a multi-byte char. */
    private fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size <= maxBytes) return raw
        var end = maxBytes
        while (end > 0 && (raw[end].toInt() and 0xC0) == 0x80) end--
        return raw.copyOfRange(0, end)
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}

/**
 * A LoRa packet exactly as it came off the air, as reported by LOG_RX_DATA.
 *
 * Every repeater that forwards a flood packet appends its own key hash to [path],
 * so the *last* entry is whoever transmitted the copy we just heard.
 */
data class RawPacket(
    val header: Int,
    val pathLenByte: Int,
    /** `hops * hashSize` bytes; empty when we heard the originator directly. */
    val path: ByteArray,
    val payload: ByteArray,
) {
    val payloadType: Int get() = MeshCoreProtocol.PacketHeader.payloadType(header)
    val isFlood: Boolean get() = MeshCoreProtocol.PacketHeader.isFlood(header)
    val hashSizeBytes: Int get() = MeshCoreProtocol.PathInfo.hashSizeBytes(pathLenByte)
    val hops: Int get() = MeshCoreProtocol.PathInfo.hops(pathLenByte)

    /** The hash of the node that transmitted this copy, or null if we heard the source. */
    fun lastPathHash(): ByteArray? {
        if (path.isEmpty() || path.size < hashSizeBytes) return null
        return path.copyOfRange(path.size - hashSizeBytes, path.size)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawPacket) return false
        return header == other.header &&
            pathLenByte == other.pathLenByte &&
            path.contentEquals(other.path) &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = header
        result = 31 * result + pathLenByte
        result = 31 * result + path.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/** One decoded companion-protocol frame. */
sealed interface MeshFrame {
    val code: Int

    data class Ok(val value: Long?) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.OK
    }

    data class Error(val errorCode: Int) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.ERR
        val message: String get() = MeshCoreProtocol.errorMessage(errorCode)
    }

    data class ContactsStart(val totalContacts: Long) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.CONTACTS_START
    }

    data class Contact(
        val publicKey: ByteArray,
        val type: Int,
        val flags: Int,
        val outPathLen: Int,
        /** Always MAX_PATH_SIZE bytes; only the first [outPathLen] are meaningful. */
        val outPath: ByteArray,
        val name: String,
        val lastAdvertEpochSec: Long,
        val latE6: Int,
        val lonE6: Int,
        val lastModEpochSec: Long,
    ) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.CONTACT

        /** The 6-byte prefix is what messages and send commands address a node by. */
        val keyPrefix: ByteArray get() = publicKey.copyOfRange(0, 6)

        // Compares every field, not just the key. A StateFlow conflates values it
        // considers equal, so a narrower equals() would hide a contact whose name,
        // route or last-advert time changed.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Contact) return false
            return publicKey.contentEquals(other.publicKey) &&
                type == other.type &&
                flags == other.flags &&
                outPathLen == other.outPathLen &&
                outPath.contentEquals(other.outPath) &&
                name == other.name &&
                lastAdvertEpochSec == other.lastAdvertEpochSec &&
                latE6 == other.latE6 &&
                lonE6 == other.lonE6 &&
                lastModEpochSec == other.lastModEpochSec
        }

        override fun hashCode(): Int {
            var result = publicKey.contentHashCode()
            result = 31 * result + type
            result = 31 * result + flags
            result = 31 * result + outPathLen
            result = 31 * result + outPath.contentHashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + lastAdvertEpochSec.hashCode()
            result = 31 * result + latE6
            result = 31 * result + lonE6
            result = 31 * result + lastModEpochSec.hashCode()
            return result
        }
    }

    data class EndOfContacts(val mostRecentLastMod: Long) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.END_OF_CONTACTS
    }

    data class SelfInfo(
        val advType: Int,
        val txPower: Int,
        val maxTxPower: Int,
        val publicKey: ByteArray,
        val latE6: Int,
        val lonE6: Int,
        val multiAcks: Int,
        val advertLocPolicy: Int,
        val telemetryMode: Int,
        val manualAddContacts: Boolean,
        /** Frequency in kilohertz; divide by 1000 for MHz. */
        val radioFreqKhz: Long,
        /** Bandwidth in hertz; divide by 1000 for kHz. */
        val radioBandwidthHz: Long,
        val spreadingFactor: Int,
        val codingRate: Int,
        val name: String,
    ) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.SELF_INFO

        /** Anything other than NONE means the advert carries lat/lon. */
        val sharesLocation: Boolean get() = advertLocPolicy != MeshCoreProtocol.AdvertLoc.NONE

        /** `multi_acks` holds the *extra* acks; the UI talks in totals. */
        val totalDirectAcks: Int get() = MeshCoreProtocol.MultiAcks.totalOf(multiAcks)

        /** The board's ceiling, or the safe fallback when the node reports nonsense. */
        val txPowerCeiling: Int
            get() = if (maxTxPower in 1..30) maxTxPower
            else MeshCoreProtocol.RadioLimits.TX_POWER_MAX_FALLBACK

        // Must compare every field. The settings screen re-reads SELF_INFO after a
        // save and diffs the user's next edit against it; if a value that changed
        // only in, say, frequency compared equal, the StateFlow would drop the
        // update and the screen would keep diffing against stale settings.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SelfInfo) return false
            return advType == other.advType &&
                txPower == other.txPower &&
                maxTxPower == other.maxTxPower &&
                publicKey.contentEquals(other.publicKey) &&
                latE6 == other.latE6 &&
                lonE6 == other.lonE6 &&
                multiAcks == other.multiAcks &&
                advertLocPolicy == other.advertLocPolicy &&
                telemetryMode == other.telemetryMode &&
                manualAddContacts == other.manualAddContacts &&
                radioFreqKhz == other.radioFreqKhz &&
                radioBandwidthHz == other.radioBandwidthHz &&
                spreadingFactor == other.spreadingFactor &&
                codingRate == other.codingRate &&
                name == other.name
        }

        override fun hashCode(): Int {
            var result = advType
            result = 31 * result + txPower
            result = 31 * result + maxTxPower
            result = 31 * result + publicKey.contentHashCode()
            result = 31 * result + latE6
            result = 31 * result + lonE6
            result = 31 * result + multiAcks
            result = 31 * result + advertLocPolicy
            result = 31 * result + telemetryMode
            result = 31 * result + manualAddContacts.hashCode()
            result = 31 * result + radioFreqKhz.hashCode()
            result = 31 * result + radioBandwidthHz.hashCode()
            result = 31 * result + spreadingFactor
            result = 31 * result + codingRate
            result = 31 * result + name.hashCode()
            return result
        }
    }

    data class DeviceInfo(
        val firmwareVersion: Int,
        val maxContacts: Int,
        val maxChannels: Int,
        val blePin: Long,
        val firmwareBuild: String,
        val model: String,
        val version: String,
        val clientRepeat: Boolean,
        val pathHashMode: Int,
    ) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.DEVICE_INFO
    }

    data class Sent(
        val flood: Boolean,
        val expectedAck: Long,
        val suggestedTimeoutMs: Long,
    ) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.SENT
    }

    data object NoMoreMessages : MeshFrame {
        override val code = MeshCoreProtocol.Resp.NO_MORE_MESSAGES
    }

    data class Battery(
        val millivolts: Int,
        val usedKb: Long,
        val totalKb: Long,
        /** Whether the node is on external power. Null if its firmware doesn't report it. */
        val charging: Boolean? = null,
    ) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.BATT_AND_STORAGE

        /** A node that never reported storage sends totalKb = 0; don't divide by it. */
        fun usedFraction(): Float =
            if (totalKb <= 0L) 0f else (usedKb.toFloat() / totalKb.toFloat()).coerceIn(0f, 1f)

        fun usedPercent(): Int = (usedFraction() * 100).toInt()

        /**
         * The same linear curve the node's own display uses
         * (`UITask.cpp: battPercent`), so the app and the screen never disagree.
         */
        fun batteryPercent(): Int = MeshCoreProtocol.Battery.percentOf(millivolts)

        /** e.g. "3.58" volts. */
        fun volts(): Double = millivolts / 1000.0
    }

    data class ChannelInfo(val index: Int, val name: String, val secret: ByteArray) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.CHANNEL_INFO

        val isEmpty: Boolean get() = name.isBlank() && secret.all { it == 0.toByte() }

        // Includes the secret: rotating a channel key changes nothing else, and a
        // conflated update would leave the app using the old one.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ChannelInfo) return false
            return index == other.index && name == other.name && secret.contentEquals(other.secret)
        }

        override fun hashCode(): Int {
            var result = index
            result = 31 * result + name.hashCode()
            result = 31 * result + secret.contentHashCode()
            return result
        }
    }

    data class ContactMessage(
        val keyPrefix: ByteArray,
        val pathLen: Int,
        val txtType: Int,
        val timestampEpochSec: Long,
        val text: String,
        val snr: Float?,
    ) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.CONTACT_MSG_RECV

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ContactMessage) return false
            return keyPrefix.contentEquals(other.keyPrefix) &&
                pathLen == other.pathLen &&
                txtType == other.txtType &&
                timestampEpochSec == other.timestampEpochSec &&
                text == other.text &&
                snr == other.snr
        }

        override fun hashCode(): Int {
            var result = keyPrefix.contentHashCode()
            result = 31 * result + pathLen
            result = 31 * result + txtType
            result = 31 * result + timestampEpochSec.hashCode()
            result = 31 * result + text.hashCode()
            result = 31 * result + (snr?.hashCode() ?: 0)
            return result
        }
    }

    data class ChannelMessage(
        val channelIndex: Int,
        val pathLen: Int,
        val txtType: Int,
        val timestampEpochSec: Long,
        val text: String,
        val snr: Float?,
    ) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.CHANNEL_MSG_RECV
    }

    data class CurrentTime(val epochSec: Long) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.CURR_TIME
    }

    /** The firmware was built without this feature (e.g. private key export). */
    data object Disabled : MeshFrame {
        override val code = MeshCoreProtocol.Resp.DISABLED
    }

    /** The node's 64-byte identity keypair. Treat as a secret. */
    data class PrivateKey(val keyPair: ByteArray) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.PRIVATE_KEY

        override fun equals(other: Any?): Boolean =
            other is PrivateKey && keyPair.contentEquals(other.keyPair)

        override fun hashCode(): Int = keyPair.contentHashCode()
    }

    data class AutoAddConfig(val config: Int, val maxHops: Int) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.AUTOADD_CONFIG

        fun has(flag: Int): Boolean = (config and flag) != 0
    }

    data class CustomVars(val values: Map<String, String>) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.CUSTOM_VARS

        /** Absent entirely on a board built without ENV_INCLUDE_GPS. */
        val gpsEnabled: Boolean? get() = values["gps"]?.let { it == "1" }
    }

    data object MessagesWaiting : MeshFrame {
        override val code = MeshCoreProtocol.Push.MSG_WAITING
    }

    /**
     * Every packet the radio received, whether or not it was addressed to us.
     * `Dispatcher::checkRecv` emits one of these before it even tries to parse.
     */
    data class LogRxData(val snr: Float, val rssi: Int, val raw: ByteArray) : MeshFrame {
        override val code = MeshCoreProtocol.Push.LOG_RX_DATA

        fun packet(): RawPacket? = MeshCoreProtocol.parseRawPacket(raw)

        override fun equals(other: Any?): Boolean =
            other is LogRxData && snr == other.snr && rssi == other.rssi &&
                raw.contentEquals(other.raw)

        override fun hashCode(): Int =
            31 * (31 * snr.hashCode() + rssi) + raw.contentHashCode()
    }

    /**
     * When this node last heard an advert from a contact, and along what path.
     * Only the 16 most recently heard nodes are in the firmware's table.
     */
    data class AdvertPath(
        val recvEpochSec: Long,
        /** Encoded: hash size in the top two bits, hop count in the low six. */
        val pathLen: Int,
        val path: ByteArray,
    ) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.ADVERT_PATH

        val hops: Int get() = MeshCoreProtocol.PathInfo.hops(pathLen)
        val hashSizeBytes: Int get() = MeshCoreProtocol.PathInfo.hashSizeBytes(pathLen)

        override fun equals(other: Any?): Boolean =
            other is AdvertPath && recvEpochSec == other.recvEpochSec &&
                pathLen == other.pathLen && path.contentEquals(other.path)

        override fun hashCode(): Int =
            31 * (31 * recvEpochSec.hashCode() + pathLen) + path.contentHashCode()
    }

    /** `CMD_GET_STATS` with `STATS_TYPE_RADIO`. */
    data class RadioStats(
        /** Negative dBm. The quieter (more negative) the better. */
        val noiseFloorDbm: Int,
        val lastRssi: Int,
        val lastSnr: Float,
        val txAirTimeSec: Long,
        val rxAirTimeSec: Long,
    ) : MeshFrame {
        override val code = MeshCoreProtocol.Resp.STATS
    }

    /** A zero-hop control packet the radio heard. Node-discovery replies arrive here. */
    data class ControlData(
        val snr: Float,
        val rssi: Int,
        val pathLen: Int,
        val payload: ByteArray,
    ) : MeshFrame {
        override val code = MeshCoreProtocol.Push.CONTROL_DATA

        override fun equals(other: Any?): Boolean =
            other is ControlData && snr == other.snr && rssi == other.rssi &&
                pathLen == other.pathLen && payload.contentEquals(other.payload)

        override fun hashCode(): Int =
            31 * (31 * (31 * snr.hashCode() + rssi) + pathLen) + payload.contentHashCode()
    }

    /** The result of a path trace: who forwarded it, and how well each hop heard it. */
    data class TraceData(
        val tag: Int,
        val authCode: Int,
        val hashSizeBytes: Int,
        /** `hops * hashSizeBytes` bytes. */
        val pathHashes: ByteArray,
        val hopSnrs: List<Float>,
        /** The SNR our own node heard the returning trace at. */
        val finalSnr: Float,
    ) : MeshFrame {
        override val code = MeshCoreProtocol.Push.TRACE_DATA

        val hops: Int get() = hopSnrs.size

        /** The hash of the n-th repeater along the path. */
        fun hashAt(index: Int): ByteArray =
            pathHashes.copyOfRange(index * hashSizeBytes, (index + 1) * hashSizeBytes)

        override fun equals(other: Any?): Boolean =
            other is TraceData && tag == other.tag && authCode == other.authCode &&
                hashSizeBytes == other.hashSizeBytes && pathHashes.contentEquals(other.pathHashes) &&
                hopSnrs == other.hopSnrs && finalSnr == other.finalSnr

        override fun hashCode(): Int {
            var result = tag
            result = 31 * result + authCode
            result = 31 * result + hashSizeBytes
            result = 31 * result + pathHashes.contentHashCode()
            result = 31 * result + hopSnrs.hashCode()
            result = 31 * result + finalSnr.hashCode()
            return result
        }
    }

    data class SendConfirmed(val ackCode: ByteArray) : MeshFrame {
        override val code = MeshCoreProtocol.Push.SEND_CONFIRMED

        override fun equals(other: Any?): Boolean =
            other is SendConfirmed && ackCode.contentEquals(other.ackCode)

        override fun hashCode(): Int = ackCode.contentHashCode()
    }

    /** A frame we recognise the code of but do not act on (adverts, logs, telemetry). */
    data class Unhandled(override val code: Int, val raw: ByteArray) : MeshFrame {
        override fun equals(other: Any?): Boolean =
            other is Unhandled && code == other.code && raw.contentEquals(other.raw)

        override fun hashCode(): Int = 31 * code + raw.contentHashCode()
    }

    data class Malformed(override val code: Int, val reason: String) : MeshFrame
}
