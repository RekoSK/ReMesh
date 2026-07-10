package com.rekosk.remesh.ui

import com.rekosk.remesh.data.model.MeshMessage
import com.rekosk.remesh.ui.components.formatMessageTime
import com.rekosk.remesh.ui.screens.formatSnr
import com.rekosk.remesh.ui.screens.heardRepeatsSummary
import com.rekosk.remesh.ui.screens.incomingFooterText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

class MessageFooterTest {

    private val originalLocale: Locale = Locale.getDefault()
    private val originalZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreDefaults() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalZone)
    }

    private fun epochMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun incoming(
        hops: Int = 0,
        hashSize: Int? = null,
        direct: Boolean = false,
        stamp: Long = 0L,
    ) = MeshMessage(
        id = "m",
        author = "NEO",
        text = "Dobre ranko",
        timestampEpochMs = stamp,
        isOutgoing = false,
        hopCount = hops,
        pathHashSizeBytes = hashSize,
        isDirectRoute = direct,
    )

    @Test
    fun `footer reads time, hops and path hash size`() {
        val stamp = epochMs(LocalDate.now().year, LocalDate.now().monthValue, LocalDate.now().dayOfMonth, 6, 20)
        val footer = incomingFooterText(incoming(hops = 6, hashSize = 2, stamp = stamp))
        assertEquals("06:20 • 6 Hops • 2-byte", footer)
    }

    @Test
    fun `a single hop is singular`() {
        assertTrue(incomingFooterText(incoming(hops = 1, hashSize = 1)).contains("1 Hop •"))
    }

    @Test
    fun `a direct route says so instead of showing zero hops`() {
        val footer = incomingFooterText(incoming(direct = true, hashSize = 1))
        assertTrue(footer.contains("Direct"))
        assertTrue(!footer.contains("Hops"))
    }

    @Test
    fun `an unknown hop count is simply omitted`() {
        assertEquals(1, incomingFooterText(incoming()).split("•").size)
    }

    @Test
    fun `the message settings can hide hops and the path hash size`() {
        val message = incoming(hops = 6, hashSize = 2)
        assertTrue(incomingFooterText(message, showHops = false).contains("2-byte"))
        assertTrue(!incomingFooterText(message, showHops = false).contains("Hops"))
        assertTrue(incomingFooterText(message, showHashSize = false).contains("6 Hops"))
        assertTrue(!incomingFooterText(message, showHashSize = false).contains("2-byte"))
        // Both off leaves just the time, with no stray separator.
        assertEquals(
            1,
            incomingFooterText(message, showHops = false, showHashSize = false).split("•").size,
        )
    }

    @Test
    fun `hiding hops also hides the Direct marker, which is the same field`() {
        val message = incoming(direct = true, hashSize = 1)
        assertTrue(!incomingFooterText(message, showHops = false).contains("Direct"))
    }

    @Test
    fun `yesterday's messages carry their date`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Bratislava"))
        val stamp = epochMs(2026, 7, 9, 20, 30)
        assertEquals("09/Jul 20:30", formatMessageTime(stamp, today = LocalDate.of(2026, 7, 10)))
        assertEquals("20:30", formatMessageTime(stamp, today = LocalDate.of(2026, 7, 9)))
    }

    @Test
    fun `the month abbreviation stays English on a Slovak phone`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Bratislava"))
        Locale.setDefault(Locale.forLanguageTag("sk-SK"))
        val stamp = epochMs(2026, 7, 9, 20, 30)
        assertEquals("09/Jul 20:30", formatMessageTime(stamp, today = LocalDate.of(2026, 7, 10)))
    }

    @Test
    fun `snr drops trailing zeros but keeps real precision`() {
        assertEquals("7.75 dB", formatSnr(7.75f))
        assertEquals("-8.5 dB", formatSnr(-8.5f))
        assertEquals("-0.5 dB", formatSnr(-0.5f))
        assertEquals("0 dB", formatSnr(0f))
        assertEquals("3.75 dB", formatSnr(3.75f))
    }

    @Test
    fun `snr stays dot-separated in a comma-decimal locale`() {
        Locale.setDefault(Locale.forLanguageTag("sk-SK"))
        assertEquals("7.75 dB", formatSnr(7.75f))
    }

    @Test
    fun `the heard-repeats phrase is pluralised`() {
        assertEquals("No repeats heard", heardRepeatsSummary(0))
        assertEquals("Heard 1 repeat", heardRepeatsSummary(1))
        assertEquals("Heard 3 repeats", heardRepeatsSummary(3))
    }
}
