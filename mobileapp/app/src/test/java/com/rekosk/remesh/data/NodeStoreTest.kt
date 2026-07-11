package com.rekosk.remesh.data

import com.rekosk.remesh.data.model.Channel
import com.rekosk.remesh.data.model.ChannelKind
import com.rekosk.remesh.data.model.Contact
import com.rekosk.remesh.data.model.DeliveryState
import com.rekosk.remesh.data.model.HeardRepeat
import com.rekosk.remesh.data.model.MeshMessage
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.data.model.RecentAdvert
import com.rekosk.remesh.data.model.Route
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted format is what survives an app restart, so a model whose fields the
 * DTO forgets would silently lose data. These lock the mapping and the JSON shape.
 */
class NodeStoreTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a contact round-trips through its persisted form`() {
        val flood = Contact(
            id = "c:aabb",
            name = "Baltazarr",
            type = NodeType.REPEATER,
            route = Route.Flood,
            lastSeenEpochMs = 1_700_000_000_000,
            hasLocation = true,
            isBlocked = false,
        )
        val routed = flood.copy(route = Route.Hops(3), name = "Terasa")

        assertEquals(flood, flood.toPersisted().toDomain())
        assertEquals(routed, routed.toPersisted().toDomain())
        // -1 in the DTO is the flood sentinel, a real hop count is preserved.
        assertEquals(-1, flood.toPersisted().routeHops)
        assertEquals(3, routed.toPersisted().routeHops)
    }

    @Test
    fun `a message keeps its delivery state and heard repeats`() {
        val message = MeshMessage(
            id = "local-1",
            author = "Reko",
            text = "ping",
            timestampEpochMs = 1_700_000_000_000,
            isOutgoing = true,
            hopCount = 2,
            pathHashSizeBytes = 2,
            isDirectRoute = false,
            snr = -11.25f,
            deliveryState = DeliveryState.CONFIRMED,
            heardRepeats = listOf(HeardRepeat("F736", -11.25f, -110, 1_700_000_001_000)),
        )
        assertEquals(message, message.toPersisted().toDomain())
    }

    @Test
    fun `an advert keeps its full public key across the round trip`() {
        val advert = RecentAdvert(
            name = "ESP32C6 Repeater",
            publicKey = ByteArray(32) { (it + 1).toByte() },
            type = NodeType.REPEATER,
            receivedEpochMs = 1_700_000_000_000,
            hops = 0,
            isDirect = true,
        )
        val restored = advert.toPersisted().toDomain()
        assertArrayEquals(advert.publicKey, restored.publicKey)
        assertEquals(advert, restored)
    }

    @Test
    fun `a whole node store survives JSON encoding`() {
        val node = PersistedNode(
            key = "9c".repeat(32),
            name = "My Node",
            advType = 1,
            address = "AA:BB:CC:DD:EE:FF",
            lastConnected = 1_700_000_000_000,
            contacts = listOf(
                Contact("c:aabb", "Baltazarr", NodeType.REPEATER, Route.Hops(1), 1L).toPersisted(),
            ),
            channels = listOf(
                Channel("ch:0", 0, "Public", ChannelKind.PUBLIC).toPersisted(),
            ),
            messages = mapOf(
                "ch:0" to listOf(
                    MeshMessage("m1", "A", "hi", 2L, isOutgoing = false).toPersisted(),
                ),
            ),
        )
        val file = NodeStoreFile(listOf(node))
        val text = json.encodeToString(NodeStoreFile.serializer(), file)
        val decoded = json.decodeFromString(NodeStoreFile.serializer(), text)

        assertEquals(file, decoded)
        assertEquals(node, decoded.nodes.single())
    }

    @Test
    fun `a favourite contact keeps its star across the round trip`() {
        val fav = Contact("c:aabb", "Baltazarr", NodeType.REPEATER, Route.Flood, 1L, isFavorite = true)
        val plain = fav.copy(isFavorite = false, id = "c:ccdd")

        assertTrue(fav.toPersisted().toDomain().isFavorite)
        assertFalse(plain.toPersisted().toDomain().isFavorite)
    }

    @Test
    fun `read marks survive JSON encoding`() {
        val node = PersistedNode(
            key = "ab",
            name = "N",
            readMarks = mapOf("ch:0" to 1_700_000_000_000, "c:aabb" to 42L),
        )
        val text = json.encodeToString(NodeStoreFile.serializer(), NodeStoreFile(listOf(node)))
        val decoded = json.decodeFromString(NodeStoreFile.serializer(), text).nodes.single()

        assertEquals(1_700_000_000_000, decoded.readMarks["ch:0"])
        assertEquals(42L, decoded.readMarks["c:aabb"])
    }

    @Test
    fun `an unknown enum name decodes to a safe default rather than crashing`() {
        val contact = PersistedContact(id = "c:1", name = "X", type = "WORMHOLE")
        assertEquals(NodeType.CHAT, contact.toDomain().type)

        val channel = PersistedChannel(id = "ch:1", index = 1, name = "Y", kind = "QUANTUM")
        assertEquals(ChannelKind.PRIVATE, channel.toDomain().kind)
    }

    @Test
    fun `ignoreUnknownKeys lets an older store gain fields without breaking`() {
        val decoded = json.decodeFromString(
            NodeStoreFile.serializer(),
            """{"nodes":[{"key":"ab","name":"N","future_field":42}]}""",
        )
        assertTrue(decoded.nodes.single().messages.isEmpty())
        assertEquals("N", decoded.nodes.single().name)
    }
}
