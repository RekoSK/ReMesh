package com.rekosk.remesh.data.coverage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageMathTest {

    @Test
    fun fspl_matchesKnownValue() {
        // 1 km at 869 MHz ≈ 32.44 + 0 + 58.78 ≈ 91.2 dB.
        assertEquals(91.2, CoverageMath.fsplDb(1_000.0, 869.0), 0.3)
        // Doubling distance adds ~6 dB.
        val a = CoverageMath.fsplDb(1_000.0, 869.0)
        val b = CoverageMath.fsplDb(2_000.0, 869.0)
        assertEquals(6.0, b - a, 0.05)
    }

    @Test
    fun knifeEdge_isZeroWellBelowLos_andGrowsWhenBlocked() {
        assertEquals(0.0, CoverageMath.knifeEdgeDb(-1.0), 0.0) // deep clearance → no loss
        assertEquals(6.0, CoverageMath.knifeEdgeDb(0.0), 0.5)  // grazing ≈ 6 dB
        assertTrue(CoverageMath.knifeEdgeDb(2.0) > CoverageMath.knifeEdgeDb(0.5)) // more blocked → more loss
    }

    @Test
    fun sensitivity_scalesWithSpreadingFactor() {
        val sf12 = CoverageDefaults.sensitivityDbm(12, 125_000)
        val sf7 = CoverageDefaults.sensitivityDbm(7, 125_000)
        // SF12 is far more sensitive (more negative) than SF7.
        assertTrue(sf12 < sf7)
        // SF12 @ BW125k lands near the datasheet ≈ −137 dBm.
        assertEquals(-137.0, sf12, 2.0)
    }

    @Test
    fun twoRay_raisingEitherAntennaExtendsRange() {
        // 40 log10(10 km) − 20 log10(5·2) = 160 − 20 = 140 dB.
        assertEquals(140.0, CoverageMath.twoRayDb(10_000.0, 5.0, 2.0), 0.2)
        // Higher masts → less loss at the same distance.
        assertTrue(
            CoverageMath.twoRayDb(10_000.0, 12.0, 2.0) < CoverageMath.twoRayDb(10_000.0, 5.0, 2.0),
        )
    }

    @Test
    fun terrariumDecode_matchesFormula() {
        // (R*256 + G + B/256) − 32768: sea level encodes as (128, 0, 0).
        assertEquals(0.0, CoverageMath.terrariumElevation(128, 0, 0), 1e-9)
        assertEquals(1696.625, CoverageMath.terrariumElevation(134, 160, 160), 1e-6)
        assertTrue(CoverageMath.terrariumElevation(120, 0, 0) < 0) // below sea level
    }

    @Test
    fun viewshed_ridgeShadowsTerrainBehindIt_openPlainStaysCovered() {
        val params = RadioParams(
            freqMHz = 869.525,
            txPowerDbm = 22.0,
            sensitivityDbm = -130.0,
            txAntennaM = 5.0,
            rxAntennaM = 2.0,
            maxRangeM = 15_000.0,
        )
        val src = 48.7 to 21.2
        // Flat world except a 250 m ridge crossing east-west ~4 km north of the source.
        val ridge = ElevationSampler { lat, _ ->
            val northM = (lat - src.first) * 111_320.0
            if (northM in 3_800.0..4_200.0) 250.0 else 0.0
        }
        val n = 128
        val margins = CoverageEngine.computeMargins(src.first, src.second, params, ridge, n)

        fun marginAt(northM: Double, eastM: Double): Float {
            val range = params.maxRangeM
            val px = ((eastM + range) / (2 * range) * (n - 1)).toInt()
            val py = ((range - northM) / (2 * range) * (n - 1)).toInt()
            return margins[py * n + px]
        }

        val nearNorth = marginAt(2_000.0, 0.0)   // before the ridge
        val behindRidge = marginAt(10_000.0, 0.0) // deep in the ridge's shadow
        val openSouth = marginAt(-10_000.0, 0.0)  // same distance, no obstacle

        assertTrue("near-field should be covered", nearNorth > 0f)
        assertTrue("open plain at 10 km should be covered", openSouth > 0f)
        assertTrue(
            "terrain shadow must cost real dB (south=$openSouth, shadow=$behindRidge)",
            behindRidge < openSouth - 15f,
        )
    }
}
