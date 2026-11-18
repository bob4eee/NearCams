package com.opencams.nearby

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.opencams.nearby.ui.CamerasViewModel
import com.opencams.nearby.ui.DetailScreen
import com.opencams.nearby.ui.MapScreen
import com.opencams.nearby.ui.NearbyListScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    val vm: CamerasViewModel = viewModel()
                    val state by vm.state.collectAsState()

                    val permLauncher = rememberLauncher { granted ->
                        vm.load(locationGranted = granted)
                    }

                    // Kick off a load once, requesting location if we don't have it.
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        val granted = ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            vm.load(locationGranted = true)
                        } else {
                            permLauncher(
                                arrayOf(
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                )
                            )
                        }
                    }

                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "list") {
                        composable("list") {
                            NearbyListScreen(
                                state = state,
                                onOpen = { nav.navigate("detail/${it.id}") },
                                onOpenMap = { nav.navigate("map") }
                            )
                        }
                        composable("map") {
                            MapScreen(
                                cameras = state.cameras,
                                onBack = { nav.popBackStack() },
                                onOpen = { nav.navigate("detail/${it.id}") }
                            )
                        }
                        composable("detail/{id}") { entry ->
                            val id = entry.arguments?.getString("id").orEmpty()
                            vm.camera(id)?.let { cam ->
                                DetailScreen(cam = cam, onBack = { nav.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun rememberLauncher(
    onResult: (Boolean) -> Unit
): (Array<String>) -> Unit {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        onResult(result.values.any { it })
    }
    return { perms -> launcher.launch(perms) }
}
