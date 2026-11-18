package com.opencams.nearby.util

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LocationProvider(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    /** Returns (lat, lon) or null. Caller must have already been granted permission. */
    @SuppressLint("MissingPermission")
    suspend fun current(): Pair<Double, Double>? = suspendCancellableCoroutine { cont ->
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { loc ->
                cont.resume(loc?.let { it.latitude to it.longitude })
            }
            .addOnFailureListener { cont.resume(null) }
    }
}
