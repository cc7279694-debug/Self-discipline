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

    suspend fun setLastDestination(destination: TopLevelDestination)
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

    override suspend fun setLastDestination(destination: TopLevelDestination) {
        dataStore.edit { preferences ->
            preferences[LAST_DESTINATION] = destination.storageValue
        }
    }

    private companion object {
        val LAST_DESTINATION = stringPreferencesKey("last_destination")
    }
}
