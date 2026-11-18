package com.camradar.data

import android.content.Context
import com.camradar.core.geo.SearchRadius
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val radius: SearchRadius = SearchRadius.M500,
    /** Opt-in: sends the search area (not your identity) to the OpenStreetMap Overpass API. */
    val publicRegistryConsent: Boolean = false,
    val simulatedGps: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("camradar", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state

    private fun read() = AppSettings(
        radius = SearchRadius.entries.firstOrNull { it.name == prefs.getString("radius", null) } ?: SearchRadius.M500,
        publicRegistryConsent = prefs.getBoolean("registryConsent", false),
        simulatedGps = prefs.getBoolean("simGps", false),
    )

    fun update(f: (AppSettings) -> AppSettings) {
        val n = f(_state.value)
        prefs.edit().putString("radius", n.radius.name).putBoolean("registryConsent", n.publicRegistryConsent)
            .putBoolean("simGps", n.simulatedGps).apply()
        _state.value = n
    }
}
