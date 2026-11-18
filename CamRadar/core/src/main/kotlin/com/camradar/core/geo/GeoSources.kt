package com.camradar.core.geo

import com.camradar.core.model.Camera
import com.camradar.core.model.CameraCategory
import com.camradar.core.model.DiscoverySource
import com.camradar.core.model.LocationConfidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal HTTP abstraction so geographic sources can be tested offline. */
fun interface HttpFetcher {
    @Throws(IOException::class)
    fun post(url: String, formBody: String, timeoutMs: Int): String
}

class UrlConnectionFetcher(private val userAgent: String = "CamRadar/1.0 (Android)") : HttpFetcher {
    override fun post(url: String, formBody: String, timeoutMs: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(formBody.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Queries OpenStreetMap (via the Overpass API) for features tagged man_made=surveillance.
 * These are camera installations mapped by contributors at their physical position — a
 * legitimate public dataset. The data is ODbL licensed; attribution is shown in the UI.
 */
class OverpassSource(
    private val fetcher: HttpFetcher,
    private val endpoint: String = "https://overpass-api.de/api/interpreter",
    private val timeoutMs: Int = 20_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun buildQuery(center: LatLon, radiusMeters: Int): String =
        """[out:json][timeout:25];(node["man_made"="surveillance"](around:$radiusMeters,${center.lat},${center.lon});""" +
            """way["man_made"="surveillance"](around:$radiusMeters,${center.lat},${center.lon}););out center tags;"""

    fun fetch(center: LatLon, radiusMeters: Int): List<Camera> {
        val body = "data=" + URLEncoder.encode(buildQuery(center, radiusMeters), "UTF-8")
        return OverpassParser.parse(fetcher.post(endpoint, body, timeoutMs), clock())
    }
}

object OverpassParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String, nowMs: Long): List<Camera> {
        val root = json.parseToJsonElement(body).jsonObject
        val elements = root["elements"]?.jsonArray ?: return emptyList()
        return elements.mapNotNull { el ->
            val o = el.jsonObject
            val type = o["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val (lat, lon, exact) = when (type) {
                "node" -> Triple(o.num("lat"), o.num("lon"), true)
                else -> (o["center"] as? JsonObject).let { c -> Triple(c?.num("lat"), c?.num("lon"), false) }
            }
            if (lat == null || lon == null) return@mapNotNull null
            val tags = (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() } ?: emptyMap()
            Camera(
                id = "osm:$type/$id",
                name = tags["name"] ?: tags["operator"]?.let { "Camera ($it)" } ?: "Surveillance camera",
                manufacturer = tags["manufacturer"],
                model = tags["model"],
                location = LatLon(lat, lon),
                locationConfidence = if (exact) LocationConfidence.EXACT_REGISTERED else LocationConfidence.APPROXIMATE_REGISTERED,
                category = categoryOf(tags),
                source = DiscoverySource.OSM_SURVEILLANCE,
                sourceDetail = listOfNotNull(
                    "OSM $type $id",
                    tags["surveillance:type"]?.let { "type=$it" },
                    tags["camera:type"]?.let { "camera=$it" },
                    tags["operator"]?.let { "operator=$it" },
                ).joinToString(", "),
                lastSeenEpochMs = nowMs,
            )
        }
    }

    internal fun categoryOf(tags: Map<String, String>): CameraCategory = when {
        tags["surveillance:type"] == "ALPR" || tags["surveillance:zone"] == "traffic" -> CameraCategory.TRAFFIC
        tags["surveillance"] == "public" || tags["surveillance"] == "outdoor" && tags["operator:type"] == "government" -> CameraCategory.PUBLIC_SURVEILLANCE
        tags["surveillance:type"] == "webcam" || tags["surveillance"] == "webcam" -> CameraCategory.WEBCAM
        tags["surveillance"] == "indoor" || tags["surveillance"] == "outdoor" -> CameraCategory.PRIVATE_SURVEILLANCE
        else -> CameraCategory.OTHER
    }

    private fun JsonObject.num(key: String) = this[key]?.jsonPrimitive?.doubleOrNull
}

/**
 * Parses a user-supplied camera registry (e.g. a municipal open-data export).
 * Header row required; columns: id,name,lat,lon[,manufacturer,model,category,precision]
 * precision = "exact" | "approximate" | "ip_geolocation".
 */
object RegistryCsvParser {
    data class Result(val cameras: List<Camera>, val errors: List<String>)

    fun parse(csv: String, registryName: String, nowMs: Long): Result {
        val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (lines.isEmpty()) return Result(emptyList(), listOf("Empty file"))
        val header = splitCsv(lines.first()).map { it.lowercase() }
        val idx = { name: String -> header.indexOf(name) }
        val required = listOf("id", "name", "lat", "lon")
        val missing = required.filter { idx(it) < 0 }
        if (missing.isNotEmpty()) return Result(emptyList(), listOf("Missing columns: ${missing.joinToString()}"))
        val cams = mutableListOf<Camera>()
        val errors = mutableListOf<String>()
        lines.drop(1).forEachIndexed { i, line ->
            val f = splitCsv(line)
            fun col(n: String) = idx(n).takeIf { it >= 0 }?.let { f.getOrNull(it) }?.takeIf { it.isNotBlank() }
            val lat = col("lat")?.toDoubleOrNull()
            val lon = col("lon")?.toDoubleOrNull()
            val id = col("id")
            if (id == null || lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                errors += "Row ${i + 2}: invalid id/lat/lon"; return@forEachIndexed
            }
            cams += Camera(
                id = "reg:$registryName:$id",
                name = col("name") ?: "Camera $id",
                manufacturer = col("manufacturer"),
                model = col("model"),
                location = LatLon(lat, lon),
                locationConfidence = when (col("precision")?.lowercase()) {
                    "approximate" -> LocationConfidence.APPROXIMATE_REGISTERED
                    "ip_geolocation" -> LocationConfidence.APPROXIMATE_IP_GEOLOCATION
                    else -> LocationConfidence.EXACT_REGISTERED
                },
                category = col("category")?.let { c -> CameraCategory.entries.firstOrNull { it.name.equals(c, true) } }
                    ?: CameraCategory.OTHER,
                source = DiscoverySource.IMPORTED_REGISTRY,
                sourceDetail = registryName,
                lastSeenEpochMs = nowMs,
            )
        }
        return Result(cams, errors)
    }

    internal fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && line.getOrNull(i + 1) == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString().trim()
        return out
    }
}
