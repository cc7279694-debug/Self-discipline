package com.guanyi.mirra

import android.app.Application
import com.guanyi.mirra.di.AppContainer
import com.guanyi.mirra.di.DefaultAppContainer
import com.guanyi.mirra.platform.focus.MonitoringPlatformRuntime

class MirraApplication : Application() {
    val monitoringPlatform by lazy { MonitoringPlatformRuntime(applicationContext) }
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(applicationContext)
    }
}
