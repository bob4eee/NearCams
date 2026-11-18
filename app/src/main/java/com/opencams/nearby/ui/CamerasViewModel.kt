package com.opencams.nearby.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opencams.nearby.data.Camera
import com.opencams.nearby.data.CameraRepository
import com.opencams.nearby.util.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(
    val loading: Boolean = false,
    val cameras: List<Camera> = emptyList(),
    val error: String? = null,
    val usedLocation: Boolean = false,
    val hasWindyKey: Boolean = false
)

class CamerasViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CameraRepository(app.applicationContext)
    private val location = LocationProvider(app.applicationContext)

    private val _state = MutableStateFlow(UiState(hasWindyKey = repo.hasWindyKey))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** @param locationGranted whether ACCESS_*_LOCATION was granted by the user. */
    fun load(locationGranted: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val loc = if (locationGranted) location.current() else null
            runCatching {
                repo.nearby(loc?.first, loc?.second)
            }.onSuccess { cams ->
                _state.value = _state.value.copy(
                    loading = false,
                    cameras = cams,
                    usedLocation = loc != null
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Failed to load cameras"
                )
            }
        }
    }

    fun camera(id: String): Camera? = _state.value.cameras.firstOrNull { it.id == id }
}
