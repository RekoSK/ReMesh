package com.rekosk.remesh.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Commands behind the eight settings sub-screens. Layouts transcribed from
 * `examples/companion_radio/MyMesh.cpp`.
 */
class NodeSettingsCommandsTest {

    // ---------------- Bluetooth PIN ----------------

    @Test
    fun `random pin encodes as zero`() {
        val frame = MeshCoreProtocol.encodeSetDevicePin(MeshCoreProtocol.BlePin.RANDOM)
        assertArrayEquals(byteArrayOf(37, 0, 0, 0, 0), frame)
    }

    @Test
    fun `fixed pin is little endian`() {
        val frame = MeshCoreProtocol.encodeSetDevicePin(123456)
        assertEquals(37, frame[0].toInt())
        // 123456 == 0x0001E240
        assertArrayEquals(
            byteArrayOf(0x40, 0xE2.toByte(), 0x01, 0x00),
            frame.copyOfRange(1, 5),
        )
    }

    @Test
    fun `pin outside the firmware's accepted range is rejected before sending`() {
        // Firmware accepts 0 or 100000..999999 and answers ERR_CODE_ILLEGAL_ARG otherwise.
        assertThrows(IllegalArgumentException::class.java) {
            MeshCoreProtocol.encodeSetDevicePin(99_999)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeshCoreProtocol.encodeSetDevicePin(1_000_000)
        }
    }

    // ---------------- auto-add contacts ----------------

    @Test
    fun `autoadd bit flags match the firmware`() {
        assertEquals(0x01, MeshCoreProtocol.AutoAdd.OVERWRITE_OLDEST)
        assertEquals(0x02, MeshCoreProtocol.AutoAdd.CHAT)
        assertEquals(0x04, MeshCoreProtocol.AutoAdd.REPEATER)
        assertEquals(0x08, MeshCoreProtocol.AutoAdd.ROOM)
        assertEquals(0x10, MeshCoreProtocol.AutoAdd.SENSOR)
        assertEquals(0x1E, MeshCoreProtocol.AutoAdd.ALL_TYPES)
    }

    @Test
    fun `set autoadd config carries bitfield and max hops`() {
        val bits = MeshCoreProtocol.AutoAdd.ALL_TYPES or MeshCoreProtocol.AutoAdd.OVERWRITE_OLDEST
        val frame = MeshCoreProtocol.encodeSetAutoAddConfig(bits, 7)
        assertArrayEquals(byteArrayOf(58, 0x1F, 7), frame)
    }

    @Test
    fun `autoadd config decodes and exposes flags`() {
        val frame = byteArrayOf(25, 0x06, 3) // chat + repeater, 3 hops
        val cfg = MeshCoreProtocol.decode(frame) as MeshFrame.AutoAddConfig
        assertEquals(3, cfg.maxHops)
        assertTrue(cfg.has(MeshCoreProtocol.AutoAdd.CHAT))
        assertTrue(cfg.has(MeshCoreProtocol.AutoAdd.REPEATER))
        assertTrue(!cfg.has(MeshCoreProtocol.AutoAdd.SENSOR))
        assertTrue(!cfg.has(MeshCoreProtocol.AutoAdd.OVERWRITE_OLDEST))
    }

    @Test
    fun `autoadd config tolerates a two byte reply`() {
        val cfg = MeshCoreProtocol.decode(byteArrayOf(25, 0x02)) as MeshFrame.AutoAddConfig
        assertEquals(0, cfg.maxHops)
    }

    // ---------------- telemetry bitfield ----------------

    @Test
    fun `telemetry packs three two-bit fields`() {
        // env=1, loc=2, base=1 -> 0b01_10_01 = 0x19
        assertEquals(0x19, MeshCoreProtocol.Telemetry.pack(base = 1, location = 2, environment = 1))
    }

    @Test
    fun `telemetry unpacks what it packs`() {
        val packed = MeshCoreProtocol.Telemetry.pack(base = 2, location = 0, environment = 1)
        assertEquals(2, MeshCoreProtocol.Telemetry.base(packed))
        assertEquals(0, MeshCoreProtocol.Telemetry.location(packed))
        assertEquals(1, MeshCoreProtocol.Telemetry.environment(packed))
    }

    @Test
    fun `telemetry ignores bits outside each two-bit field`() {
        assertEquals(0x3F, MeshCoreProtocol.Telemetry.pack(0xFF, 0xFF, 0xFF))
    }

    // ---------------- direct message acks ----------------

    @Test
    fun `the user picks a total, the node stores the extras`() {
        // getExtraAckTransmitCount() returns multi_acks, and Mesh.cpp sends that many
        // acks *in addition to* the first, so total = stored + 1.
        assertEquals(0, MeshCoreProtocol.MultiAcks.extraOf(1))
        assertEquals(1, MeshCoreProtocol.MultiAcks.extraOf(2))
        assertEquals(1, MeshCoreProtocol.MultiAcks.totalOf(0))
        assertEquals(2, MeshCoreProtocol.MultiAcks.totalOf(1))
    }

