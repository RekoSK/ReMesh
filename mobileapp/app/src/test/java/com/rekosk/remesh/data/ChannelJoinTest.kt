package com.rekosk.remesh.data

import com.rekosk.remesh.ble.ChannelCrypto
import com.rekosk.remesh.ble.MeshCoreProtocol
import com.rekosk.remesh.ble.MeshFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelUriTest {

    @Test
    fun `a channel invitation yields its name and key`() {
        val shared = parseChannelUri(
            "meshcore://channel/add?name=Public&secret=8b3387e9c5cdea6ac9e5edbaa115cd72",
        )!!
        assertEquals("Public", shared.name)
        assertArrayEquals(ChannelCrypto.PUBLIC_SECRET, shared.secret)
    }

    @Test
    fun `names are url-decoded`() {
        val shared = parseChannelUri(
            "meshcore://channel/add?name=My+Secret%20Room&secret=8b3387e9c5cdea6ac9e5edbaa115cd72",
        )!!
        assertEquals("My Secret Room", shared.name)
    }

    @Test
    fun `an unknown region_scope is ignored rather than fatal`() {
        val shared = parseChannelUri(
            "meshcore://channel/add?name=SK&secret=8b3387e9c5cdea6ac9e5edbaa115cd72&region_scope=eu",
        )!!
        assertEquals("SK", shared.name)
    }

    @Test
    fun `a shared channel round-trips through its own QR link`() {
        val secret = ChannelCrypto.hashtagSecret("#slovakia")
        val parsed = parseChannelUri(channelUri("My Secret Room", secret))!!

        assertEquals("My Secret Room", parsed.name)
        assertArrayEquals(secret, parsed.secret)
    }

    @Test
    fun `the shared link matches the format the reference app prints`() {
        assertEquals(
            "meshcore://channel/add?name=Public&secret=8b3387e9c5cdea6ac9e5edbaa115cd72",
            channelUri("Public", ChannelCrypto.PUBLIC_SECRET),
        )
        // '&' and '=' in a name would otherwise forge a second query parameter.
        val forged = channelUri("a&secret=00", ChannelCrypto.PUBLIC_SECRET)
        assertEquals("a&secret=00", parseChannelUri(forged)!!.name)
    }

    @Test
    fun `anything that is not a channel invitation is rejected`() {
        assertNull(parseChannelUri("https://example.com"))
        assertNull(parseChannelUri("meshcore://contact/add?name=A&public_key=00"))
        // No name.
        assertNull(parseChannelUri("meshcore://channel/add?secret=8b3387e9c5cdea6ac9e5edbaa115cd72"))
        // No key.
        assertNull(parseChannelUri("meshcore://channel/add?name=Public"))
        // A 32-byte key, which CMD_SET_CHANNEL would refuse.
        assertNull(
            parseChannelUri(
                "meshcore://channel/add?name=P&secret=" + "ab".repeat(32),
            ),
        )
        assertNull(parseChannelUri("meshcore://channel/add?name=P&secret=nothex0000000000"))
        assertNull(parseChannelUri(""))
    }
}

class RepeaterNamesTest {

    private fun contact(name: String, keyHex: String): MeshFrame.Contact {
        val key = ByteArray(MeshCoreProtocol.PUB_KEY_SIZE)
        val prefix = ByteArray(keyHex.length / 2) {
            keyHex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
        prefix.copyInto(key)
        return MeshFrame.Contact(
            publicKey = key,
            type = MeshCoreProtocol.AdvType.REPEATER,
            flags = 0,
            outPathLen = -1,
            outPath = ByteArray(MeshCoreProtocol.MAX_PATH_SIZE),
            name = name,
            lastAdvertEpochSec = 0,
            latE6 = 0,
            lonE6 = 0,
            lastModEpochSec = 0,
        )
    }

    @Test
    fun `a hash matching exactly one contact names it`() {
        val contacts = listOf(contact("Cingov 1", "89ab"), contact("Cingov 3", "21cd"))
        val resolved = ResolvedRepeat("89", resolveRepeaterName("89", contacts), 7.75f, -100)

        assertEquals("Cingov 1", resolved.title)
        assertEquals("1 known repeater", resolved.subtitle)
        assertEquals(false, resolved.isAmbiguous)
    }

    @Test
    fun `a hash matching nothing is labelled Unknown with the hash`() {
        val resolved = ResolvedRepeat("7F", resolveRepeaterName("7F", emptyList()), 0f, -100)

        assertEquals("Unknown (7F)", resolved.title)
        assertEquals("No known repeater", resolved.subtitle)
        assertEquals(false, resolved.isAmbiguous)
    }

    @Test
    fun `a hash matching several contacts is Duplicated and lists them`() {
        val contacts = listOf(
            contact("Cingov 8998", "8998"),
            contact("Cingov 89ff", "89ff"),
            contact("Elsewhere", "2100"),
        )
        val name = resolveRepeaterName("89", contacts)
        val resolved = ResolvedRepeat("89", name, 3.75f, -90)

        assertEquals("Duplicated (89)", resolved.title)
        assertEquals("2 known repeaters", resolved.subtitle)
        assertEquals(true, resolved.isAmbiguous)
        assertEquals(listOf("Cingov 8998", "Cingov 89ff"), (name as RepeaterName.Duplicated).names)
    }

    @Test
    fun `matching is case-insensitive, because hashes are rendered upper case`() {
        val contacts = listOf(contact("Node", "abcd"))
        assertEquals(RepeaterName.Known("Node"), resolveRepeaterName("AB", contacts))
    }

    @Test
    fun `a two-byte hash narrows a prefix that one byte would have made ambiguous`() {
        val contacts = listOf(contact("First", "8998"), contact("Second", "89ff"))
        assertEquals(RepeaterName.Known("First"), resolveRepeaterName("8998", contacts))
    }
}

class ContactUriTest {

    @Test
    fun `a shared contact link yields its name, key and type`() {
        val shared = parseContactUri(
            "meshcore://contact/add?name=Example+Contact&public_key=" +
                "9cd8fcf22a47333b591d96a2b848b73f457b1bb1a3ea2453a885f9e5787765b1&type=2",
        )!!
        assertEquals("Example Contact", shared.name)
        assertEquals(2, shared.type)
        assertEquals(
            "9cd8fcf22a47333b591d96a2b848b73f457b1bb1a3ea2453a885f9e5787765b1",
            shared.publicKeyHex,
        )
    }

    @Test
    fun `a missing or silly type falls back to chat`() {
        val key = "public_key=" + "ab".repeat(32)
        assertEquals(1, parseContactUri("meshcore://contact/add?name=A&$key")!!.type)
        assertEquals(1, parseContactUri("meshcore://contact/add?name=A&$key&type=99")!!.type)
    }

    @Test
    fun `a contact link round-trips through the share format`() {
        val key = ByteArray(32) { it.toByte() }
        val uri = MeshRepository.contactUri("Reko DIY", key, 1)
        val parsed = parseContactUri(uri)!!

        assertEquals("Reko DIY", parsed.name)
        assertArrayEquals(key, parsed.publicKey)
    }

    @Test
    fun `anything that is not a contact link is rejected`() {
        assertNull(parseContactUri("meshcore://channel/add?name=P&secret=" + "ab".repeat(16)))
        assertNull(parseContactUri("meshcore://contact/add?name=A&public_key=abcd"))
        assertNull(parseContactUri("meshcore://contact/add?public_key=" + "ab".repeat(32)))
        assertNull(parseContactUri("https://example.com"))
    }
}
