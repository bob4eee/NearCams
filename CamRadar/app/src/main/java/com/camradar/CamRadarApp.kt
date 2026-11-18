package com.camradar

import android.app.Application
import com.camradar.di.AppContainer
import org.osmdroid.config.Configuration

class CamRadarApp : Application() {
    lateinit var container: AppContainer
        private set

    /** Instrumentation tests swap in fakes before launching MainActivity. */
    fun replaceContainer(c: AppContainer) { container = c }

    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().apply {
            userAgentValue = "CamRadar/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = cacheDir.resolve("osmdroid")
            osmdroidTileCache = cacheDir.resolve("osmdroid/tiles")
        }
        container = AppContainer(this)
    }
}
