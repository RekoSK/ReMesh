package com.rekosk.remesh.data

import android.util.JsonReader
import android.util.JsonToken
import com.rekosk.remesh.data.model.NodeType
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/**
 * A node from the map.meshcore.io online map, pinned on the Node Map while the
 * "Online map" toggle is on. Never persisted; refetched on every toggle.
 */
data class OnlineMapNode(
    /** The node's full 64-hex identity key, straight from the map API. */
    val publicKeyHex: String,
    val name: String,
    val type: NodeType,
    val latitude: Double,
    val longitude: Double,
) {
    /** The conversation id this node would get as a contact, for de-duplication
     *  against the contact list (same scheme as [MeshRepository.contactConversationId]). */
    val contactId: String = MeshRepository.CONTACT_PREFIX + publicKeyHex.take(12)
}

/** The radio parameters an online node must share with ours to be shown. */
data class OnlineRadioConfig(
    val freqKhz: Int,
    val bandwidthHz: Int,
    val spreadingFactor: Int,
    val codingRate: Int,
)

/**
 * Client for the public MeshCore map (map.meshcore.io). The API has no server-side
 * filtering -- it always returns the full node list (tens of MB, ~50k nodes) -- so the
 * response is gzip'd on the wire and *stream*-parsed here: each node is read, tested
 * against our radio config and freshness window, and dropped immediately unless it
 * matches. Nothing near the full list is ever held in memory.
 */
object OnlineNodesApi {

    private const val NODES_URL = "https://map.meshcore.io/api/v1/nodes"

    /**
     * How recently the map must have heard from a node for us to call it "online".
     * Matches the map site's own "updated recently" freshness bucket (5 days).
     */
    private const val ONLINE_WINDOW_MS = 5L * 24 * 60 * 60 * 1000

    /**
     * Blocking fetch; call on an IO dispatcher. Returns every online node advertising
     * the same radio configuration as [config], with a usable position. Throws on
     * network / HTTP errors.
     */
    fun fetchMatching(config: OnlineRadioConfig): List<OnlineMapNode> {
        val connection = URL(NODES_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("Accept-Encoding", "gzip")
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("The map server answered HTTP $code")
            val body = connection.inputStream.let {
                if (connection.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(it) else it
            }
            JsonReader(InputStreamReader(body, Charsets.UTF_8)).use { reader ->
                val cutoffEpochMs = System.currentTimeMillis() - ONLINE_WINDOW_MS
                val matches = ArrayList<OnlineMapNode>()
                reader.beginArray()
                while (reader.hasNext()) {
                    readNode(reader, config, cutoffEpochMs)?.let(matches::add)
                }
                reader.endArray()
                return matches
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Reads one node object; null unless it is fresh, positioned, and on our config. */
    private fun readNode(
        reader: JsonReader,
        config: OnlineRadioConfig,
        cutoffEpochMs: Long,
    ): OnlineMapNode? {
        var publicKey: String? = null
        var type = 0
        var name: String? = null
        var lat: Double? = null
        var lon: Double? = null
        var lastAdvertMs: Long? = null
        var updatedMs: Long? = null
        var paramsMatch = false

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "public_key" -> publicKey = reader.nextStringOrNull()
                "type" -> type = reader.nextDoubleOrNull()?.toInt() ?: 0
                "adv_name" -> name = reader.nextStringOrNull()
                "adv_lat" -> lat = reader.nextDoubleOrNull()
                "adv_lon" -> lon = reader.nextDoubleOrNull()
                "last_advert" -> lastAdvertMs = reader.nextEpochMsOrNull()
                "updated_date" -> updatedMs = reader.nextEpochMsOrNull()
                "params" -> paramsMatch = readParamsMatch(reader, config)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (!paramsMatch) return null
        if (publicKey == null || publicKey.length != 64) return null
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return null
        if (lat == 0.0 && lon == 0.0) return null // the app-wide "position unknown" sentinel
        val freshness = updatedMs ?: lastAdvertMs ?: return null
        if (freshness < cutoffEpochMs) return null
        return OnlineMapNode(
            publicKeyHex = publicKey.lowercase(),
            name = name?.trim().orEmpty().ifBlank { "(unnamed)" },
            type = MeshRepository.nodeTypeOf(type),
            latitude = lat,
            longitude = lon,
        )
    }

    /** The API reports freq/bw in MHz/kHz floats; ours are kHz/Hz ints, so compare rounded. */
    private fun readParamsMatch(reader: JsonReader, config: OnlineRadioConfig): Boolean {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return false
        }
        var freqMhz: Double? = null
        var bwKhz: Double? = null
        var sf: Int? = null
        var cr: Int? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "freq" -> freqMhz = reader.nextDoubleOrNull()
                "bw" -> bwKhz = reader.nextDoubleOrNull()
                "sf" -> sf = reader.nextDoubleOrNull()?.toInt()
                "cr" -> cr = reader.nextDoubleOrNull()?.toInt()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return freqMhz != null && (freqMhz * 1000).roundToInt() == config.freqKhz &&
            bwKhz != null && (bwKhz * 1000).roundToInt() == config.bandwidthHz &&
            sf == config.spreadingFactor &&
            cr == config.codingRate
    }

    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.NULL) {
            nextNull(); null
        } else {
            nextString()
        }

    private fun JsonReader.nextDoubleOrNull(): Double? =
        if (peek() == JsonToken.NULL) {
            nextNull(); null
        } else {
            nextDouble()
        }

    /** ISO-8601 ("2026-07-11T01:03:06.000Z") to epoch ms; null on absence or garbage. */
    private fun JsonReader.nextEpochMsOrNull(): Long? =
        nextStringOrNull()?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}
