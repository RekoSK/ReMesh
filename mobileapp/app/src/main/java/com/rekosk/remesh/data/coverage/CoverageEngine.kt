package com.rekosk.remesh.data.coverage

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Radio link parameters used to shape and threshold coverage. */
data class RadioParams(
    val freqMHz: Double,
    val txPowerDbm: Double,
    val sensitivityDbm: Double,
    /** Transmit antenna height above ground, metres. */
    val txAntennaM: Double = CoverageDefaults.TX_ANTENNA_M,
    /** Assumed receiver antenna height above ground, metres. */
    val rxAntennaM: Double = CoverageDefaults.RX_ANTENNA_M,
    /** Compute boundary. 0 = derive from the link budget (see [CoverageMath.maxRangeM]). */
    val maxRangeM: Double = 0.0,
) {
    /** The params with [maxRangeM] resolved from the link budget when not set explicitly. */
    fun withDerivedRange(): RadioParams =
        if (maxRangeM > 0.0) this
        else copy(
            maxRangeM = CoverageMath.maxRangeM(
                freqMHz, txPowerDbm, sensitivityDbm, txAntennaM, rxAntennaM,
            ),
        )
}

/** A georeferenced coverage heatmap: an ARGB bitmap over a lat/lon bounding box. */
class CoverageResult(
    val bitmap: Bitmap,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)

object CoverageDefaults {
    const val DEFAULT_FREQ_MHZ = 869.525
    const val DEFAULT_TX_DBM = 22.0
    const val DEFAULT_SENS_DBM = -130.0
    const val TX_ANTENNA_M = 5.0
    const val RX_ANTENNA_M = 2.0
    const val REPEATER_ANTENNA_M = 12.0

    /** Sanity clamps on the link-budget-derived compute range. */
    const val MIN_RANGE_M = 2_000.0
    const val MAX_RANGE_CAP_M = 80_000.0

    /** Combined TX+RX antenna gain, dB. */
    const val SYSTEM_GAIN_DB = 2.0

    /** Output raster is RASTER_SIZE x RASTER_SIZE cells over the 2*maxRange square. */
    const val RASTER_SIZE = 512

    const val EARTH_RADIUS_M = 6_371_000.0
    const val K_FACTOR = 4.0 / 3.0

    /** LoRa receiver sensitivity ≈ thermal noise floor + noise figure + demod SNR limit. */
    fun sensitivityDbm(spreadingFactor: Int, bandwidthHz: Long): Double {
        val snrLimit = when (spreadingFactor) {
            7 -> -7.5
            8 -> -10.0
            9 -> -12.5
            10 -> -15.0
            11 -> -17.5
            else -> -20.0
        }
        val noiseFigure = 6.0
        val bw = bandwidthHz.coerceAtLeast(1L).toDouble()
        return -174.0 + 10 * log10(bw) + noiseFigure + snrLimit
    }
}

/** Pure propagation math, unit-tested. */
object CoverageMath {
    /** Free-space path loss in dB. */
    fun fsplDb(distanceM: Double, freqMHz: Double): Double {
        val dKm = (distanceM / 1000.0).coerceAtLeast(1e-4)
        return 32.44 + 20 * log10(dKm) + 20 * log10(freqMHz)
    }

    /**
     * Two-ray (plane-earth) loss in dB — the long-range ground-reflection limit that makes
     * antenna height matter: L = 40·log10(d) − 20·log10(ht·hr). Only meaningful beyond the
     * break distance, so callers take max(FSPL, twoRay).
     */
    fun twoRayDb(distanceM: Double, txHeightM: Double, rxHeightM: Double): Double {
        val d = distanceM.coerceAtLeast(1.0)
        val ht = txHeightM.coerceAtLeast(0.5)
        val hr = rxHeightM.coerceAtLeast(0.5)
        return 40 * log10(d) - 20 * log10(ht * hr)
    }

    /** ITU-R P.526 single knife-edge diffraction loss for Fresnel-Kirchhoff parameter v. */
    fun knifeEdgeDb(v: Double): Double {
        if (v <= -0.78) return 0.0
        return 6.9 + 20 * log10(sqrt((v - 0.1).pow(2) + 1.0) + v - 0.1)
    }

