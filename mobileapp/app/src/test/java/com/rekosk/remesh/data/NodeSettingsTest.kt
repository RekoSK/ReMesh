package com.rekosk.remesh.data

import com.rekosk.remesh.data.model.NodeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeSettingsTest {

    private val valid = NodeSettings(
        name = "Reko",
        latE6 = 48_707_708,
        lonE6 = 21_216_309,
        shareLocation = true,
        freqKhz = 869_618,
        bandwidthHz = 62_500,
        spreadingFactor = 8,
        codingRate = 5,
        txPowerDbm = 20,
    )

    @Test
    fun `a valid configuration passes`() {
        assertNull(valid.validate(txPowerCeiling = 22))
    }

    @Test
    fun `tx power above the board ceiling is rejected`() {
        // The firmware answers ERR_CODE_ILLEGAL_ARG; catch it before it hits the radio.
        assertNotNull(valid.copy(txPowerDbm = 23).validate(txPowerCeiling = 22))
        assertNull(valid.copy(txPowerDbm = 22).validate(txPowerCeiling = 22))
    }

    @Test
    fun `a 20 dBm board rejects 22 dBm`() {
        assertNotNull(valid.copy(txPowerDbm = 22).validate(txPowerCeiling = 20))
    }

    @Test
    fun `tx power below minus nine is rejected`() {
        assertNotNull(valid.copy(txPowerDbm = -10).validate(txPowerCeiling = 22))
        assertNull(valid.copy(txPowerDbm = -9).validate(txPowerCeiling = 22))
    }

    @Test
    fun `frequency outside the firmware window is rejected`() {
        assertNotNull(valid.copy(freqKhz = 149_999).validate(22))
        assertNotNull(valid.copy(freqKhz = 2_500_001).validate(22))
        assertNull(valid.copy(freqKhz = 150_000).validate(22))
    }

    @Test
    fun `bandwidth outside the firmware window is rejected`() {
        assertNotNull(valid.copy(bandwidthHz = 6_999).validate(22))
        assertNotNull(valid.copy(bandwidthHz = 500_001).validate(22))
    }

    @Test
    fun `spreading factor and coding rate bounds match the firmware`() {
        assertNotNull(valid.copy(spreadingFactor = 4).validate(22))
        assertNotNull(valid.copy(spreadingFactor = 13).validate(22))
        assertNotNull(valid.copy(codingRate = 4).validate(22))
        assertNotNull(valid.copy(codingRate = 9).validate(22))
    }

    @Test
    fun `an empty name is rejected because the firmware needs at least one byte`() {
        assertNotNull(valid.copy(name = "  ").validate(22))
    }

    @Test
    fun `out of range coordinates are rejected`() {
        assertNotNull(valid.copy(latE6 = 91_000_000).validate(22))
        assertNotNull(valid.copy(lonE6 = -181_000_000).validate(22))
    }

    @Test
    fun `cleared location is a valid zero coordinate`() {
        assertNull(valid.copy(latE6 = 0, lonE6 = 0).validate(22))
    }

    @Test
    fun `frequency text parses megahertz into kilohertz`() {
        assertEquals(869_618, NodeSettings.freqKhzFromMhzText("869.618"))
        // Slovak and other locales type a decimal comma.
        assertEquals(869_618, NodeSettings.freqKhzFromMhzText("869,618"))
        assertNull(NodeSettings.freqKhzFromMhzText("not a number"))
    }

    @Test
    fun `coordinate text parses into microdegrees`() {
        assertEquals(48_707_708, NodeSettings.coordE6FromText("48.707708"))
        assertEquals(-21_216_309, NodeSettings.coordE6FromText("-21,216309"))
        assertEquals(0, NodeSettings.coordE6FromText("0.000000"))
        assertNull(NodeSettings.coordE6FromText(""))
    }

    @Test
    fun `radioDiffers only reacts to radio fields`() {
        assertTrue(!valid.radioDiffers(valid.copy(name = "Other")))
        assertTrue(!valid.radioDiffers(valid.copy(txPowerDbm = 5)))
        assertTrue(valid.radioDiffers(valid.copy(spreadingFactor = 9)))
        assertTrue(valid.radioDiffers(valid.copy(freqKhz = 868_000)))
    }

    @Test
    fun `unit conversions round trip`() {
        assertEquals(869.618, valid.freqMhz, 0.0001)
        assertEquals(62.5, valid.bandwidthKhz, 0.0001)
    }
}
