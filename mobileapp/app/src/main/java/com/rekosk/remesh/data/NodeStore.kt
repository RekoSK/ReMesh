package com.rekosk.remesh.data

import android.content.Context
import com.rekosk.remesh.data.model.Channel
import com.rekosk.remesh.data.model.ChannelKind
import com.rekosk.remesh.data.model.Contact
import com.rekosk.remesh.data.model.DeliveryState
import com.rekosk.remesh.data.model.HeardRepeat
import com.rekosk.remesh.data.model.MeshMessage
import com.rekosk.remesh.data.model.MessageRoute
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.data.model.RecentAdvert
import com.rekosk.remesh.data.model.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk state, one block per node, keyed by the node's public identity key.
 *
 * The domain models the UI works in are deliberately not `@Serializable`: they carry
 * `ByteArray`s and sealed types that would pin the wire format to internal choices.
 * These DTOs are the stable format, and [toDomain]/[fromDomain] bridge the two, so a
 * later refactor of a model cannot silently invalidate everything already saved.
 */
@Serializable
data class NodeStoreFile(val nodes: List<PersistedNode> = emptyList())

/** Everything ReMesh remembers about one node, whether or not it is in range now. */
@Serializable
data class PersistedNode(
    /** The node's 64-hex public identity key. This is the storage block's identity. */
    val key: String,
    val name: String,
    val advType: Int = 0,
    /** Last BLE address we reached it at, so the scan list can match it back up. */
    val address: String? = null,
    val lastConnected: Long = 0,
    /** This node's own last known position in 1e-6 degrees, so it maps offline. 0/0 = unknown. */
    val selfLatE6: Int = 0,
    val selfLonE6: Int = 0,
    val contacts: List<PersistedContact> = emptyList(),
    val channels: List<PersistedChannel> = emptyList(),
    val adverts: List<PersistedAdvert> = emptyList(),
    /** Conversation id -> its messages. Same ids the live repository uses. */
    val messages: Map<String, List<PersistedMessage>> = emptyMap(),
    /** Conversation id -> epoch-ms it was last read, for unread counts. */
    val readMarks: Map<String, Long> = emptyMap(),
    /** Location updates made while offline, waiting to be pushed on the next handshake. */
    val pendingLocations: List<PersistedPendingLocation> = emptyList(),
)

/** A queued location update: [target] is "self" or a contact id; coords in 1e-6 degrees. */
@Serializable
data class PersistedPendingLocation(
    val target: String,
    val latE6: Int,
    val lonE6: Int,
)

@Serializable
data class PersistedContact(
    val id: String,
    val name: String,
    val type: String = NodeType.CHAT.name,
    /** -1 floods; 0 or more is a known route of that many hops. */
    val routeHops: Int = -1,
    val lastSeen: Long? = null,
    val hasLocation: Boolean = false,
    /** Last advertised position in 1e-6 degrees, so the map works offline. 0/0 = unknown. */
    val latE6: Int = 0,
    val lonE6: Int = 0,
    val isBlocked: Boolean = false,
    val isFavorite: Boolean = false,
)

@Serializable
data class PersistedChannel(
    val id: String,
    val index: Int,
    val name: String,
    val kind: String = ChannelKind.PRIVATE.name,
)

@Serializable
data class PersistedAdvert(
    val name: String,
    /** Hex of the full public key, so it survives a round trip to disk. */
    val key: String,
    val type: String = NodeType.CHAT.name,
    val received: Long,
    val hops: Int,
    val isDirect: Boolean,
)

@Serializable
data class PersistedMessage(
    val id: String,
    val author: String,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val received: Long? = null,
    val hopCount: Int = 0,
    val pathHashSizeBytes: Int? = null,
    val isDirectRoute: Boolean = false,
    val snr: Float? = null,
    val deliveryState: String = DeliveryState.PENDING.name,
    val heardRepeats: List<PersistedHeardRepeat> = emptyList(),
    val routes: List<PersistedMessageRoute> = emptyList(),
)

@Serializable
data class PersistedHeardRepeat(
    val hashHex: String,
    val snr: Float,
    val rssi: Int,
    val heardEpochMs: Long,
)

@Serializable
data class PersistedMessageRoute(
    val pathHashesHex: List<String>,
    val snr: Float,
    val rssi: Int,
)

/** A saved node as the connect screen lists it, with no message history loaded. */
data class SavedNodeSummary(
    val key: String,
    val name: String,
    val advType: Int,
    val address: String?,
    val lastConnectedEpochMs: Long,
)

