package com.rekosk.remesh.data.model

/** One line of the live packet log: everything the radio heard, decoded or not. */
data class LoggedPacket(
    val receivedEpochMs: Long,
    val snr: Float,
    val rssi: Int,
    val sizeBytes: Int,
    /** `PAYLOAD_TYPE_*`, or null when the packet did not parse. */
    val payloadType: Int?,
    val hops: Int,
    val isFlood: Boolean,
    /** Who transmitted the copy we heard, when it came via a repeater. */
    val lastPathHash: String?,
)

/**
 * A node whose advert this node heard recently. Comes from the firmware's 16-entry
 * `advert_paths` table, so it is a window on the last few nodes, not on all of them.
 */
data class RecentAdvert(
    val name: String,
    val publicKey: ByteArray,
    val type: NodeType,
    val receivedEpochMs: Long,
    val hops: Int,
    val isDirect: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is RecentAdvert && publicKey.contentEquals(other.publicKey) &&
            receivedEpochMs == other.receivedEpochMs && hops == other.hops

    override fun hashCode(): Int =
        31 * (31 * publicKey.contentHashCode() + receivedEpochMs.hashCode()) + hops
}

/**
 * A node that answered a zero-hop discovery scan, so it is in direct radio range.
 *
 * The two SNRs are the two directions of the same link and can differ a lot: a
 * repeater on a hill hears a phone-side node far better than the other way round.
 */
data class NearbyNode(
    /** Null when the node is not in our contacts yet. */
    val name: String?,
    val publicKey: ByteArray,
    val type: NodeType,
    /** How well *we* heard *them*. */
    val inboundSnr: Float,
    /** How well *they* heard *us*, as reported in their reply. */
    val outboundSnr: Float,
    val rssi: Int,
) {
    val shortKey: String get() = publicKey.take(2).joinToString("") { "%02X".format(it) }

    override fun equals(other: Any?): Boolean =
        other is NearbyNode && publicKey.contentEquals(other.publicKey)

    override fun hashCode(): Int = publicKey.contentHashCode()
}

/**
 * Read-only facts about a node seen in the Discover screens, before it is a contact.
 * Aggregates whatever a recent advert and/or a nearby scan reply told us about the key,
 * so its contact menu can show real signal/route info without adding it first.
 */
data class DiscoveredNodeInfo(
    val name: String?,
    val type: NodeType,
    /** Hop count from a recent advert; null when only a nearby scan reply is known. */
    val hops: Int?,
    val isDirect: Boolean?,
    /** Nearby-scan link quality (how well we heard them / they heard us). */
    val inboundSnr: Float?,
    val outboundSnr: Float?,
    val rssi: Int?,
    val lastHeardEpochMs: Long?,
)

/** One hop of a completed path trace. */
data class TraceHop(
    val hashHex: String,
    /** Set when exactly one known contact starts with this hash. */
    val name: String?,
    val snr: Float,
    /**
     * Every known contact sharing this hash prefix. More than one means the hop is
     * ambiguous -- the UI shows "Duplicate (hash)" and lets the user pick.
     */
    val candidates: List<String> = emptyList(),
)