    /** Terrarium terrain-tile RGB → metres above sea level. */
    fun terrariumElevation(r: Int, g: Int, b: Int): Double =
        (r * 256 + g + b / 256.0) - 32768.0

    /**
     * Maximum reach of the signal for the given link parameters: the distance at which the
     * best-case (unobstructed) loss — free space near in, the two-ray ground limit far out —
     * spends the whole link budget. TX power, frequency, sensitivity, and antenna heights all
     * move this, so it is the natural compute boundary; clamped only for compute sanity.
     */
    fun maxRangeM(
        freqMHz: Double,
        txPowerDbm: Double,
        sensitivityDbm: Double,
        txAntennaM: Double,
        rxAntennaM: Double,
    ): Double {
        val budget = txPowerDbm + CoverageDefaults.SYSTEM_GAIN_DB - sensitivityDbm
        val fsplBoundM = 1000.0 * 10.0.pow((budget - 32.44 - 20 * log10(freqMHz)) / 20.0)
        val heights = txAntennaM.coerceAtLeast(0.5) * rxAntennaM.coerceAtLeast(0.5)
        val twoRayBoundM = 10.0.pow((budget + 20 * log10(heights)) / 40.0)
        return minOf(fsplBoundM, twoRayBoundM)
            .coerceIn(CoverageDefaults.MIN_RANGE_M, CoverageDefaults.MAX_RANGE_CAP_M)
    }
}

/**
 * Terrain-aware coverage estimator. Marches rays out from the source over a real DEM,
 * accumulating the dominant and secondary terrain edges (Deygout-style double knife-edge,
 * with 4/3-earth curvature), and thresholds FSPL/two-ray path loss + diffraction against
 * the link budget. Produces a signal-margin raster tinted the point's colour — coverage
 * follows valleys and dies behind ridges rather than drawing a circle.
 */
class CoverageEngine {

    suspend fun compute(
        sourceLat: Double,
        sourceLon: Double,
        params: RadioParams,
        baseColorArgb: Int,
        sampler: ElevationSampler,
    ): CoverageResult = withContext(Dispatchers.Default) {
        val resolved = params.withDerivedRange()
        val n = CoverageDefaults.RASTER_SIZE
        val margins = computeMargins(sourceLat, sourceLon, resolved, sampler, n)
        colorize(sourceLat, sourceLon, resolved, margins, n, baseColorArgb)
    }