    @Test
    fun `only one and two acks are offered`() {
        // CommonCLI.cpp: constrain(multi_acks, 0, 1)
        assertEquals(listOf(1, 2), MeshCoreProtocol.MultiAcks.TOTAL_OPTIONS)
    }

    @Test
    fun `a node corrupted by another app reads back as two total acks`() {
        // The official app writes the total (2) into a 0..1 field. Display it sanely
        // instead of showing 3.
        assertEquals(2, MeshCoreProtocol.MultiAcks.totalOf(2))
        assertEquals(2, MeshCoreProtocol.MultiAcks.totalOf(200))
    }

    @Test
    fun `set other params clamps an out of range ack count`() {
        val frame = MeshCoreProtocol.encodeSetOtherParams(
            manualAddContacts = false,
            telemetryMode = 0,
            advertLocPolicy = 0,
            multiAcks = 2, // what the official app would have sent
        )
        assertEquals(1, frame[4].toInt())
    }

    @Test
    fun `self info reports the total acks, not the raw extras`() {
        val frame = ByteArray(58)
        frame[0] = 5
        frame[44] = 1 // multi_acks
        val info = MeshCoreProtocol.decode(frame) as MeshFrame.SelfInfo
        assertEquals(1, info.multiAcks)
        assertEquals(2, info.totalDirectAcks)
    }

    // ---------------- path hash mode ----------------

    @Test
    fun `path hash mode frame has the required zero sub-command byte`() {
        // Firmware guard: cmd_frame[1] == 0 && len >= 3
        assertArrayEquals(byteArrayOf(61, 0, 1), MeshCoreProtocol.encodeSetPathHashMode(1))
    }

    @Test
    fun `path hash mode 3 is reserved and rejected locally`() {
        assertThrows(IllegalArgumentException::class.java) {
            MeshCoreProtocol.encodeSetPathHashMode(3)
        }
    }

    @Test
    fun `path hash sizes and hop caps follow the 64 byte path budget`() {
        assertEquals(1, MeshCoreProtocol.PathHashMode.hashSizeBytes(0))
        assertEquals(64, MeshCoreProtocol.PathHashMode.maxHops(0))
        assertEquals(2, MeshCoreProtocol.PathHashMode.hashSizeBytes(1))
        assertEquals(32, MeshCoreProtocol.PathHashMode.maxHops(1))
        assertEquals(3, MeshCoreProtocol.PathHashMode.hashSizeBytes(2))
        assertEquals(21, MeshCoreProtocol.PathHashMode.maxHops(2))
    }

    // ---------------- custom vars / GPS ----------------

    @Test
    fun `gps custom var uses the name colon value form`() {
        assertEquals("gps:1", String(MeshCoreProtocol.encodeSetGpsEnabled(true), 1, 5))
        assertEquals(41, MeshCoreProtocol.encodeSetGpsEnabled(true)[0].toInt())
        assertEquals("gps:0", String(MeshCoreProtocol.encodeSetGpsEnabled(false), 1, 5))
    }

    @Test
    fun `custom vars decode comma separated pairs`() {
        val body = "gps:1,gps_interval:30".toByteArray()
        val frame = ByteArray(1 + body.size).also { it[0] = 21; body.copyInto(it, 1) }
        val vars = MeshCoreProtocol.decode(frame) as MeshFrame.CustomVars
        assertEquals("30", vars.values["gps_interval"])
        assertEquals(true, vars.gpsEnabled)
    }

    @Test
    fun `a node without gps reports no gps var`() {
        val body = "gps_interval:30".toByteArray()
        val frame = ByteArray(1 + body.size).also { it[0] = 21; body.copyInto(it, 1) }
        val vars = MeshCoreProtocol.decode(frame) as MeshFrame.CustomVars
        assertNull(vars.gpsEnabled)
    }

    @Test
    fun `custom vars ignore trailing nulls the firmware may leave`() {
        val body = "gps:0".toByteArray()
        val frame = ByteArray(1 + body.size + 3).also { it[0] = 21; body.copyInto(it, 1) }
        val vars = MeshCoreProtocol.decode(frame) as MeshFrame.CustomVars
        assertEquals(false, vars.gpsEnabled)
    }

    @Test
    fun `empty custom vars reply is not a crash`() {
        val vars = MeshCoreProtocol.decode(byteArrayOf(21)) as MeshFrame.CustomVars
        assertTrue(vars.values.isEmpty())
    }

    // ---------------- identity key ----------------

    @Test
    fun `export private key is a single byte command`() {
        assertArrayEquals(byteArrayOf(23), MeshCoreProtocol.encodeExportPrivateKey())
    }