// ---------------- domain <-> persisted ----------------

private fun enumOrDefault(name: String): NodeType =
    runCatching { NodeType.valueOf(name) }.getOrDefault(NodeType.CHAT)

fun PersistedContact.toDomain(): Contact = Contact(
    id = id,
    name = name,
    type = enumOrDefault(type),
    route = if (routeHops < 0) Route.Flood else Route.Hops(routeHops),
    lastSeenEpochMs = lastSeen,
    hasLocation = hasLocation,
    latE6 = latE6,
    lonE6 = lonE6,
    isBlocked = isBlocked,
    isFavorite = isFavorite,
)

fun Contact.toPersisted(): PersistedContact = PersistedContact(
    id = id,
    name = name,
    type = type.name,
    routeHops = when (val r = route) {
        is Route.Hops -> r.count
        Route.Flood -> -1
    },
    lastSeen = lastSeenEpochMs,
    hasLocation = hasLocation,
    latE6 = latE6,
    lonE6 = lonE6,
    isBlocked = isBlocked,
    isFavorite = isFavorite,
)

fun PersistedChannel.toDomain(): Channel = Channel(
    id = id,
    index = index,
    name = name,
    kind = runCatching { ChannelKind.valueOf(kind) }.getOrDefault(ChannelKind.PRIVATE),
)

fun Channel.toPersisted(): PersistedChannel =
    PersistedChannel(id = id, index = index, name = name, kind = kind.name)

fun PersistedAdvert.toDomain(): RecentAdvert = RecentAdvert(
    name = name,
    publicKey = hexToBytes(key),
    type = enumOrDefault(type),
    receivedEpochMs = received,
    hops = hops,
    isDirect = isDirect,
)

fun RecentAdvert.toPersisted(): PersistedAdvert = PersistedAdvert(
    name = name,
    key = publicKey.joinToString("") { "%02x".format(it) },
    type = type.name,
    received = receivedEpochMs,
    hops = hops,
    isDirect = isDirect,
)

fun PersistedMessage.toDomain(): MeshMessage = MeshMessage(
    id = id,
    author = author,
    text = text,
    timestampEpochMs = timestamp,
    isOutgoing = isOutgoing,
    receivedEpochMs = received,
    hopCount = hopCount,
    pathHashSizeBytes = pathHashSizeBytes,
    isDirectRoute = isDirectRoute,
    snr = snr,
    deliveryState = runCatching { DeliveryState.valueOf(deliveryState) }
        .getOrDefault(DeliveryState.PENDING),
    heardRepeats = heardRepeats.map {
        HeardRepeat(it.hashHex, it.snr, it.rssi, it.heardEpochMs)
    },
    routes = routes.map {
        MessageRoute(it.pathHashesHex, it.snr, it.rssi)
    },
)

fun MeshMessage.toPersisted(): PersistedMessage = PersistedMessage(
    id = id,
    author = author,
    text = text,
    timestamp = timestampEpochMs,
    isOutgoing = isOutgoing,
    received = receivedEpochMs,
    hopCount = hopCount,
    pathHashSizeBytes = pathHashSizeBytes,
    isDirectRoute = isDirectRoute,
    snr = snr,
    deliveryState = deliveryState.name,
    heardRepeats = heardRepeats.map {
        PersistedHeardRepeat(it.hashHex, it.snr, it.rssi, it.heardEpochMs)
    },
    routes = routes.map {
        PersistedMessageRoute(it.pathHashesHex, it.snr, it.rssi)
    },
)

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/**
 * Reads and writes [NodeStoreFile] to a single JSON file in the app's private
 * storage. One file for every node keeps enumeration trivial and the write atomic;
 * the data is small (text messages and a handful of contacts), so rewriting the
 * whole file on each debounced save costs nothing worth optimising.
 */
class NodeStorage(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load(): List<PersistedNode> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) return@withContext emptyList()
            runCatching {
                json.decodeFromString(NodeStoreFile.serializer(), file.readText()).nodes
            }.getOrDefault(emptyList())
        }
    }

    suspend fun save(nodes: List<PersistedNode>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val text = json.encodeToString(NodeStoreFile.serializer(), NodeStoreFile(nodes))
            // Write-then-rename: a crash mid-write cannot truncate the real file.
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    private companion object {
        const val FILE_NAME = "nodes.json"
    }
}
