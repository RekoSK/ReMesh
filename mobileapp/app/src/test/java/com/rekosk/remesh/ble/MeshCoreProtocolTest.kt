package com.rekosk.remesh.ble

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Frame layouts here are transcribed from the ReCore firmware
 * (`examples/companion_radio/MyMesh.cpp`), so these tests pin the wire format,
 * not merely the code's own idea of it.
 */
class MeshCoreProtocolTest {

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun `app start reserves seven bytes before the app name`() {
        val frame = MeshCoreProtocol.encodeAppStart("remesh")
        assertEquals(1, frame[0].toInt())
        for (i in 1..7) assertEquals("byte $i must be reserved/zero", 0, frame[i].toInt())
        assertEquals("remesh", String(frame, 8, frame.size - 8))
        // Firmware guard is `len >= 8`.
        assertTrue(frame.size >= 8)
    }

    @Test
    fun `device query carries the app protocol version`() {
        assertArrayEquals(byteArrayOf(22, 3), MeshCoreProtocol.encodeDeviceQuery(3))
    }

    @Test
    fun `set device time is little endian`() {
        val frame = MeshCoreProtocol.encodeSetDeviceTime(0x499602D2L)
        assertArrayEquals(
            byteArrayOf(6, 0xD2.toByte(), 0x02, 0x96.toByte(), 0x49),
            frame,
        )
    }

    @Test
    fun `get contacts omits since when zero and appends it otherwise`() {
        assertArrayEquals(byteArrayOf(4), MeshCoreProtocol.encodeGetContacts(0))
        val withSince = MeshCoreProtocol.encodeGetContacts(1)
        assertEquals(5, withSince.size)
        assertEquals(1, withSince[1].toInt())
    }