    @Test
    fun `private key reply carries 64 bytes`() {
        val frame = ByteArray(65).also { it[0] = 14; for (i in 1..64) it[i] = i.toByte() }
        val key = MeshCoreProtocol.decode(frame) as MeshFrame.PrivateKey
        assertEquals(64, key.keyPair.size)
        assertEquals(1, key.keyPair[0].toInt())
    }

    @Test
    fun `import private key requires exactly 64 bytes`() {
        val frame = MeshCoreProtocol.encodeImportPrivateKey(ByteArray(64) { 7 })
        assertEquals(65, frame.size) // firmware guard: len >= 65
        assertEquals(24, frame[0].toInt())
        assertThrows(IllegalArgumentException::class.java) {
            MeshCoreProtocol.encodeImportPrivateKey(ByteArray(32))
        }
    }

    @Test
    fun `a firmware built without key export answers DISABLED`() {
        assertTrue(MeshCoreProtocol.decode(byteArrayOf(15)) is MeshFrame.Disabled)
    }

    // ---------------- config import writes ----------------

    @Test
    fun `set channel is a 50 byte frame with a 16 byte secret`() {
        // Firmware guard: len >= 2 + 32 + 16. The 32-byte-secret form is rejected.
        val secret = ByteArray(16) { 0x7F }
        val frame = MeshCoreProtocol.encodeSetChannel(1, "Public", secret)
        assertEquals(50, frame.size)
        assertEquals(32, frame[0].toInt())
        assertEquals(1, frame[1].toInt())
        assertEquals("Public", String(frame, 2, 6))
        assertEquals(0, frame[8].toInt()) // null padded
        assertArrayEquals(secret, frame.copyOfRange(34, 50))
    }

    @Test
    fun `set channel rejects a secret of the wrong size`() {
        assertThrows(IllegalArgumentException::class.java) {
            MeshCoreProtocol.encodeSetChannel(0, "x", ByteArray(32))
        }
    }

    @Test
    fun `add update contact mirrors the 148 byte contact frame`() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val path = byteArrayOf(0xF2.toByte(), 0x5B)
        val frame = MeshCoreProtocol.encodeAddUpdateContact(
            publicKey = key,
            type = 2,
            flags = 1,
            outPathLen = 2,
            outPath = path,
            name = "Repeater",
            lastAdvertEpochSec = 1000,
            latE6 = 48_707_708,
            lonE6 = -21_216_309,
            lastModEpochSec = 2000,
        )
        assertEquals(MeshCoreProtocol.CONTACT_FRAME_SIZE, frame.size)
        assertEquals(9, frame[0].toInt())
        assertArrayEquals(key, frame.copyOfRange(1, 33))
        assertEquals(2, frame[33].toInt()) // type
        assertEquals(1, frame[34].toInt()) // flags
        assertEquals(2, frame[35].toInt()) // out_path_len
        assertArrayEquals(path, frame.copyOfRange(36, 38))
        assertEquals("Repeater", String(frame, 100, 8))

        // The reply layout and this command layout must agree, so a contact read
        // from one node can be written straight to another.
        val asContactFrame = frame.copyOf().also { it[0] = 3 }
        val decoded = MeshCoreProtocol.decode(asContactFrame) as MeshFrame.Contact
        assertEquals("Repeater", decoded.name)
        assertEquals(2, decoded.outPathLen)
        assertEquals(48_707_708, decoded.latE6)
        assertEquals(-21_216_309, decoded.lonE6)
        assertEquals(2000L, decoded.lastModEpochSec)
    }

    @Test
    fun `add update contact rejects a bad public key length`() {
        assertThrows(IllegalArgumentException::class.java) {
            MeshCoreProtocol.encodeAddUpdateContact(
                publicKey = ByteArray(16),
                type = 1, flags = 0, outPathLen = -1, outPath = ByteArray(0),
                name = "x", lastAdvertEpochSec = 0, latE6 = 0, lonE6 = 0, lastModEpochSec = 0,
            )
        }
    }

    // ---------------- device info additions ----------------

    @Test
    fun `device info exposes path hash mode from byte 81`() {
        val frame = ByteArray(82)
        frame[0] = 13; frame[1] = 10; frame[2] = 50; frame[3] = 8
        frame[80] = 0 // client repeat
        frame[81] = 2 // path hash mode
        val info = MeshCoreProtocol.decode(frame) as MeshFrame.DeviceInfo
        assertEquals(2, info.pathHashMode)
    }

    @Test
    fun `older firmware without the path hash byte defaults to mode 0`() {
        val frame = ByteArray(80)
        frame[0] = 13; frame[1] = 10
        val info = MeshCoreProtocol.decode(frame) as MeshFrame.DeviceInfo
        assertEquals(0, info.pathHashMode)
        assertTrue(!info.clientRepeat)
    }
}
