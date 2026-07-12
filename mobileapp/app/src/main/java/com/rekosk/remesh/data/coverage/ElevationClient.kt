package com.rekosk.remesh.data.coverage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches terrain elevation (metres above sea level) for lat/lon points from a batch
 * elevation API (open-elevation-compatible: `POST {"locations":[{latitude,longitude}...]}`
 * → `{"results":[{elevation}...]}`, in request order). Results are cached on an ~11 m grid,
 * and duplicate coordinates within a call are coalesced. Failed lookups return 0 m.
 */
class ElevationClient(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val chunkSize: Int = 400,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<Long, Double>()

    private fun cacheKey(lat: Double, lon: Double): Long {
        val la = Math.round(lat * 1e5)
        val lo = Math.round(lon * 1e5)
        return la * 40_000_000L + lo
    }

    /** Elevations aligned 1:1 with [points] (each `[lat, lon]`). */
    fun elevations(points: List<DoubleArray>): DoubleArray {
        val out = DoubleArray(points.size)
        val missing = ArrayList<Int>()
        for (i in points.indices) {
            val cached = cache[cacheKey(points[i][0], points[i][1])]
            if (cached != null) out[i] = cached else missing.add(i)
        }
        for (batch in missing.chunked(chunkSize)) {
            val body = buildString {
                append("{\"locations\":[")
                batch.forEachIndexed { k, idx ->
                    if (k > 0) append(',')
                    append("{\"latitude\":").append(points[idx][0])
                    append(",\"longitude\":").append(points[idx][1]).append('}')
                }
                append("]}")
            }
            val text = runCatching { post(body) }.getOrNull() ?: continue
            val parsed = runCatching { json.decodeFromString<LookupResponse>(text) }.getOrNull() ?: continue
            parsed.results.forEachIndexed { k, r ->
                if (k < batch.size) {
                    val idx = batch[k]
                    out[idx] = r.elevation
                    cache[cacheKey(points[idx][0], points[idx][1])] = r.elevation
                }
            }
        }
        return out
    }

    private fun post(body: String): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 40_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    @Serializable
    private data class LookupResponse(val results: List<ElevationResult> = emptyList())

    @Serializable
    private data class ElevationResult(val elevation: Double = 0.0)

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.open-elevation.com/api/v1/lookup"
    }
}
