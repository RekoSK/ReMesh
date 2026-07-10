package com.rekosk.remesh.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire formats for the four diagnostics, pinned against `MyMesh.cpp`. Nothing here
 * can be checked at runtime: a wrong offset would silently report the wrong number.
 */
class ToolCommandsTest {

    @Test
    fun `advert path is asked for by whole public key`() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val frame = MeshCoreProtocol.encodeGetAdvertPath(key)

        assertEquals(34, frame.size)
        assertEquals(MeshCoreProtocol.Cmd.GET_ADVERT_PATH.toByte(), frame[0])
        assertEquals(0.toByte(), frame[1]) // reserved
        assertArrayEquals(key, frame.copyOfRange(2, 34))
    }

    @Test
    fun `advert path reply carries a timestamp and an encoded path`() {
        // recv_timestamp = 0x6870_0000, path_len = (2-byte hashes | 2 hops), then 4 path bytes.
        val pathLen = (1 shl 6) or 2
        val frame = byteArrayOf(
            MeshCoreProtocol.Resp.ADVERT_PATH.toByte(),
            0x00, 0x00, 0x70, 0x68,
            pathLen.toByte(),
            0x11, 0x22, 0x33, 0x44,
        )
        val decoded = MeshCoreProtocol.decode(frame) as MeshFrame.AdvertPath

        assertEquals(0x6870_0000L, decoded.recvEpochSec)
        assertEquals(2, decoded.hops)
        assertEquals(2, decoded.hashSizeBytes)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), decoded.path)
    }

    @Test
    fun `an advert heard directly reports zero hops`() {
        val frame = byteArrayOf(MeshCoreProtocol.Resp.ADVERT_PATH.toByte(), 1, 0, 0, 0, 0)
        val decoded = MeshCoreProtocol.decode(frame) as MeshFrame.AdvertPath
        assertEquals(0, decoded.hops)
    }

    @Test
    fun `radio stats decode a negative noise floor`() {
        // noise_floor = -111 as int16 little-endian is 0x91 0xFF.
        val frame = byteArrayOf(
            MeshCoreProtocol.Resp.STATS.toByte(),
            MeshCoreProtocol.Stats.RADIO.toByte(),
            0x91.toByte(), 0xFF.toByte(),
            (-107).toByte(), // last rssi
            31, // last snr x4 -> 7.75
            0x10, 0x00, 0x00, 0x00, // tx air time 16s
            0x20, 0x00, 0x00, 0x00, // rx air time 32s
        )
        val decoded = MeshCoreProtocol.decode(frame) as MeshFrame.RadioStats

        assertEquals(-111, decoded.noiseFloorDbm)
        assertEquals(-107, decoded.lastRssi)
        assertEquals(7.75f, decoded.lastSnr, 0.0001f)
        assertEquals(16L, decoded.txAirTimeSec)
        assertEquals(32L, decoded.rxAirTimeSec)
    }

    @Test
    fun `the core and packet stats blocks are left alone`() {
        val core = byteArrayOf(MeshCoreProtocol.Resp.STATS.toByte(), 0, 1, 2, 3)
        assertTrue(MeshCoreProtocol.decode(core) is MeshFrame.Unhandled)
    }

    @Test
    fun `a node discovery request asks every node type for a full key`() {
        val frame = MeshCoreProtocol.encodeNodeDiscoverRequest(tag = 0x0102_0304)

        assertEquals(MeshCoreProtocol.Cmd.SEND_CONTROL_DATA.toByte(), frame[0])
        assertEquals(MeshCoreProtocol.NodeDiscovery.REQUEST.toByte(), frame[1])
        // Bit 0 clear: we want the whole 32-byte key back, not an 8-byte prefix.
        assertEquals(0, frame[1].toInt() and MeshCoreProtocol.NodeDiscovery.FLAG_PREFIX_ONLY)
        assertEquals(0x1E.toByte(), frame[2]) // chat | repeater | room | sensor
        // Tag, little-endian.
        assertArrayEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01), frame.copyOfRange(3, 7))
    }

    @Test
    fun `a discovery response is recognised by its top nibble`() {
        assertTrue(MeshCoreProtocol.NodeDiscovery.isResponse(0x92))
        assertEquals(2, MeshCoreProtocol.NodeDiscovery.nodeTypeOf(0x92))
        // A request is not a response.
        assertTrue(!MeshCoreProtocol.NodeDiscovery.isResponse(0x80))
    }

    @Test
    fun `control data separates the two SNR directions`() {
        val frame = byteArrayOf(
            MeshCoreProtocol.Push.CONTROL_DATA.toByte(),
            31, // we heard them at 7.75 dB
            (-100).toByte(),
            0, // path len
            0x92.toByte(), (-20).toByte(), 1, 0, 0, 0, // resp|repeater, they heard us at -5 dB
        )
        val decoded = MeshCoreProtocol.decode(frame) as MeshFrame.ControlData

        assertEquals(7.75f, decoded.snr, 0.0001f)
        assertEquals(-100, decoded.rssi)
        assertEquals(0x92.toByte(), decoded.payload[0])
        assertEquals(-5.0f, decoded.payload[1].toInt() / 4.0f, 0.0001f)
    }

    @Test
    fun `a trace path command packs the hash size into its flags`() {
        val oneByte = MeshCoreProtocol.encodeSendTracePath(1, 0, byteArrayOf(0x5B, 0x63), 1)
        assertEquals(MeshCoreProtocol.Cmd.SEND_TRACE_PATH.toByte(), oneByte[0])
        assertEquals(0.toByte(), oneByte[9]) // shift 0 -> 1-byte hashes
        assertEquals(12, oneByte.size)

        val twoByte = MeshCoreProtocol.encodeSendTracePath(1, 0, byteArrayOf(1, 2, 3, 4), 2)
        assertEquals(1.toByte(), twoByte[9]) // shift 1 -> 2-byte hashes
    }

    @Test
    fun `a trace path rejects a partial repeater hash`() {
        // Three bytes cannot be a whole number of 2-byte hashes.
        assertTrue(
            runCatching {
                MeshCoreProtocol.encodeSendTracePath(1, 0, byteArrayOf(1, 2, 3), 2)
            }.isFailure,
        )
        assertTrue(
            runCatching { MeshCoreProtocol.encodeSendTracePath(1, 0, ByteArray(0), 1) }.isFailure,
        )
    }

    @Test
    fun `trace data reports one SNR per hop plus our own`() {
        // 2 hops, 1-byte hashes: path_len = 2, flags = 0.
        val frame = byteArrayOf(
            MeshCoreProtocol.Push.TRACE_DATA.toByte(),
            0, // reserved
            2, // path_len in bytes
            0, // flags: shift 0
            0x04, 0x03, 0x02, 0x01, // tag
            0, 0, 0, 0, // auth
            0x5B, 0x63, // hashes
            31, (-20).toByte(), // per-hop SNR
            12, // final SNR at this node
        )
        val decoded = MeshCoreProtocol.decode(frame) as MeshFrame.TraceData

        assertEquals(0x0102_0304, decoded.tag)
        assertEquals(2, decoded.hops)
        assertEquals(1, decoded.hashSizeBytes)
        assertArrayEquals(byteArrayOf(0x5B), decoded.hashAt(0))
        assertArrayEquals(byteArrayOf(0x63), decoded.hashAt(1))
        assertEquals(listOf(7.75f, -5.0f), decoded.hopSnrs)
        assertEquals(3.0f, decoded.finalSnr, 0.0001f)
    }

    @Test
    fun `a two-byte trace halves the SNR count`() {
        val frame = byteArrayOf(
            MeshCoreProtocol.Push.TRACE_DATA.toByte(),
            0,
            4, // 4 bytes of hashes
            1, // shift 1 -> 2-byte hashes -> 2 hops
            1, 0, 0, 0,
            0, 0, 0, 0,
            0x11, 0x22, 0x33, 0x44,
            4, 8, // two SNRs
            16,
        )
        val decoded = MeshCoreProtocol.decode(frame) as MeshFrame.TraceData

        assertEquals(2, decoded.hops)
        assertArrayEquals(byteArrayOf(0x33, 0x44), decoded.hashAt(1))
        assertEquals(4.0f, decoded.finalSnr, 0.0001f)
    }

    @Test
    fun `a truncated trace frame is malformed, not misread`() {
        val frame = byteArrayOf(
            MeshCoreProtocol.Push.TRACE_DATA.toByte(), 0, 4, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0x11,
        )
        assertTrue(MeshCoreProtocol.decode(frame) is MeshFrame.Malformed)
    }
}
