package com.camradar.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import com.camradar.core.geo.LatLon
import com.camradar.core.geo.LocationSource
import com.camradar.core.geo.UserFix
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Foreground location via FusedLocationProviderClient. Updates are requested only while the flow
 * is collected (the UI collects with repeatOnLifecycle(STARTED)), so no location is gathered in the background.
 * Caller must hold ACCESS_FINE or ACCESS_COARSE_LOCATION.
 */
class FusedLocationSource(context: Context) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override fun fixes(): Flow<UserFix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L).setMinUpdateIntervalMillis(2_000L).build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { trySend(it.toFix()) } }
        }
        client.lastLocation.addOnSuccessListener { it?.let { l -> trySend(l.toFix()) } }
        client.requestLocationUpdates(request, cb, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(cb) }
    }

    private fun Location.toFix() = UserFix(
        LatLon(latitude, longitude), if (hasAccuracy()) accuracy else 999f, time,
        isMock = if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider,
    )
}
