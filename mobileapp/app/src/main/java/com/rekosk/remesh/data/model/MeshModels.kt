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
    val isBlocked: Boolean = false,
    /** Only set for GROUP rows, which show member names instead of "last seen". */
    val memberSummary: String? = null,
    val unreadCount: Int = 0,
)

enum class ChannelKind { PUBLIC, PRIVATE, HASHTAG }

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
}

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
)
