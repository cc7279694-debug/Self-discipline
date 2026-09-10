package com.guanyi.mirra.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.guanyi.mirra.data.preferences.AppPreferencesRepository
import com.guanyi.mirra.data.preferences.DefaultAppPreferencesRepository

private val Context.mirraPreferences by preferencesDataStore(name = "mirra_preferences")

interface AppContainer {
    val appPreferencesRepository: AppPreferencesRepository
}

class DefaultAppContainer(context: Context) : AppContainer {
    override val appPreferencesRepository: AppPreferencesRepository =
        DefaultAppPreferencesRepository(context.mirraPreferences)
}
