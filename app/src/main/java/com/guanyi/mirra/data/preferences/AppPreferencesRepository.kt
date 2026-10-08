package com.guanyi.mirra.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.guanyi.mirra.navigation.TopLevelDestination
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

interface AppPreferencesRepository {
    val lastDestination: Flow<TopLevelDestination>
    val themeId: Flow<MirraThemeId>
    val dndEnabled: Flow<Boolean>
    val crossAppInterventionEnabled: Flow<Boolean>

    suspend fun setLastDestination(destination: TopLevelDestination)
    suspend fun setThemeId(themeId: MirraThemeId)
    suspend fun setDndEnabled(enabled: Boolean)
    suspend fun setCrossAppInterventionEnabled(enabled: Boolean)
}

class DefaultAppPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : AppPreferencesRepository, StrictAppPreferencesReader {
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

    override val dndEnabled: Flow<Boolean> = dataStore.data
        .catch { throwable -> if (throwable is IOException) emit(emptyPreferences()) else throw throwable }
        .map { preferences -> preferences[DND_ENABLED] ?: false }
    override val crossAppInterventionEnabled: Flow<Boolean> = dataStore.data
        .catch { throwable -> if (throwable is IOException) emit(emptyPreferences()) else throw throwable }
        .map { preferences -> preferences[CROSS_APP_INTERVENTION_ENABLED] ?: false }

    override suspend fun setLastDestination(destination: TopLevelDestination) {
        dataStore.edit { preferences ->
            preferences[LAST_DESTINATION] = destination.storageValue
        }
    }

    override suspend fun setThemeId(themeId: MirraThemeId) {
        dataStore.edit { preferences -> preferences[THEME_ID] = themeId.storageValue }
    }

    override suspend fun setDndEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[DND_ENABLED] = enabled }
    }
    override suspend fun setCrossAppInterventionEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[CROSS_APP_INTERVENTION_ENABLED] = enabled }
    }

    override suspend fun readStrictSnapshot(): StrictAppPreferencesSnapshot =
        readStrictAppPreferencesSnapshot(dataStore)

    private companion object {
        val LAST_DESTINATION = stringPreferencesKey("last_destination")
        val THEME_ID = stringPreferencesKey("theme_id")
        val DND_ENABLED = booleanPreferencesKey("dnd_enabled")
        val CROSS_APP_INTERVENTION_ENABLED = booleanPreferencesKey("cross_app_intervention_enabled")
    }
}
