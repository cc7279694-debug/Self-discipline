package com.guanyi.mirra

import android.app.Application
import com.guanyi.mirra.di.AppContainer
import com.guanyi.mirra.di.DefaultAppContainer

class MirraApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(applicationContext)
    }
}
