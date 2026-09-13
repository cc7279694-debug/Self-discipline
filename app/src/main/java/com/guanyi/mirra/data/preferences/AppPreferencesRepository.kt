package com.guanyi.mirra.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.guanyi.mirra.navigation.TopLevelDestination
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

interface AppPreferencesRepository {
    val lastDestination: Flow<TopLevelDestination>
    val themeId: Flow<MirraThemeId>

    suspend fun setLastDestination(destination: TopLevelDestination)
    suspend fun setThemeId(themeId: MirraThemeId)
}

class DefaultAppPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : AppPreferencesRepository {
    override val lastDestination: Flow<TopLevelDestination> =
        dataStore.data
            .catch { throwable ->
                if (throwable is IOException) emit(emptyPreferences()) else throw throwable
            }
            .map { preferences ->
                TopLevelDestination.fromStorageValue(preferences[LAST_DESTINATION])
            }

    override val themeId: Flow<MirraThemeId> =
        dataStore.data
            .catch { throwable ->
                if (throwable is IOException) emit(emptyPreferences()) else throw throwable
            }
            .map { preferences -> MirraThemeId.fromStorageValue(preferences[THEME_ID]) }

    override suspend fun setLastDestination(destination: TopLevelDestination) {
        dataStore.edit { preferences ->
            preferences[LAST_DESTINATION] = destination.storageValue
        }
    }

    override suspend fun setThemeId(themeId: MirraThemeId) {
        dataStore.edit { preferences -> preferences[THEME_ID] = themeId.storageValue }
    }

    private companion object {
        val LAST_DESTINATION = stringPreferencesKey("last_destination")
        val THEME_ID = stringPreferencesKey("theme_id")
    }
}
