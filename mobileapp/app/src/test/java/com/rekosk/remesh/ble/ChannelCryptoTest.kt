package com.rekosk.remesh.ble

import com.rekosk.remesh.ble.ChannelCrypto.decodeHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * These bytes are what the node actually transmits. Nothing in the app can check
 * that at runtime, so the wire format is pinned here against the firmware and the
 * two published test vectors in docs/qr_codes.md and docs/companion_protocol.md.
 */
class ChannelCryptoTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `public channel secret matches the firmware's base64 PSK`() {
        // PUBLIC_GROUP_PSK = "izOH6cXN6mrJ5e26oRXNcg==" in MyMesh.cpp.
        val fromBase64 = java.util.Base64.getDecoder().decode("izOH6cXN6mrJ5e26oRXNcg==")
        assertArrayEquals(fromBase64, ChannelCrypto.PUBLIC_SECRET)
        assertEquals("8b3387e9c5cdea6ac9e5edbaa115cd72", ChannelCrypto.PUBLIC_SECRET.hex())
    }

    @Test
    fun `hashtag secret matches the documented vector`() {
        // docs/companion_protocol.md: "#test" has the key 9cd8fcf22a47333b591d96a2b848b73f
        assertEquals("9cd8fcf22a47333b591d96a2b848b73f", ChannelCrypto.hashtagSecret("#test").hex())
        assertEquals(ChannelCrypto.SECRET_SIZE, ChannelCrypto.hashtagSecret("#slovakia").size)
    }

    @Test
    fun `the hash is part of the hashtag name`() {
        assertNotEquals(
            ChannelCrypto.hashtagSecret("#test").hex(),
            ChannelCrypto.hashtagSecret("test").hex(),
        )
    }

    @Test
    fun `hashtag names are validated the way the firmware's peers expect`() {
        assertNull(ChannelCrypto.validateHashtag("#meshcore"))
        assertNull(ChannelCrypto.validateHashtag("#mesh-core-2"))
        assertEquals(
            "A hashtag channel name must start with '#'",
            ChannelCrypto.validateHashtag("meshcore"),
        )
        assertEquals("Enter a name after the '#'", ChannelCrypto.validateHashtag("#"))
        assertEquals(
            "Only lowercase letters, digits and dashes are allowed",
            ChannelCrypto.validateHashtag("#MeshCore"),
        )
        assertEquals(
            "Only lowercase letters, digits and dashes are allowed",
            ChannelCrypto.validateHashtag("#mesh core"),
        )
    }

    @Test
    fun `channel hash is the first byte of sha256 of the secret`() {
        // sha256(8b3387e9c5cdea6ac9e5edbaa115cd72) computed independently.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(ChannelCrypto.PUBLIC_SECRET)
        assertEquals(digest[0], ChannelCrypto.channelHash(ChannelCrypto.PUBLIC_SECRET))
    }

    @Test
    fun `plaintext is timestamp then type then sender-prefixed text`() {
        val plaintext = ChannelCrypto.groupTextPlaintext(0x1234_5678, "NEO", "Dobre ranko")

        val timestamp = ByteBuffer.wrap(plaintext, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(0x1234_5678, timestamp)
        assertEquals(0.toByte(), plaintext[4]) // TXT_TYPE_PLAIN
        assertEquals("NEO: Dobre ranko", String(plaintext, 5, plaintext.size - 5))
    }

    @Test
    fun `a long name eats into the text, exactly as sendGroupMessage does`() {
        // BaseChatMesh caps "name: text" at MAX_TEXT_LEN, trimming the text, not the name.
        val name = "n".repeat(150)
        val text = "t".repeat(60)
        val plaintext = ChannelCrypto.groupTextPlaintext(1, name, text)

        val joined = String(plaintext, 5, plaintext.size - 5)
        assertEquals(ChannelCrypto.MAX_GROUP_TEXT_LEN, joined.length)
        assertTrue(joined.startsWith("$name: "))
        assertEquals(ChannelCrypto.MAX_GROUP_TEXT_LEN - (name.length + 2), joined.count { it == 't' })
    }

    @Test
    fun `an absurdly long name leaves no room for text rather than throwing`() {
        val plaintext = ChannelCrypto.groupTextPlaintext(1, "n".repeat(400), "hello")
        // 5 header bytes plus the untruncated prefix; the body is squeezed to nothing.
        assertEquals(5 + 402, plaintext.size)
    }

    @Test
    fun `payload is channel hash, two MAC bytes, then whole cipher blocks`() {
        val secret = ChannelCrypto.PUBLIC_SECRET
        val payload = ChannelCrypto.groupTextPayload(secret, 1_700_000_000, "NEO", "hi")

        assertEquals(ChannelCrypto.channelHash(secret), payload[0])
        val ciphertextLength = payload.size - 1 - ChannelCrypto.MAC_SIZE
        assertEquals(0, ciphertextLength % 16)
        // 5 + "NEO: hi".length = 12 bytes of plaintext -> one 16-byte block.
        assertEquals(16, ciphertextLength)
    }

    @Test
    fun `the same message always encrypts to the same bytes`() {
        // AES-ECB has no nonce. Heard-repeat matching depends on exactly this.
        val a = ChannelCrypto.groupTextPayload(ChannelCrypto.PUBLIC_SECRET, 42, "NEO", "hi")
        val b = ChannelCrypto.groupTextPayload(ChannelCrypto.PUBLIC_SECRET, 42, "NEO", "hi")
        assertArrayEquals(a, b)
    }

    @Test
    fun `a different timestamp, sender or key changes the payload`() {
        val base = ChannelCrypto.groupTextPayload(ChannelCrypto.PUBLIC_SECRET, 42, "NEO", "hi")
        val laterStamp = ChannelCrypto.groupTextPayload(ChannelCrypto.PUBLIC_SECRET, 43, "NEO", "hi")
        val otherSender = ChannelCrypto.groupTextPayload(ChannelCrypto.PUBLIC_SECRET, 42, "ZED", "hi")
        val otherKey = ChannelCrypto.groupTextPayload(ChannelCrypto.hashtagSecret("#test"), 42, "NEO", "hi")

        assertNotEquals(base.hex(), laterStamp.hex())
        assertNotEquals(base.hex(), otherSender.hex())
        assertNotEquals(base.hex(), otherKey.hex())
    }

    /**
     * The whole on-air payload, byte for byte, against an independent implementation
     * of `Utils::encryptThenMAC` + `Mesh::createGroupDatagram`. This is the one thing
     * the app cannot check at runtime: if these bytes are wrong, a sent message simply
     * never matches its own repeat and quietly never earns a second tick.
     */
    @Test
    fun `payload matches an independent implementation of the firmware`() {
        assertEquals(
            "1172dcb6f77dcd1f856eec2bfc53c0b1a999ee",
            ChannelCrypto.groupTextPayload(ChannelCrypto.PUBLIC_SECRET, 1_700_000_000, "NEO", "hi").hex(),
        )
        assertEquals(
            "11abc909d841ff3b1eab65ace555498ecdd824",
            ChannelCrypto.groupTextPayload(ChannelCrypto.PUBLIC_SECRET, 42, "NEO", "hi").hex(),
        )
        assertEquals(
            "d963db2176fdfe25c6b5633ed977d84bc7dd731b13a502138919b3ee91acf4e7a52f91",
            ChannelCrypto.groupTextPayload(
                ChannelCrypto.hashtagSecret("#test"),
                1_234_567_890,
                "Reko-Node",
                "Dobre ranko",
            ).hex(),
        )
    }

    @Test
    fun `an over-long message truncates to the same bytes the firmware would send`() {
        assertEquals(
            "111e569f150153077dcbb3e07d0a76d11eeb0e19c4d300b47100d9b3c80644cbe5cd" +
                "04d8cebca3635c1e1e5f3154caf495b35c8b1e06c7e1539d003b40a794d36151368b" +
                "1e06c7e1539d003b40a794d36151368b1e06c7e1539d003b40a794d36151368b1e06" +
                "c7e1539d003b40a794d36151368b1e06c7e1539d003b40a794d36151368b1e06c7e1" +
                "539d003b40a794d36151368b1e06c7e1539d003b40a794d3615136e264139d3353ed" +
                "fcd58eecd688794bcd",
            ChannelCrypto.groupTextPayload(
                ChannelCrypto.PUBLIC_SECRET,
                0x1234_5678,
                "N".repeat(40),
                "x".repeat(200),
            ).hex(),
        )
    }

    /**
     * How a channel is deleted: the firmware has no delete command, so the slot is
     * overwritten with an empty name and a zeroed key. `MACThenDecrypt` can never
     * authenticate a packet against that key, which is what makes the slot inert.
     */
    @Test
    fun `clearing a channel writes an empty name and a zeroed key`() {
        val frame = MeshCoreProtocol.encodeSetChannel(3, "", ByteArray(ChannelCrypto.SECRET_SIZE))

        assertEquals(50, frame.size)
        assertEquals(MeshCoreProtocol.Cmd.SET_CHANNEL.toByte(), frame[0])
        assertEquals(3.toByte(), frame[1])
        assertTrue(frame.drop(2).all { it == 0.toByte() })
    }

    @Test
    fun `decodeHex round-trips and rejects rubbish`() {
        assertEquals("0aff10", "0AFF10".decodeHex().hex())
        assertEquals(16, "8b3387e9c5cdea6ac9e5edbaa115cd72".decodeHex().size)
        assertTrue(runCatching { "abc".decodeHex() }.isFailure)
        assertTrue(runCatching { "zz".decodeHex() }.isFailure)
    }
}
