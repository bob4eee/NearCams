package com.opencams.nearby.data

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * Windy Webcams API v3 (https://api.windy.com/webcams/docs).
 * Returns webcams the operators have made publicly available.
 * Auth is a header: "x-windy-api-key: <key>".
 */
interface WindyApi {

    @GET("webcams")
    suspend fun nearby(
        @Header("x-windy-api-key") key: String,
        // "nearby=lat,lon,radiusKm"
        @Query("nearby") nearby: String,
        @Query("limit") limit: Int = 50,
        @Query("include") include: String = "location,images,urls,player"
    ): WindyResponse
}

data class WindyResponse(
    val total: Int = 0,
    val webcams: List<WindyWebcam> = emptyList()
)

data class WindyWebcam(
    val webcamId: Long = 0,
    val title: String = "",
    val location: WindyLocation? = null,
    val images: WindyImages? = null,
    val player: WindyPlayer? = null,
    val urls: WindyUrls? = null
)

data class WindyLocation(
    val city: String? = null,
    val country: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

data class WindyImages(
    val current: WindyImageSet? = null
)

data class WindyImageSet(
    val preview: String? = null,
    val thumbnail: String? = null
)

data class WindyPlayer(
    @Json(name = "live") val live: WindyPlayerLink? = null,
    @Json(name = "day") val day: WindyPlayerLink? = null
)

data class WindyPlayerLink(
    val embed: String? = null,
    val link: String? = null
)

data class WindyUrls(
    val detail: String? = null
)
