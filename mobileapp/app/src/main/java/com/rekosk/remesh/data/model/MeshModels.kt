package com.rekosk.remesh.data.model

/** How a packet reaches a node: either flooded, or along a known N-hop route. */
sealed interface Route {
    data object Flood : Route
    data class Hops(val count: Int) : Route
}

enum class NodeType { CHAT, REPEATER, ROOM, SENSOR, GROUP }

data class Contact(
    val id: String,
    val name: String,
    val type: NodeType,
    val route: Route,
    val lastSeenEpochMs: Long?,
    val hasLocation: Boolean = false,
    /** Last advertised position in 1e-6 degrees; 0/0 means unknown. Persisted so the
     *  map can place this node even when its companion radio is offline. */
    val latE6: Int = 0,
    val lonE6: Int = 0,
    val isBlocked: Boolean = false,
    /**
     * App-side only: favourites sort to the top of the list and are never forgotten
     * by ReMesh. The firmware has no equivalent flag.
     */
    val isFavorite: Boolean = false,
    /** Only set for GROUP rows, which show member names instead of "last seen". */
    val memberSummary: String? = null,
    val unreadCount: Int = 0,
)

enum class ChannelKind { PUBLIC, PRIVATE, HASHTAG }

/** Extra contact fields the list model drops, gathered for the detail screen. */
data class ContactExtras(
    val publicKeyHex: String,
    val latitude: Double?,
    val longitude: Double?,
    val distanceKm: Double?,
    val lastAdvertEpochMs: Long?,
    /** Current outgoing path bytes as hex; "" is a zero-hop direct route, null is flood. */
    val outPathHex: String?,
    val pathHashSizeBytes: Int,
)

/**
 * A row in the messaging list, which mixes channels and any direct-message threads
 * that have messages. [isChannel] picks the icon and the destination the row opens.
 */
data class ConversationSummary(
    val id: String,
    val title: String,
    val isChannel: Boolean,
    val channelKind: ChannelKind? = null,
    /** For a DM row, the other node's type, so it gets the right avatar. */
    val contactType: NodeType? = null,
    val unreadCount: Int = 0,
    /** Newest message time, for ordering the list. */
    val lastActivityEpochMs: Long = 0,
)

data class Channel(
    val id: String,
    /** Firmware channel slot, 0..7. Needed to address a send. */
    val index: Int,
    val name: String,
    val kind: ChannelKind,
    val unreadCount: Int = 0,
)

/**
 * How far an outgoing message has got. The chat bubble draws one tick for [SENT]
 * and two for [CONFIRMED], nothing for [PENDING].
 */
enum class DeliveryState {
    /** Handed to the node; it has not answered yet. */
    PENDING,

    /** The node put it on the air. */
    SENT,

    /**
     * At least one repeater was overheard re-transmitting it. For a direct message
     * this will instead mean the recipient acked it.
     */
    CONFIRMED,

    /**
     * Composed while the node was offline. Persisted to disk and transmitted
     * automatically after the next successful handshake; deleting it first cancels it.
     */
    QUEUED,
}

/**
 * A user-placed point on the Signal-coverage map. Each has a palette colour and can be
 * enabled/disabled; its terrain signal coverage is computed on demand. Local-only (per node).
 */
data class CoveragePoint(
    val id: String,
    val label: String,
    val latE6: Int,
    val lonE6: Int,
    /** Index into the avatar palette (see `avatarColorByIndex`). */
    val colorIndex: Int,
    val enabled: Boolean = true,
    // Optional radio overrides; null = use the node's radio config / app defaults.
    val txPowerDbm: Double? = null,
    val freqMhz: Double? = null,
    /** Antenna height above ground, metres. */
    val antennaM: Double? = null,
    val rxSensitivityDbm: Double? = null,
)

/**
 * A repeater our own node overheard forwarding one of our messages.
 *
 * [hashHex] is the key prefix that repeater appended to the packet's path: one to
 * three bytes, depending on the mesh's path hash mode. It is not a full identity,
 * and several contacts can share a prefix -- which is why the UI resolves it into
 * a name separately rather than storing one here.
 */
data class HeardRepeat(
    val hashHex: String,
    /** Signal-to-noise ratio we heard *the repeater* at, in dB. Always a multiple of 0.25. */
    val snr: Float,
    val rssi: Int,
    val heardEpochMs: Long,
)

/**
 * One overheard copy of an *incoming* message: the ordered repeater hashes it
 * travelled through and how well our node heard that copy off the air.
 *
 * [pathHashesHex] is in path order -- the hop nearest the sender first, the hop
 * that handed the packet to us last -- and empty for a copy we heard directly.
 * Each entry is a 1-3 byte key prefix (like [HeardRepeat.hashHex]); the UI resolves
 * it into a repeater name separately. Reconstructed from PUSH_CODE_LOG_RX_DATA, so
 * it only exists while the app was connected as the message arrived.
 */
data class MessageRoute(
    val pathHashesHex: List<String>,
    /** SNR we heard this copy at, in dB. A multiple of 0.25. */
    val snr: Float,
    val rssi: Int,
)

/** A known contact a route hop resolved to: [contactId] opens its detail screen. */
data class RepeaterContactRef(val contactId: String, val name: String)

/**
 * A node placed on the map. Comes from our own [MeshFrame.SelfInfo] position or from
 * a contact's last advertised coordinates; nodes without a position are left off.
 *
 * [id] is the same conversation id the contact lists use, so tapping a marker can open
 * the node's chat -- except for [isSelf], whose id is a sentinel with no conversation.
 */
data class NodePosition(
    val id: String,
    val name: String,
    val type: NodeType,
    val latitude: Double,
    val longitude: Double,
    val isSelf: Boolean = false,
    /** When the node was last heard (its last advert), for the "· 2d" marker label. */
    val lastSeenEpochMs: Long? = null,
)

data class MeshMessage(
    val id: String,
    val author: String,
    val text: String,
    /** When the sender stamped it. This is the time the bubble shows. */
    val timestampEpochMs: Long,
    val isOutgoing: Boolean,
    /** When this app took delivery of it. Null for our own messages. */
    val receivedEpochMs: Long? = null,
    val hopCount: Int = 0,
    /** Bytes each repeater added to the path: 1, 2 or 3. Null when unknown. */
    val pathHashSizeBytes: Int? = null,
    /** True when the packet arrived along a known route instead of being flooded. */
    val isDirectRoute: Boolean = false,
    /** How well our node heard the incoming packet, in dB. */
    val snr: Float? = null,
    val deliveryState: DeliveryState = DeliveryState.PENDING,
    /** Outgoing only, in the order they were heard. */
    val heardRepeats: List<HeardRepeat> = emptyList(),
    /** Incoming only: one entry per overheard copy, its ordered repeater path. */
    val routes: List<MessageRoute> = emptyList(),
)
