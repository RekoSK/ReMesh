package com.rekosk.remesh.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export must be byte-compatible with the official MeshCore app, so these
 * tests pin the exact JSON text, not just the parsed shape. Key order, the
 * two-space indent, coordinates-as-strings and the explicit `custom_name: null`
 * are all part of the contract.
 */
class ConfigCodecTest {

    private val full = ConfigFile(
        name = "Reko",
        publicKey = "aa".repeat(32),
        privateKey = "bb".repeat(64),
        radio = RadioConfig(
            frequency = 869_618,
            bandwidth = 62_500,
            spreadingFactor = 8,
            codingRate = 5,
            txPower = 20,
        ),
        position = PositionConfig(latitude = 48.707708, longitude = 21.216309),
        other = OtherConfig(manualAddContacts = 0, advertLocationPolicy = 1),
        autoAdd = AutoAddConfig(
            chat = true,
            repeater = true,
            roomServer = true,
            sensor = true,
            overwriteOldest = false,
            maxHops = 0,
        ),
        channels = listOf(ChannelConfig("Public", "8b3387e9c5cdea6ac9e5edbaa115cd72")),
        contacts = listOf(
            ContactConfig(
                type = 2,
                name = "SK-KE-Barca",
                customName = null,
                publicKey = "54b25efb".padEnd(64, '0'),
                flags = 0,
                latitude = 48.666436,
                longitude = 21.25862,
                lastAdvert = 1783856386,
                lastModified = 1783655423,
                outPathList = "",
            ),
        ),
    )

    @Test
    fun `encoding produces the reference app's exact layout`() {
        val expected = """
            {
              "name": "Reko",
              "public_key": "${"aa".repeat(32)}",
              "private_key": "${"bb".repeat(64)}",
              "radio_settings": {
                "frequency": 869618,
                "bandwidth": 62500,
                "spreading_factor": 8,
                "coding_rate": 5,
                "tx_power": 20
              },
              "position_settings": {
                "latitude": "48.707708",
                "longitude": "21.216309"
              },
              "other_settings": {
                "manual_add_contacts": 0,
                "advert_location_policy": 1
              },
              "auto_add_settings": {
                "auto_add_chat": true,
                "auto_add_repeater": true,
                "auto_add_room_server": true,
                "auto_add_sensor": true,
                "overwrite_oldest": false,
                "auto_add_max_hops": 0
              },
              "channels": [
                {
                  "name": "Public",
                  "secret": "8b3387e9c5cdea6ac9e5edbaa115cd72"
                }
              ],
              "contacts": [
                {
                  "type": 2,
                  "name": "SK-KE-Barca",
                  "custom_name": null,
                  "public_key": "54b25efb${"0".repeat(56)}",
                  "flags": 0,
                  "latitude": "48.666436",
                  "longitude": "21.25862",
                  "last_advert": 1783856386,
                  "last_modified": 1783655423,
                  "out_path_list": ""
                }
              ]
            }
        """.trimIndent()
        assertEquals(expected, ConfigCodec.encode(full))
    }

    @Test
    fun `a file survives a decode and encode unchanged`() {
        val text = ConfigCodec.encode(full)
        assertEquals(text, ConfigCodec.encode(ConfigCodec.decode(text)))
    }

    @Test
    fun `unselected sections are omitted entirely`() {
        val text = ConfigCodec.encode(ConfigFile(name = "Only the name"))
        assertEquals("{\n  \"name\": \"Only the name\"\n}", text)
        assertTrue(!text.contains("radio_settings"))
        assertTrue(!text.contains("contacts"))
    }

    // ---- the formatting quirks that make it cross-compatible ----

    @Test
    fun `coordinates drop trailing zeros but never lose the decimal point`() {
        assertEquals("48.707708", ConfigCodec.formatCoordinate(48.707708))
        assertEquals("48.3", ConfigCodec.formatCoordinate(48.3))
        assertEquals("18.08221", ConfigCodec.formatCoordinate(18.08221))
        assertEquals("0.0", ConfigCodec.formatCoordinate(0.0))
        assertEquals("48.0", ConfigCodec.formatCoordinate(48.0))
        assertEquals("-21.5", ConfigCodec.formatCoordinate(-21.5))
    }

    @Test
    fun `out path is null when unknown, empty when direct, hex otherwise`() {
        val path = byteArrayOf(0xF2.toByte(), 0x5B, 0x00, 0x00)
        // 0xFF (read as -1): the node knows no route.
        assertNull(ConfigCodec.formatOutPath(-1, path))
        assertNull(ConfigCodec.formatOutPath(0xFF, path))
        // Zero hop count is a direct route, whatever the hash-size mode.
        assertEquals("", ConfigCodec.formatOutPath(0, path))
        // 0x40 packs "2-byte hash mode, 0 hops": still direct, NOT 64 zero bytes.
        assertEquals("", ConfigCodec.formatOutPath(0x40, path))
        // 0x02 packs "1-byte hash mode, 2 hops": two bytes of path.
        assertEquals("f25b", ConfigCodec.formatOutPath(2, path))
        // 0x42 packs "2-byte hash mode, 2 hops": four bytes of path.
        assertEquals("f25b0000", ConfigCodec.formatOutPath(0x42, path))
    }

    @Test
    fun `radio units are the raw ones the node reports`() {
        // 869618 kHz and 62500 Hz, not 869.618 and 62.5.
        val text = ConfigCodec.encode(ConfigFile(radio = full.radio))
        assertTrue(text.contains("\"frequency\": 869618"))
        assertTrue(text.contains("\"bandwidth\": 62500"))
    }

    @Test
    fun `decoding reads coordinates back from their string form`() {
        val config = ConfigCodec.decode(ConfigCodec.encode(full))
        assertEquals(48.707708, config.position!!.latitude, 1e-9)
        assertEquals(21.216309, config.position!!.longitude, 1e-9)
        assertEquals(48.666436, config.contacts!![0].latitude, 1e-9)
    }

    @Test
    fun `sectionsPresent reflects what the file actually carries`() {
        assertEquals(ConfigSection.entries.toSet(), full.sectionsPresent())
        assertEquals(setOf(ConfigSection.NAME), ConfigFile(name = "x").sectionsPresent())
        assertEquals(emptySet<ConfigSection>(), ConfigFile().sectionsPresent())
    }

    @Test
    fun `an unknown key does not break the parser`() {
        val text = """{"name":"x","future_field":123}"""
        assertEquals("x", ConfigCodec.decode(text).name)
    }
}
