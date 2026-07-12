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
}
