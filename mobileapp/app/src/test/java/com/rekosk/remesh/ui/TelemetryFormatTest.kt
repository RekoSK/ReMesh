package com.rekosk.remesh.ui

import com.rekosk.remesh.ui.screens.settings.formatBattery
import com.rekosk.remesh.ui.screens.settings.formatPosition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The telemetry screen once crashed with UnknownFormatConversionException because
 * a literal `%` sat next to a format specifier. These strings are built with
 * String.format, so they need a test, not just a glance.
 */
class TelemetryFormatTest {

    private val original: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `battery renders percent and volts without throwing`() {
        assertEquals("48% / 3.58v", formatBattery(48, 3.58))
        assertEquals("0% / 2.90v", formatBattery(0, 2.9))
        assertEquals("100% / 4.20v", formatBattery(100, 4.2))
    }

    @Test
    fun `battery stays dot-separated in a comma-decimal locale`() {
        // Slovak formats decimals with a comma; the reference app shows "3.58v".
        Locale.setDefault(Locale.forLanguageTag("sk-SK"))
        assertEquals("48% / 3.58v", formatBattery(48, 3.58))
    }

    @Test
    fun `position renders four decimals`() {
        assertEquals("48.7077, 21.2163", formatPosition(48_707_708, 21_216_309))
        assertEquals("0.0000, 0.0000", formatPosition(0, 0))
        assertEquals("-48.7077, -21.2163", formatPosition(-48_707_708, -21_216_309))
    }

    @Test
    fun `position stays dot-separated in a comma-decimal locale`() {
        Locale.setDefault(Locale.forLanguageTag("sk-SK"))
        assertEquals("48.7077, 21.2163", formatPosition(48_707_708, 21_216_309))
    }
}
