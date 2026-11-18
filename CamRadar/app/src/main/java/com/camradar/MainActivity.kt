package com.camradar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.camradar.ui.CamRadarRoot
import com.camradar.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.onPermissionResult(hasLocationPermission())
        setContent { CamRadarRoot(vm) }
    }

    override fun onStart() {
        super.onStart()
        vm.onPermissionResult(hasLocationPermission())
        vm.refreshNetwork()
        vm.startLocation()
    }

    /** Stop GPS as soon as the app leaves the foreground: no background collection. */
    override fun onStop() {
        vm.stopLocation()
        super.onStop()
    }

    private fun hasLocationPermission() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}
