package com.opencams.nearby.data

import android.content.Context
import com.opencams.nearby.BuildConfig
import com.opencams.nearby.util.Geo
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Loads public cameras from two sources and sorts them by distance from the user:
 *   1. The bundled, editable catalog in assets/cameras.json (always available).
 *   2. The Windy Webcams API, if a key is configured (true "near me" discovery).
 */
class CameraRepository(private val appContext: Context) {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val windy: WindyApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder().addInterceptor(logging).build()
        Retrofit.Builder()
            .baseUrl("https://api.windy.com/webcams/api/v3/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(WindyApi::class.java)
    }

    val hasWindyKey: Boolean get() = BuildConfig.WINDY_API_KEY.isNotBlank()

    /** Cameras near (lat, lon), nearest first. userLoc null -> catalog in file order. */
    suspend fun nearby(lat: Double?, lon: Double?, radiusKm: Int = 250): List<Camera> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<Camera>()
            out += loadCatalog()

            if (hasWindyKey && lat != null && lon != null) {
                runCatching {
                    val resp = windy.nearby(
                        key = BuildConfig.WINDY_API_KEY,
                        nearby = "$lat,$lon,$radiusKm"
                    )
                    out += resp.webcams.mapNotNull { it.toCamera() }
                }
            }

            if (lat != null && lon != null) {
                out.forEach { it.distanceKm = Geo.distanceKm(lat, lon, it.lat, it.lon) }
                out.sortBy { it.distanceKm ?: Double.MAX_VALUE }
            }
            out.distinctBy { it.id }
        }

    private fun loadCatalog(): List<Camera> = runCatching {
        val json = appContext.assets.open("cameras.json").bufferedReader().use { it.readText() }
        // The file may contain "//" comment strings; drop any non-object entries.
        val listType = com.squareup.moshi.Types.newParameterizedType(
            List::class.java, Any::class.java
        )
        @Suppress("UNCHECKED_CAST")
        val raw = moshi.adapter<List<Any?>>(listType).fromJson(json) ?: emptyList()
        val objType = com.squareup.moshi.Types.newParameterizedType(
            Map::class.java, String::class.java, Any::class.java
        )
        val mapAdapter = moshi.adapter<Map<String, Any?>>(objType)
        raw.filterIsInstance<Map<String, Any?>>().mapNotNull { m ->
            runCatching {
                Camera(
                    id = m["id"] as String,
                    title = m["title"] as String,
                    location = (m["location"] as? String).orEmpty(),
                    lat = (m["lat"] as Number).toDouble(),
                    lon = (m["lon"] as Number).toDouble(),
                    feedType = if ((m["feedType"] as? String) == "STREAM")
                        FeedType.STREAM else FeedType.IMAGE,
                    url = m["url"] as String,
                    source = m["source"] as? String,
                    ptzBaseUrl = m["ptzBaseUrl"] as? String
                )
            }.getOrNull().also { mapAdapter } // keep adapter referenced
        }
    }.getOrDefault(emptyList())

    private fun WindyWebcam.toCamera(): Camera? {
        val loc = location ?: return null
        val stream = player?.live?.embed ?: player?.day?.embed
        val image = images?.current?.preview ?: images?.current?.thumbnail
        val (type, url) = when {
            stream != null -> FeedType.STREAM to stream
            image != null -> FeedType.IMAGE to image
            else -> return null
        }
        return Camera(
            id = "windy-$webcamId",
            title = title.ifBlank { "Webcam $webcamId" },
            location = listOfNotNull(loc.city, loc.country).joinToString(", "),
            lat = loc.latitude,
            lon = loc.longitude,
            feedType = type,
            url = url,
            source = urls?.detail
        )
    }
}