    private fun colorize(
        sourceLat: Double,
        sourceLon: Double,
        params: RadioParams,
        margins: FloatArray,
        n: Int,
        baseColorArgb: Int,
    ): CoverageResult {
        val latSpan = params.maxRangeM / 111_320.0
        val lonSpan = params.maxRangeM / (111_320.0 * cos(Math.toRadians(sourceLat)).coerceAtLeast(0.05))
        val baseR = AndroidColor.red(baseColorArgb)
        val baseG = AndroidColor.green(baseColorArgb)
        val baseB = AndroidColor.blue(baseColorArgb)
        val pixels = IntArray(n * n)
        for (i in pixels.indices) {
            val m = margins[i]
            if (m <= 0f) continue
            // Soft wash at the fringe, denser where the margin is strong; capped well below
            // opaque so the tint reads like the contact palette, not a brighter version of it.
            val alpha = (55 + (m / 30f).coerceIn(0f, 1f) * 100).toInt()
            pixels[i] = AndroidColor.argb(alpha, baseR, baseG, baseB)
        }
        val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, n, 0, 0, n, n)
        return CoverageResult(
            bitmap = bitmap,
            south = sourceLat - latSpan,
            west = sourceLon - lonSpan,
            north = sourceLat + latSpan,
            east = sourceLon + lonSpan,
        )
    }

    companion object {
        /**
         * Signal margin (received dBm − sensitivity) per raster cell, row 0 = north.
         * Cells the rays never reach (or below threshold) stay at −∞/negative. Pure math,
         * no Android types, so it is unit-testable with a synthetic [sampler].
         */
        internal fun computeMargins(
            sourceLat: Double,
            sourceLon: Double,
            params: RadioParams,
            sampler: ElevationSampler,
            n: Int = CoverageDefaults.RASTER_SIZE,
        ): FloatArray {
            val range = params.maxRangeM
            val margins = FloatArray(n * n) { Float.NEGATIVE_INFINITY }
            val cosLat = cos(Math.toRadians(sourceLat)).coerceAtLeast(0.05)
            val elevTx = sampler.elevationM(sourceLat, sourceLon)
            val txAbs = elevTx + params.txAntennaM
            val lambda = 299.792458 / params.freqMHz
            val budget = params.txPowerDbm + CoverageDefaults.SYSTEM_GAIN_DB - params.sensitivityDbm
            val twoKRe = 2 * CoverageDefaults.K_FACTOR * CoverageDefaults.EARTH_RADIUS_M

            val rays = n * 3
            val steps = n
            val stepM = range / steps
            for (r in 0 until rays) {
                val az = 2 * Math.PI * r / rays
                val sinA = sin(az)
                val cosA = cos(az)
                // Dominant terrain edge so far (highest elevation angle seen from the TX) and
                // the strongest secondary edge past it — Deygout's two-edge approximation.
                var mainTheta = Double.NEGATIVE_INFINITY
                var mainD = 0.0
                var mainEff = 0.0
                var secTheta = Double.NEGATIVE_INFINITY
                var secD = 0.0
                var secEff = 0.0
                for (s in 1..steps) {
                    val d = s * stepM
                    val x = sinA * d
                    val y = cosA * d
                    val lat = sourceLat + y / 111_320.0
                    val lon = sourceLon + x / (111_320.0 * cosLat)
                    val elev = sampler.elevationM(lat, lon)
                    val drop = d * d / twoKRe // earth-curvature fall-away relative to the TX
                    val rxAbsEff = elev + params.rxAntennaM - drop

                    // Basic loss: free space near in, two-ray ground limit far out. Terrain
                    // altitude advantage counts toward the effective antenna heights, so a
                    // ridge-top node reaches far into the valley below it.
                    val htEff = params.txAntennaM + max(0.0, elevTx - elev)
                    val hrEff = params.rxAntennaM + max(0.0, elev - elevTx)
                    var loss = max(
                        CoverageMath.fsplDb(d, params.freqMHz),
                        CoverageMath.twoRayDb(d, htEff, hrEff),
                    )
                    // Diffraction over the dominant edge, then the secondary edge on the
                    // remaining sub-path.
                    if (mainTheta != Double.NEGATIVE_INFINITY && mainD < d) {
                        val line = txAbs + (rxAbsEff - txAbs) * (mainD / d)
                        val h = mainEff - line
                        val v = h * sqrt(2.0 / lambda * d / (mainD * (d - mainD)))
                        loss += CoverageMath.knifeEdgeDb(v)
                        if (secTheta != Double.NEGATIVE_INFINITY && secD < d) {
                            val subD = d - mainD
                            val d1 = secD - mainD
                            if (d1 > 0 && d1 < subD) {
                                val subLine = mainEff + (rxAbsEff - mainEff) * (d1 / subD)
                                val h2 = secEff - subLine
                                val v2 = h2 * sqrt(2.0 / lambda * subD / (d1 * (subD - d1)))
                                loss += CoverageMath.knifeEdgeDb(v2)
                            }
                        }
                    }
                    val margin = (budget - loss).toFloat()

                    val px = ((x + range) / (2 * range) * (n - 1)).roundToInt()
                    val py = ((range - y) / (2 * range) * (n - 1)).roundToInt()
                    if (px in 0 until n && py in 0 until n) {
                        val idx = py * n + px
                        if (margin > margins[idx]) margins[idx] = margin
                    }

                    // This point becomes a candidate obstacle for everything farther out.
                    val eff = elev - drop
                    val theta = (eff - txAbs) / d
                    if (theta > mainTheta) {
                        mainTheta = theta
                        mainD = d
                        mainEff = eff
                        secTheta = Double.NEGATIVE_INFINITY
                        secD = 0.0
                        secEff = 0.0
                    } else if (mainTheta != Double.NEGATIVE_INFINITY) {
                        val thetaSub = (eff - mainEff) / (d - mainD)
                        if (thetaSub > secTheta) {
                            secTheta = thetaSub
                            secD = d
                            secEff = eff
                        }
                    }
                }
            }
            return margins
        }
    }
}
