package com.rekosk.remesh.data.coverage

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Radio link parameters used to threshold coverage. */
data class RadioParams(
    val freqMHz: Double,
    val txPowerDbm: Double,
    val sensitivityDbm: Double,
    val txAntennaM: Double = CoverageDefaults.TX_ANTENNA_M,
    val rxAntennaM: Double = CoverageDefaults.RX_ANTENNA_M,
    val maxRangeM: Double = CoverageDefaults.MAX_RANGE_M,
    val radials: Int = CoverageDefaults.RADIALS,
    val steps: Int = CoverageDefaults.STEPS,
)

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
    const val TX_ANTENNA_M = 3.0
    const val RX_ANTENNA_M = 1.5
    const val MAX_RANGE_M = 15_000.0
    const val RADIALS = 72
    const val STEPS = 50
    const val EARTH_RADIUS_M = 6_371_000.0
    const val K_FACTOR = 4.0 / 3.0
    const val RASTER_SIZE = 220

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

    /** ITU-R P.526 single knife-edge diffraction loss for Fresnel-Kirchhoff parameter v. */
    fun knifeEdgeDb(v: Double): Double {
        if (v <= -0.78) return 0.0
        return 6.9 + 20 * log10(sqrt((v - 0.1).pow(2) + 1.0) + v - 0.1)
    }
}

/**
 * Terrain-aware coverage estimator: casts radials from the source, walks the elevation
 * profile out to [RadioParams.maxRangeM], and thresholds free-space + knife-edge diffraction
 * loss against the link budget. Produces a heatmap tinted toward the point's colour, with
 * opacity following the signal margin. A simplified (P.526) model, not certified Longley-Rice.
 */
class CoverageEngine(private val elevation: ElevationClient = ElevationClient()) {

    suspend fun compute(
        sourceLat: Double,
        sourceLon: Double,
        params: RadioParams,
        baseColorArgb: Int,
    ): CoverageResult = withContext(Dispatchers.IO) {
        val radials = params.radials
        val steps = params.steps
        val stepM = params.maxRangeM / steps

        // Sample points: source first, then radial * step points.
        val pts = ArrayList<DoubleArray>(1 + radials * steps)
        pts.add(doubleArrayOf(sourceLat, sourceLon))
        for (r in 0 until radials) {
            val bearing = 360.0 * r / radials
            for (s in 1..steps) {
                val (la, lo) = destination(sourceLat, sourceLon, bearing, s * stepM)
                pts.add(doubleArrayOf(la, lo))
            }
        }
        val elev = elevation.elevations(pts)
        val txAbs = elev[0] + params.txAntennaM
        val lambda = 299.792458 / params.freqMHz // wavelength in metres

        // margin[r][s] = received power − sensitivity at distance (s+1)*stepM along radial r.
        val margin = Array(radials) { DoubleArray(steps) }
        var idx = 1
        for (r in 0 until radials) {
            val prof = DoubleArray(steps) { elev[idx + it] }
            for (s in 0 until steps) {
                val d = (s + 1) * stepM
                val rxAbs = prof[s] + params.rxAntennaM
                var vMax = -Double.MAX_VALUE
                for (i in 0 until s) {
                    val di = (i + 1) * stepM
                    val d2 = d - di
                    if (d2 <= 0.0) continue
                    val bulge = (di * d2) / (2 * CoverageDefaults.K_FACTOR * CoverageDefaults.EARTH_RADIUS_M)
                    val terrain = prof[i] + bulge
                    val los = txAbs + (rxAbs - txAbs) * (di / d)
                    val clearance = terrain - los
                    val v = clearance * sqrt(2.0 / lambda * d / (di * d2))
                    if (v > vMax) vMax = v
                }
                val diffraction = if (vMax == -Double.MAX_VALUE) 0.0 else CoverageMath.knifeEdgeDb(vMax)
                val loss = CoverageMath.fsplDb(d, params.freqMHz) + diffraction
                margin[r][s] = (params.txPowerDbm - loss) - params.sensitivityDbm
            }
            idx += steps
        }

        rasterize(sourceLat, sourceLon, params, margin, stepM, baseColorArgb)
    }

    private fun rasterize(
        sourceLat: Double,
        sourceLon: Double,
        params: RadioParams,
        margin: Array<DoubleArray>,
        stepM: Double,
        baseColorArgb: Int,
    ): CoverageResult {
        val n = CoverageDefaults.RASTER_SIZE
        val latSpan = params.maxRangeM / 111_320.0
        val lonSpan = params.maxRangeM / (111_320.0 * cos(Math.toRadians(sourceLat)).coerceAtLeast(0.05))
        val south = sourceLat - latSpan
        val north = sourceLat + latSpan
        val west = sourceLon - lonSpan
        val east = sourceLon + lonSpan

        val baseR = AndroidColor.red(baseColorArgb)
        val baseG = AndroidColor.green(baseColorArgb)
        val baseB = AndroidColor.blue(baseColorArgb)
        val radials = params.radials
        val steps = params.steps

        val pixels = IntArray(n * n)
        for (py in 0 until n) {
            val lat = north - (north - south) * py / (n - 1)
            for (px in 0 until n) {
                val lon = west + (east - west) * px / (n - 1)
                val (dist, bearing) = distanceBearing(sourceLat, sourceLon, lat, lon)
                if (dist > params.maxRangeM) continue
                val rf = bearing / (360.0 / radials)
                val r = ((Math.round(rf).toInt()) % radials + radials) % radials
                val sf = (dist / stepM) - 1.0
                val s0 = sf.toInt().coerceIn(0, steps - 1)
                val s1 = (s0 + 1).coerceAtMost(steps - 1)
                val frac = (sf - s0).coerceIn(0.0, 1.0)
                val m = margin[r][s0] * (1 - frac) + margin[r][s1] * frac
                if (m <= 0.0) continue
                // Opacity grows with signal margin (0..~35 dB); tint stays the point colour.
                val alpha = (60 + (m / 35.0).coerceIn(0.0, 1.0) * 130).toInt().coerceIn(0, 190)
                pixels[py * n + px] = AndroidColor.argb(alpha, baseR, baseG, baseB)
            }
        }
        val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, n, 0, 0, n, n)
        return CoverageResult(bitmap, south, west, north, east)
    }

    private fun destination(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val r = CoverageDefaults.EARTH_RADIUS_M
        val br = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val dr = distM / r
        val lat2 = asin(sin(lat1) * cos(dr) + cos(lat1) * sin(dr) * cos(br))
        val lon2 = lon1 + atan2(sin(br) * sin(dr) * cos(lat1), cos(dr) - sin(lat1) * sin(lat2))
        return Math.toDegrees(lat2) to Math.toDegrees(lon2)
    }

    /** Returns metres and initial bearing (° from north) from point 1 to point 2. */
    private fun distanceBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
        val r = CoverageDefaults.EARTH_RADIUS_M
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dP = Math.toRadians(lat2 - lat1)
        val dL = Math.toRadians(lon2 - lon1)
        val a = sin(dP / 2).pow(2) + cos(p1) * cos(p2) * sin(dL / 2).pow(2)
        val dist = r * 2 * asin(sqrt(a).coerceIn(0.0, 1.0))
        val y = sin(dL) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dL)
        val bearing = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        return dist to bearing
    }
}