    @Test
    fun `send text message matches firmware offsets`() {
        val prefix = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x11, 0x22, 0x33)
        val frame = MeshCoreProtocol.encodeSendTextMessage(
            pubKeyPrefix = prefix,
            text = "Hi",
            epochSeconds = 0x499602D2L,
        )
        // [0]=2 [1]=txt_type [2]=attempt [3..6]=ts [7..12]=prefix [13..]=text
        assertEquals(2, frame[0].toInt())
        assertEquals(0, frame[1].toInt())
        assertEquals(0, frame[2].toInt())
        assertArrayEquals(
            byteArrayOf(0xD2.toByte(), 0x02, 0x96.toByte(), 0x49),
            frame.copyOfRange(3, 7),
        )
        assertArrayEquals(prefix, frame.copyOfRange(7, 13))
        assertEquals("Hi", String(frame, 13, frame.size - 13))
        // Firmware guard is `len >= 14`.
        assertTrue(frame.size >= 14)
    }

    @Test
    fun `send channel text message matches firmware offsets`() {
        val frame = MeshCoreProtocol.encodeSendChannelTextMessage(1, "Hello", 0x499602D2L)
        assertEquals(3, frame[0].toInt())
        assertEquals(0, frame[1].toInt())
        assertEquals(1, frame[2].toInt())
        assertEquals("Hello", String(frame, 7, frame.size - 7))
    }

    @Test
    fun `long text is cut on a codepoint boundary`() {
        // "é" is two UTF-8 bytes; 133 of them would split the 67th char in half.
        val frame = MeshCoreProtocol.encodeSendChannelTextMessage(0, "é".repeat(80), 0)
        val body = frame.copyOfRange(7, frame.size)
        assertTrue("must not exceed the firmware text cap", body.size <= MeshCoreProtocol.MAX_TEXT_LEN)
        // Decodes cleanly => we did not slice a multi-byte char.
        val text = String(body, Charsets.UTF_8)
        assertTrue(text.all { it == 'é' })
        assertEquals(66, text.length)
    }

    @Test
    fun `whole frame stays within the firmware frame cap`() {
        val frame = MeshCoreProtocol.encodeSendChannelTextMessage(0, "x".repeat(500), 0)
        assertTrue(frame.size <= MeshCoreProtocol.MAX_FRAME_SIZE)
    }

    // ---------------- decoding ----------------

    /** Rebuilds `writeContactRespFrame()` byte for byte. */
    private fun contactFrame(
        name: String,
        type: Int,
        outPathLen: Int,
        lastAdvert: Long = 1000,
        lastMod: Long = 2000,
        latE6: Int = 48_148_598,
        lonE6: Int = 17_107_748,
    ): ByteArray {
        val b = le(MeshCoreProtocol.CONTACT_FRAME_SIZE)
        b.put(MeshCoreProtocol.Resp.CONTACT.toByte())
        b.put(ByteArray(MeshCoreProtocol.PUB_KEY_SIZE) { (it + 1).toByte() })
        b.put(type.toByte())
        b.put(0) // flags
        b.put(outPathLen.toByte())
        b.put(ByteArray(MeshCoreProtocol.MAX_PATH_SIZE))
        val nameField = ByteArray(32)
        name.toByteArray().copyInto(nameField)
        b.put(nameField)
        b.putInt(lastAdvert.toInt())
        b.putInt(latE6)
        b.putInt(lonE6)
        b.putInt(lastMod.toInt())
        return b.array()
    }

    @Test
    fun `contact frame decodes name type and path`() {
        val frame = contactFrame("Slope 7ave #2", MeshCoreProtocol.AdvType.REPEATER, 4)
        assertEquals(MeshCoreProtocol.CONTACT_FRAME_SIZE, frame.size)

        val decoded = MeshCoreProtocol.decode(frame) as MeshFrame.Contact
        assertEquals("Slope 7ave #2", decoded.name)
        assertEquals(MeshCoreProtocol.AdvType.REPEATER, decoded.type)
        assertEquals(4, decoded.outPathLen)
        assertEquals(48_148_598, decoded.latE6)
        assertEquals(2000L, decoded.lastModEpochSec)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), decoded.keyPrefix)
    }

    @Test
    fun `out path len of minus one means flood`() {
        val decoded = MeshCoreProtocol.decode(
            contactFrame("N0RSR", MeshCoreProtocol.AdvType.CHAT, -1),
        ) as MeshFrame.Contact
        // Signed, not 255: the firmware field is int8_t.
        assertEquals(-1, decoded.outPathLen)
    }

    @Test
    fun `short contact frame is reported malformed rather than crashing`() {
        val decoded = MeshCoreProtocol.decode(byteArrayOf(MeshCoreProtocol.Resp.CONTACT.toByte(), 1, 2))
        assertTrue(decoded is MeshFrame.Malformed)
    }

    @Test
    fun `contacts start and end carry counters`() {
        val start = le(5).put(2).putInt(63).array()
        assertEquals(63L, (MeshCoreProtocol.decode(start) as MeshFrame.ContactsStart).totalContacts)

        val end = le(5).put(4).putInt(4242).array()
        assertEquals(4242L, (MeshCoreProtocol.decode(end) as MeshFrame.EndOfContacts).mostRecentLastMod)
    }

    @Test
    fun `channel info exposes name secret and emptiness`() {
        val b = le(50)
        b.put(18)
        b.put(1)
        val name = ByteArray(32); "Not Public".toByteArray().copyInto(name)
        b.put(name)
        b.put(ByteArray(16) { 0x7F })
        val info = MeshCoreProtocol.decode(b.array()) as MeshFrame.ChannelInfo
        assertEquals(1, info.index)
        assertEquals("Not Public", info.name)
        assertEquals(16, info.secret.size)
        assertTrue(!info.isEmpty)
    }

    @Test
    fun `empty channel slot is detected`() {
        val b = le(50).put(18).put(3).put(ByteArray(32)).put(ByteArray(16))
        assertTrue((MeshCoreProtocol.decode(b.array()) as MeshFrame.ChannelInfo).isEmpty)
    }

    @Test
    fun `v3 contact message skips snr and reserved bytes`() {
        val b = le(64)
        b.put(MeshCoreProtocol.Resp.CONTACT_MSG_RECV_V3.toByte())
        b.put((-40).toByte()) // snr * 4  => -10 dB
        b.put(0); b.put(0) // reserved
        b.put(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 1, 2, 3))
        b.put(2) // path len
        b.put(MeshCoreProtocol.TxtType.PLAIN.toByte())
        b.putInt(1234567890)
        b.put("hello".toByteArray())
        val frame = b.array().copyOfRange(0, b.position())

        val msg = MeshCoreProtocol.decode(frame) as MeshFrame.ContactMessage
        assertEquals("hello", msg.text)
        assertEquals(1234567890L, msg.timestampEpochSec)
        assertEquals(2, msg.pathLen)
        assertEquals(-10.0f, msg.snr!!, 0.001f)
    }

    @Test
    fun `signed contact message skips the four byte signature`() {
        val b = le(64)
        b.put(MeshCoreProtocol.Resp.CONTACT_MSG_RECV.toByte())
        b.put(byteArrayOf(1, 2, 3, 4, 5, 6))
        b.put(0)
        b.put(MeshCoreProtocol.TxtType.SIGNED_PLAIN.toByte())
        b.putInt(99)
        b.put(byteArrayOf(9, 9, 9, 9)) // signature
        b.put("signed".toByteArray())
        val frame = b.array().copyOfRange(0, b.position())

        val msg = MeshCoreProtocol.decode(frame) as MeshFrame.ContactMessage
        assertEquals("signed", msg.text)
        assertNull(msg.snr)
    }

    @Test
    fun `v3 channel message decodes`() {
        val b = le(64)
        b.put(MeshCoreProtocol.Resp.CHANNEL_MSG_RECV_V3.toByte())
        b.put(20) // snr*4 => 5 dB
        b.put(0); b.put(0)
        b.put(1) // channel idx
        b.put(0xFF.toByte()) // path len
        b.put(MeshCoreProtocol.TxtType.PLAIN.toByte())
        b.putInt(7)
        b.put("Evening all".toByteArray())
        val frame = b.array().copyOfRange(0, b.position())

        val msg = MeshCoreProtocol.decode(frame) as MeshFrame.ChannelMessage
        assertEquals(1, msg.channelIndex)
        assertEquals("Evening all", msg.text)
        assertEquals(5.0f, msg.snr!!, 0.001f)
    }

    @Test
    fun `sent frame reports flood and expected ack`() {
        val b = le(10).put(6).put(1).putInt(0xDEADBEEF.toInt()).putInt(3000)
        val sent = MeshCoreProtocol.decode(b.array()) as MeshFrame.Sent
        assertTrue(sent.flood)
        assertEquals(3000L, sent.suggestedTimeoutMs)
    }

    @Test
    fun `channel send is acknowledged with OK not SENT`() {
        // The firmware calls writeOKFrame() for CMD_SEND_CHANNEL_TXT_MSG, even though
        // companion_protocol.md claims PACKET_MSG_SENT. Firmware wins.
        assertTrue(MeshCoreProtocol.decode(byteArrayOf(0)) is MeshFrame.Ok)
    }

    @Test
    fun `error frame carries a readable code`() {
        val err = MeshCoreProtocol.decode(byteArrayOf(1, 3)) as MeshFrame.Error
        assertEquals(3, err.errorCode)
        assertTrue(err.message.contains("full", ignoreCase = true))
    }

    @Test
    fun `messages waiting is recognised as a push`() {
        assertTrue(MeshCoreProtocol.decode(byteArrayOf(0x83.toByte())) is MeshFrame.MessagesWaiting)
        assertTrue(MeshCoreProtocol.isPush(0x83))
        assertTrue(!MeshCoreProtocol.isPush(0x06))
    }

    @Test
    fun `device info parses model and ble pin`() {
        val b = le(80)
        b.put(13); b.put(10) // code, fw ver
        b.put(50) // max contacts / 2
        b.put(8) // max channels
        b.putInt(123456) // ble pin
        val build = ByteArray(12); "20260709".toByteArray().copyInto(build); b.put(build)
        val model = ByteArray(40); "ESP32C6_SX1276_SH1106".toByteArray().copyInto(model); b.put(model)
        val ver = ByteArray(20); "Re16".toByteArray().copyInto(ver); b.put(ver)

        val info = MeshCoreProtocol.decode(b.array()) as MeshFrame.DeviceInfo
        assertEquals(100, info.maxContacts)
        assertEquals(8, info.maxChannels)
        assertEquals(123456L, info.blePin)
        assertEquals("ESP32C6_SX1276_SH1106", info.model)
        assertEquals("Re16", info.version)
    }

    @Test
    fun `battery and storage frame decodes kilobytes`() {
        val b = le(11).put(12).putShort(3900).putInt(24).putInt(110)
        val batt = MeshCoreProtocol.decode(b.array()) as MeshFrame.Battery
        assertEquals(3900, batt.millivolts)
        assertEquals(24L, batt.usedKb)
        assertEquals(110L, batt.totalKb)
        assertEquals(21, batt.usedPercent())
        // Legacy 11-byte reply carries no charge flag.
        assertNull(batt.charging)
    }

    @Test
    fun `battery frame reads the optional charge flag`() {
        val charging = le(12).put(12).putShort(3900).putInt(24).putInt(110).put(1)
        assertEquals(true, (MeshCoreProtocol.decode(charging.array()) as MeshFrame.Battery).charging)

        val onBattery = le(12).put(12).putShort(3900).putInt(24).putInt(110).put(0)
        assertEquals(false, (MeshCoreProtocol.decode(onBattery.array()) as MeshFrame.Battery).charging)
    }

    @Test
    fun `battery percent uses the same curve as the node's own display`() {
        // UITask.cpp: (mV - 3000) * 100 / 1200, clamped. 3580 mV is the 48% in the
        // reference app's telemetry screen.
        assertEquals(48, MeshFrame.Battery(3580, 0, 0).batteryPercent())
        assertEquals(3.58, MeshFrame.Battery(3580, 0, 0).volts(), 0.0001)
        assertEquals(0, MeshFrame.Battery(3000, 0, 0).batteryPercent())
        assertEquals(100, MeshFrame.Battery(4200, 0, 0).batteryPercent())
    }

    @Test
    fun `battery percent clamps outside the curve`() {
        assertEquals(0, MeshFrame.Battery(2500, 0, 0).batteryPercent())
        assertEquals(100, MeshFrame.Battery(4500, 0, 0).batteryPercent())
    }

    @Test
    fun `storage meter survives a node reporting zero total`() {
        val batt = MeshFrame.Battery(millivolts = 0, usedKb = 5, totalKb = 0)
        assertEquals(0f, batt.usedFraction(), 0.0001f)
        assertEquals(0, batt.usedPercent())
    }

    @Test
    fun `storage meter clamps a used value larger than total`() {
        val batt = MeshFrame.Battery(millivolts = 0, usedKb = 200, totalKb = 110)
        assertEquals(1f, batt.usedFraction(), 0.0001f)
        assertEquals(100, batt.usedPercent())
    }

    /** Rebuilds the CMD_APP_START reply exactly as MyMesh.cpp writes it. */
    private fun selfInfoFrame(
        txPower: Int = 20,
        maxTxPower: Int = 22,
        advertLocPolicy: Int = 1,
        telemetryMode: Int = 0,
        manualAddContacts: Int = 0,
        multiAcks: Int = 1,
        freqKhz: Int = 869_618,
        bwHz: Int = 62_500,
        sf: Int = 8,
        cr: Int = 5,
        name: String = "Reko",
    ): ByteArray {
        val b = le(58 + name.toByteArray().size)
        b.put(5)
        b.put(1) // ADV_TYPE_CHAT
        b.put(txPower.toByte())
        b.put(maxTxPower.toByte())
        b.put(ByteArray(32) { (it + 1).toByte() })
        b.putInt(48_707_708) // lat * 1e6
        b.putInt(21_216_309) // lon * 1e6
        b.put(multiAcks.toByte())
        b.put(advertLocPolicy.toByte())
        b.put(telemetryMode.toByte())
        b.put(manualAddContacts.toByte())
        b.putInt(freqKhz)
        b.putInt(bwHz)
        b.put(sf.toByte())
        b.put(cr.toByte())
        b.put(name.toByteArray())
        return b.array()
    }

    @Test
    fun `self info decodes radio params and location policy`() {
        val info = MeshCoreProtocol.decode(selfInfoFrame()) as MeshFrame.SelfInfo
        assertEquals("Reko", info.name)
        assertEquals(48_707_708, info.latE6)
        assertEquals(21_216_309, info.lonE6)
        // Firmware sends MHz*1000 and kHz*1000.
        assertEquals(869_618L, info.radioFreqKhz)
        assertEquals(62_500L, info.radioBandwidthHz)
        assertEquals(8, info.spreadingFactor)
        assertEquals(5, info.codingRate)
        assertEquals(1, info.multiAcks)
        assertTrue(info.sharesLocation)
        assertEquals(22, info.txPowerCeiling)
    }

    @Test
    fun `advert loc policy of none means location is not shared`() {
        val info = MeshCoreProtocol.decode(selfInfoFrame(advertLocPolicy = 0)) as MeshFrame.SelfInfo
        assertTrue(!info.sharesLocation)
    }

    @Test
    fun `negative tx power survives the byte round trip`() {
        val info = MeshCoreProtocol.decode(selfInfoFrame(txPower = -9)) as MeshFrame.SelfInfo
        assertEquals(-9, info.txPower)
    }

    @Test
    fun `tx power ceiling falls back to 22 when the node reports nothing`() {
        val info = MeshCoreProtocol.decode(selfInfoFrame(maxTxPower = 0)) as MeshFrame.SelfInfo
        assertEquals(22, info.txPowerCeiling)
    }

    @Test
    fun `set advert name encodes the raw name after the opcode`() {
        val frame = MeshCoreProtocol.encodeSetAdvertName("Reko")
        assertEquals(8, frame[0].toInt())
        assertEquals("Reko", String(frame, 1, frame.size - 1))
        assertTrue(frame.size >= 2) // firmware guard
    }

    @Test
    fun `set advert latlon is two signed little endian ints`() {
        val frame = MeshCoreProtocol.encodeSetAdvertLatLon(48_707_708, -21_216_309)
        assertEquals(14, frame[0].toInt())
        assertEquals(9, frame.size)
        val b = java.nio.ByteBuffer.wrap(frame, 1, 8).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(48_707_708, b.int)
        assertEquals(-21_216_309, b.int)
    }

    @Test
    fun `set radio params always sends the client repeat byte`() {
        // Omitting it makes the firmware default client_repeat to 0, silently disabling it.
        val frame = MeshCoreProtocol.encodeSetRadioParams(869_618, 62_500, 8, 5, clientRepeat = true)
        assertEquals(12, frame.size)
        assertEquals(11, frame[0].toInt())
        val b = java.nio.ByteBuffer.wrap(frame, 1, 8).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(869_618, b.int)
        assertEquals(62_500, b.int)
        assertEquals(8, frame[9].toInt())
        assertEquals(5, frame[10].toInt())
        assertEquals(1, frame[11].toInt())
    }

    @Test
    fun `set radio tx power encodes negative values as int8`() {
        val frame = MeshCoreProtocol.encodeSetRadioTxPower(-9)
        assertEquals(12, frame[0].toInt())
        assertEquals(-9, frame[1].toInt())
    }

    @Test
    fun `set other params carries every preceding field`() {
        val frame = MeshCoreProtocol.encodeSetOtherParams(
            manualAddContacts = true,
            telemetryMode = 0x15,
            advertLocPolicy = MeshCoreProtocol.AdvertLoc.NONE,
            multiAcks = 1,
        )
        assertEquals(38, frame[0].toInt())
        assertEquals(1, frame[1].toInt())
        assertEquals(0x15, frame[2].toInt())
        assertEquals(0, frame[3].toInt())
        assertEquals(1, frame[4].toInt())
    }

    @Test
    fun `device info exposes client repeat so it can be echoed back`() {
        val b = le(82)
        b.put(13); b.put(10); b.put(50); b.put(8); b.putInt(123456)
        b.put(ByteArray(12)); b.put(ByteArray(40)); b.put(ByteArray(20))
        b.put(1) // client_repeat
        b.put(0) // path_hash_mode
        val info = MeshCoreProtocol.decode(b.array()) as MeshFrame.DeviceInfo
        assertTrue(info.clientRepeat)
    }

    @Test
    fun `self info differing only in a radio field is not equal`() {
        // A settings screen re-reads SELF_INFO after saving. If two SelfInfo values
        // that differ only in frequency compare equal, a StateFlow silently drops the
        // update and the screen keeps comparing against stale values.
        val a = MeshCoreProtocol.decode(selfInfoFrame(freqKhz = 869_618))
        val b = MeshCoreProtocol.decode(selfInfoFrame(freqKhz = 868_000))
        assertNotEquals(a, b)
    }

    @Test
    fun `self info differing only in tx power is not equal`() {
        val a = MeshCoreProtocol.decode(selfInfoFrame(txPower = 20))
        val b = MeshCoreProtocol.decode(selfInfoFrame(txPower = 14))
        assertNotEquals(a, b)
    }

    @Test
    fun `self info differing only in location policy is not equal`() {
        val a = MeshCoreProtocol.decode(selfInfoFrame(advertLocPolicy = 1))
        val b = MeshCoreProtocol.decode(selfInfoFrame(advertLocPolicy = 0))
        assertNotEquals(a, b)
    }

    @Test
    fun `a state flow actually publishes a changed self info`() {
        // This is the exact failure the settings screen hit: the new value is
        // conflated away and `.value` still reports the old frequency.
        val flow = MutableStateFlow(MeshCoreProtocol.decode(selfInfoFrame(freqKhz = 869_618)))
        flow.value = MeshCoreProtocol.decode(selfInfoFrame(freqKhz = 868_000))
        assertEquals(868_000L, (flow.value as MeshFrame.SelfInfo).radioFreqKhz)
    }

    @Test
    fun `contact differing only in name or last advert is not equal`() {
        val a = MeshCoreProtocol.decode(contactFrame("Old", MeshCoreProtocol.AdvType.CHAT, 1))
        val b = MeshCoreProtocol.decode(contactFrame("New", MeshCoreProtocol.AdvType.CHAT, 1))
        assertNotEquals(a, b)

        val c = MeshCoreProtocol.decode(contactFrame("Same", MeshCoreProtocol.AdvType.CHAT, 1, lastAdvert = 10))
        val d = MeshCoreProtocol.decode(contactFrame("Same", MeshCoreProtocol.AdvType.CHAT, 1, lastAdvert = 99))
        assertNotEquals(c, d)
    }

    @Test
    fun `channel info differing only in secret is not equal`() {
        fun frame(fill: Byte): ByteArray {
            val b = le(50)
            b.put(18); b.put(1)
            val name = ByteArray(32); "Chan".toByteArray().copyInto(name)
            b.put(name)
            b.put(ByteArray(16) { fill })
            return b.array()
        }
        assertNotEquals(MeshCoreProtocol.decode(frame(1)), MeshCoreProtocol.decode(frame(2)))
    }

    @Test
    fun `identical self info values remain equal`() {
        assertEquals(
            MeshCoreProtocol.decode(selfInfoFrame()),
            MeshCoreProtocol.decode(selfInfoFrame()),
        )
    }

    @Test
    fun `unknown codes are surfaced rather than dropped`() {
        // 0x87 is PUSH_CODE_STATUS_RESPONSE, which nothing in this app reads yet.
        val decoded = MeshCoreProtocol.decode(byteArrayOf(0x87.toByte(), 1, 2))
        assertTrue(decoded is MeshFrame.Unhandled)
        assertEquals(0x87, decoded.code)
    }

    @Test
    fun `empty frame does not throw`() {
        assertTrue(MeshCoreProtocol.decode(ByteArray(0)) is MeshFrame.Malformed)
    }
}
