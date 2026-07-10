package com.rekosk.remesh.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LOG_RX_DATA push carries a packet exactly as `Dispatcher::tryParsePacket`
 * would read it. Getting the path wrong would attribute a repeat to the wrong node,
 * so every field boundary is pinned here.
 */
class RawPacketTest {

    /** header = route | type << 2 | version << 6 */
    private fun header(route: Int, type: Int): Byte = (route or (type shl 2)).toByte()

    private val floodGroupText = header(
        route = MeshCoreProtocol.PacketHeader.ROUTE_FLOOD,
        type = MeshCoreProtocol.PacketHeader.PAYLOAD_TYPE_GRP_TXT,
    )

    @Test
    fun `path length packs hash size above hop count`() {
        // 2-byte hashes, 6 hops: (2-1) << 6 | 6
        val pathLen = (1 shl 6) or 6
        assertEquals(6, MeshCoreProtocol.PathInfo.hops(pathLen))
        assertEquals(2, MeshCoreProtocol.PathInfo.hashSizeBytes(pathLen))
        assertFalse(MeshCoreProtocol.PathInfo.isDirect(pathLen))
    }

    @Test
    fun `a plain hop count still reads as one-byte hashes`() {
        assertEquals(4, MeshCoreProtocol.PathInfo.hops(4))
        assertEquals(1, MeshCoreProtocol.PathInfo.hashSizeBytes(4))
    }

    @Test
    fun `0xFF means the packet arrived by a direct route`() {
        assertTrue(MeshCoreProtocol.PathInfo.isDirect(0xFF))
        assertEquals(0, MeshCoreProtocol.PathInfo.hops(0xFF))
    }

    @Test
    fun `a one-hop flood packet yields the repeater's hash`() {
        val raw = byteArrayOf(floodGroupText, 1, 0x89.toByte(), 0x11, 0x22, 0x33)
        val packet = MeshCoreProtocol.parseRawPacket(raw)!!

        assertTrue(packet.isFlood)
        assertEquals(MeshCoreProtocol.PacketHeader.PAYLOAD_TYPE_GRP_TXT, packet.payloadType)
        assertEquals(1, packet.hops)
        assertEquals(1, packet.hashSizeBytes)
        assertArrayEquals(byteArrayOf(0x89.toByte()), packet.lastPathHash())
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), packet.payload)
    }

    @Test
    fun `the last path entry is the node that transmitted the copy we heard`() {
        // Two hops, 1-byte hashes: originator -> 0x5B -> 0x63, heard from 0x63.
        val raw = byteArrayOf(floodGroupText, 2, 0x5B, 0x63, 0x77)
        val packet = MeshCoreProtocol.parseRawPacket(raw)!!
        assertArrayEquals(byteArrayOf(0x63), packet.lastPathHash())
    }

    @Test
    fun `a two-byte hash mode consumes two bytes per hop`() {
        val pathLen = ((2 - 1) shl 6) or 2
        val raw = byteArrayOf(floodGroupText, pathLen.toByte(), 0x11, 0x22, 0x33, 0x44, 0x99.toByte())
        val packet = MeshCoreProtocol.parseRawPacket(raw)!!

        assertEquals(2, packet.hops)
        assertEquals(2, packet.hashSizeBytes)
        assertArrayEquals(byteArrayOf(0x33, 0x44), packet.lastPathHash())
        assertArrayEquals(byteArrayOf(0x99.toByte()), packet.payload)
    }

    @Test
    fun `a packet with no path was heard straight from its originator`() {
        val raw = byteArrayOf(floodGroupText, 0, 0x11, 0x22)
        val packet = MeshCoreProtocol.parseRawPacket(raw)!!
        assertNull(packet.lastPathHash())
    }

    @Test
    fun `transport-routed packets skip four code bytes before the path`() {
        val transportFlood = header(
            route = MeshCoreProtocol.PacketHeader.ROUTE_TRANSPORT_FLOOD,
            type = MeshCoreProtocol.PacketHeader.PAYLOAD_TYPE_GRP_TXT,
        )
        val raw = byteArrayOf(transportFlood, 1, 2, 3, 4, /* pathLen */ 1, 0x42, 0xAB.toByte())
        val packet = MeshCoreProtocol.parseRawPacket(raw)!!

        assertTrue(packet.isFlood)
        assertArrayEquals(byteArrayOf(0x42), packet.lastPathHash())
        assertArrayEquals(byteArrayOf(0xAB.toByte()), packet.payload)
    }

    @Test
    fun `direct packets are not flood packets`() {
        val direct = header(
            route = MeshCoreProtocol.PacketHeader.ROUTE_DIRECT,
            type = MeshCoreProtocol.PacketHeader.PAYLOAD_TYPE_GRP_TXT,
        )
        val packet = MeshCoreProtocol.parseRawPacket(byteArrayOf(direct, 0, 1))!!
        assertFalse(packet.isFlood)
    }

    @Test
    fun `malformed packets are dropped rather than guessed at`() {
        assertNull(MeshCoreProtocol.parseRawPacket(ByteArray(0)))
        // A truncated path.
        assertNull(MeshCoreProtocol.parseRawPacket(byteArrayOf(floodGroupText, 3, 0x11)))
        // Header version 1, which the firmware refuses.
        assertNull(MeshCoreProtocol.parseRawPacket(byteArrayOf(0x40, 0, 1)))
        // Path hash mode 3 is reserved.
        assertNull(MeshCoreProtocol.parseRawPacket(byteArrayOf(floodGroupText, 0xC1.toByte(), 1)))
        // Transport codes announced but absent.
        val transportFlood = header(MeshCoreProtocol.PacketHeader.ROUTE_TRANSPORT_FLOOD, 5)
        assertNull(MeshCoreProtocol.parseRawPacket(byteArrayOf(transportFlood, 1, 2)))
    }

    @Test
    fun `LOG_RX_DATA decodes snr, rssi and the packet behind them`() {
        val frame = byteArrayOf(
            MeshCoreProtocol.Push.LOG_RX_DATA.toByte(),
            31, // snr x4  ->  7.75 dB
            (-107).toByte(), // rssi dBm
            floodGroupText, 1, 0x89.toByte(), 0xEE.toByte(),
        )
        val decoded = MeshCoreProtocol.decode(frame) as MeshFrame.LogRxData

        assertEquals(7.75f, decoded.snr, 0.0001f)
        assertEquals(-107, decoded.rssi)
        assertArrayEquals(byteArrayOf(0x89.toByte()), decoded.packet()!!.lastPathHash())
    }

    @Test
    fun `a negative snr survives the round trip`() {
        // -8.5 dB is transmitted as -34 quarter-decibels.
        val frame = byteArrayOf(
            MeshCoreProtocol.Push.LOG_RX_DATA.toByte(),
            (-34).toByte(),
            (-120).toByte(),
            floodGroupText, 0, 0x01,
        )
        val decoded = MeshCoreProtocol.decode(frame) as MeshFrame.LogRxData
        assertEquals(-8.5f, decoded.snr, 0.0001f)
    }
}
