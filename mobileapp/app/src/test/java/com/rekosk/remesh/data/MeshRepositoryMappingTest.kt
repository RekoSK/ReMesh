package com.rekosk.remesh.data

import com.rekosk.remesh.ble.ChannelCrypto
import com.rekosk.remesh.ble.MeshCoreProtocol
import com.rekosk.remesh.ble.MeshFrame
import com.rekosk.remesh.data.model.ChannelKind
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.data.model.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshRepositoryMappingTest {

    @Test
    fun `channel sender is split off the message body`() {
        val (sender, body) = MeshRepository.splitChannelSender("Goodyear COOP: Evening all")
        assertEquals("Goodyear COOP", sender)
        assertEquals("Evening all", body)
    }

    @Test
    fun `message body keeps its own colons`() {
        val (sender, body) = MeshRepository.splitChannelSender("N0RSR: link at 19:42: good")
        assertEquals("N0RSR", sender)
        assertEquals("link at 19:42: good", body)
    }

    @Test
    fun `message without a sender prefix is kept whole`() {
        val (sender, body) = MeshRepository.splitChannelSender("no prefix here")
        assertNull(sender)
        assertEquals("no prefix here", body)
    }

    @Test
    fun `leading separator is not treated as an empty sender`() {
        val (sender, body) = MeshRepository.splitChannelSender(": orphaned")
        assertNull(sender)
        assertEquals(": orphaned", body)
    }

    @Test
    fun `direct route reports no hop count`() {
        // On receive, 0xFF means "arrived direct" -- the inverse of its meaning on send.
        assertEquals(0, MeshCoreProtocol.PathInfo.hops(0xFF))
        assertEquals(4, MeshCoreProtocol.PathInfo.hops(4))
    }

    @Test
    fun `out_path_len is decoded as a packed path byte, not a raw hop count`() {
        // 0xFF is OUT_PATH_UNKNOWN: no route, so traffic floods.
        assertEquals(Route.Flood, contactWithOutPathLen(0xFF).toContact().route)
        // 0x00: a plain direct route (0 hops).
        assertEquals(Route.Hops(0), contactWithOutPathLen(0x00).toContact().route)
        // 0x40 packs "2-byte hash mode, 0 hops" -- still direct, NOT 64 hops. This was
        // the bug: a direct contact showed "64 hops" with 64 zero bytes.
        assertEquals(Route.Hops(0), contactWithOutPathLen(0x40).toContact().route)
        // 0x03 packs "1-byte hash mode, 3 hops".
        assertEquals(Route.Hops(3), contactWithOutPathLen(0x03).toContact().route)
        // 0x42 packs "2-byte hash mode, 2 hops".
        assertEquals(Route.Hops(2), contactWithOutPathLen(0x42).toContact().route)
    }

    private fun contactWithOutPathLen(outPathLen: Int) = MeshFrame.Contact(
        publicKey = ByteArray(MeshCoreProtocol.PUB_KEY_SIZE),
        type = MeshCoreProtocol.AdvType.CHAT,
        flags = 0,
        outPathLen = outPathLen,
        outPath = ByteArray(MeshCoreProtocol.MAX_PATH_SIZE),
        name = "SK-KE-Test",
        lastAdvertEpochSec = 0,
        latE6 = 0,
        lonE6 = 0,
        lastModEpochSec = 0,
    )

    @Test
    fun `channel kind is derived from the name and the key`() {
        val public = ChannelCrypto.PUBLIC_SECRET
        val secret = ChannelCrypto.hashtagSecret("#testing")
        val private = ByteArray(16) { 7 }

        assertEquals(ChannelKind.PUBLIC, MeshRepository.channelKind("Public", public))
        assertEquals(ChannelKind.HASHTAG, MeshRepository.channelKind("#testing", secret))
        assertEquals(ChannelKind.PRIVATE, MeshRepository.channelKind("Regulars", private))
        // The public key makes a channel public whatever slot it landed in.
        assertEquals(ChannelKind.PUBLIC, MeshRepository.channelKind("Verejny", public))
        // A hashtag name wins even over the public key.
        assertEquals(ChannelKind.HASHTAG, MeshRepository.channelKind("#odd", public))
    }

    /**
     * A contact row carries two timestamps and only one of them means "last seen".
     * These numbers are real, read off the author's node: SK-KE-Terasa's advert claimed
     * a time two days in the future, and SK-KE-Galakticka's claimed May 2024, because
     * `last_advert_timestamp` is whatever clock the *sender* happened to have.
     */
    @Test
    fun `last seen comes from lastmod, not from the sender's advert clock`() {
        val terasa = contactRow(lastAdvert = 1_783_877_266, lastMod = 1_783_676_376)
        assertEquals(1_783_676_376_000L, terasa.toContact().lastSeenEpochMs)

        val galakticka = contactRow(lastAdvert = 1_716_072_753, lastMod = 1_783_677_557)
        assertEquals(1_783_677_557_000L, galakticka.toContact().lastSeenEpochMs)
    }

    @Test
    fun `a contact the node has never heard from has no last seen time`() {
        assertNull(contactRow(lastAdvert = 1_783_877_266, lastMod = 0).toContact().lastSeenEpochMs)
    }

    private fun contactRow(lastAdvert: Long, lastMod: Long) = MeshFrame.Contact(
        publicKey = ByteArray(MeshCoreProtocol.PUB_KEY_SIZE),
        type = MeshCoreProtocol.AdvType.REPEATER,
        flags = 0,
        outPathLen = -1,
        outPath = ByteArray(MeshCoreProtocol.MAX_PATH_SIZE),
        name = "SK-KE-Test",
        lastAdvertEpochSec = lastAdvert,
        latE6 = 0,
        lonE6 = 0,
        lastModEpochSec = lastMod,
    )

    @Test
    fun `advert types map onto node types`() {
        assertEquals(NodeType.CHAT, MeshRepository.nodeTypeOf(MeshCoreProtocol.AdvType.CHAT))
        assertEquals(NodeType.REPEATER, MeshRepository.nodeTypeOf(MeshCoreProtocol.AdvType.REPEATER))
        assertEquals(NodeType.ROOM, MeshRepository.nodeTypeOf(MeshCoreProtocol.AdvType.ROOM))
        assertEquals(NodeType.SENSOR, MeshRepository.nodeTypeOf(MeshCoreProtocol.AdvType.SENSOR))
        // ADV_TYPE_NONE and anything unknown fall back to a plain chat node.
        assertEquals(NodeType.CHAT, MeshRepository.nodeTypeOf(MeshCoreProtocol.AdvType.NONE))
        assertEquals(NodeType.CHAT, MeshRepository.nodeTypeOf(99))
    }

    @Test
    fun `self contact uri matches the documented share format`() {
        val key = ByteArray(32) { it.toByte() }
        val uri = MeshRepository.contactUri("Reko DIY", key, MeshCoreProtocol.AdvType.CHAT)
        assertEquals(
            "meshcore://contact/add?name=Reko+DIY" +
                "&public_key=000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "&type=1",
            uri,
        )
    }

    @Test
    fun `contact uri percent-encodes awkward names`() {
        val uri = MeshRepository.contactUri("a&b=c", ByteArray(32), 2)
        // & and = would otherwise forge extra query parameters.
        assertEquals(true, uri.contains("name=a%26b%3Dc"))
        assertEquals(true, uri.endsWith("&type=2"))
    }

    @Test
    fun `conversation ids are distinguishable and stable`() {
        val prefix = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0x01, 0x02, 0x03, 0x04)
        assertEquals("c:aabb01020304", MeshRepository.contactConversationId(prefix))
        assertEquals("ch:3", MeshRepository.channelConversationId(3))
    }
}
