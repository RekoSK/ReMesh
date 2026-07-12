package com.rekosk.remesh.data.coverage

import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.tan

/** Anything that can answer "how high is the ground here", in metres above sea level. */
fun interface ElevationSampler {
    fun elevationM(lat: Double, lon: Double): Double
}

/**
 * Elevation source backed by AWS Terrarium terrain tiles (free, keyless):
 * `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png`, 256x256 PNG where
 * `elevation = (R*256 + G + B/256) - 32768` metres. Tiles live only in this instance's memory —
 * nothing is written to disk, so scoping an instance to the coverage screen means all terrain
 * data is fetched fresh in real time and forgotten when the screen closes.
 */
class TerrainDem {

    /** Decoded tile: 256x256 elevations, metres (rounded). */
    private val tiles = HashMap<Long, ShortArray?>()

    /**
     * Fetches every tile covering a square of [rangeM] around the centre and returns a sampler
     * over them. Blocking network work — call from a background dispatcher. Ground for tiles
     * that could not be fetched reads as 0 m (sea level).
     */
    fun prepare(centerLat: Double, centerLon: Double, rangeM: Double): ElevationSampler {
        val zoom = zoomFor(centerLat, rangeM)
        val latSpan = rangeM / 111_320.0
        val lonSpan = rangeM / (111_320.0 * cos(Math.toRadians(centerLat)).coerceAtLeast(0.05))
        val n = 1 shl zoom
        val x0 = tileX(centerLon - lonSpan, zoom).toInt().coerceIn(0, n - 1)
        val x1 = tileX(centerLon + lonSpan, zoom).toInt().coerceIn(0, n - 1)
        val y0 = tileY(centerLat + latSpan, zoom).toInt().coerceIn(0, n - 1)
        val y1 = tileY(centerLat - latSpan, zoom).toInt().coerceIn(0, n - 1)
        for (x in x0..x1) for (y in y0..y1) tile(zoom, x, y)
        return ElevationSampler { lat, lon ->
            val fx = tileX(lon, zoom)
            val fy = tileY(lat, zoom)
            val tx = floor(fx).toInt()
            val ty = floor(fy).toInt()
            val data = tile(zoom, tx, ty) ?: return@ElevationSampler 0.0
            val px = ((fx - tx) * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE - 1)
            val py = ((fy - ty) * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE - 1)
            data[py * TILE_SIZE + px].toDouble()
        }
    }

    /** Largest zoom (finest terrain) that keeps the fetch to roughly a 5x5 tile grid. */
    private fun zoomFor(lat: Double, rangeM: Double): Int {
        var z = 12
        while (z > 7) {
            val pxM = 156_543.03 * cos(Math.toRadians(lat)) / (1 shl z)
            if (2 * rangeM / (pxM * TILE_SIZE) <= 5.2) return z
            z--
        }
        return z
    }

    private fun tile(z: Int, x: Int, y: Int): ShortArray? {
        val key = (z.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()
        synchronized(tiles) { if (tiles.containsKey(key)) return tiles[key] }
        val bytes = loadBytes(z, x, y)
        val decoded = bytes?.let { decodeTile(it) }
        synchronized(tiles) { tiles[key] = decoded }
        return decoded
    }

    private fun loadBytes(z: Int, x: Int, y: Int): ByteArray? = runCatching {
        val conn = URL("$TILE_BASE/$z/$x/$y.png").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.inputStream.use { it.readBytes() }
    }.getOrNull()

    private fun decodeTile(bytes: ByteArray): ShortArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (bitmap.width != TILE_SIZE || bitmap.height != TILE_SIZE) return null
        val pixels = IntArray(TILE_SIZE * TILE_SIZE)
        bitmap.getPixels(pixels, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
        bitmap.recycle()
        return ShortArray(pixels.size) { i ->
            val p = pixels[i]
            CoverageMath.terrariumElevation((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                .toInt().coerceIn(-32000, 32000).toShort()
        }
    }

    companion object {
        private const val TILE_BASE = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium"
        private const val TILE_SIZE = 256

        fun tileX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)

        fun tileY(lat: Double, z: Int): Double {
            val clamped = lat.coerceIn(-85.05, 85.05)
            return (1.0 - asinh(tan(Math.toRadians(clamped))) / PI) / 2.0 * (1 shl z)
        }
    }
}
