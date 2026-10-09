package com.guanyi.mirra.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.guanyi.mirra.data.preferences.DefaultAppPreferencesRepository
import com.guanyi.mirra.data.preferences.PortableAppPreferencesSnapshot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ManagedPreferences(file: File) {
    private val path = file.canonicalPath
    private val lifecycle = Mutex()
    private val owner = SupervisorJob()
    private var closed = false
    init {
        synchronized(owners) { check(owners.add(path)) { "Preferences already have an active owner" } }
    }
    val dataStore: DataStore<Preferences> = try {
        PreferenceDataStoreFactory.create(scope = CoroutineScope(owner + Dispatchers.IO)) {
            file.parentFile?.let { check(it.exists() || it.mkdirs()) { "Preferences directory unavailable" } }
            file
        }
    } catch (failure: Throwable) {
        synchronized(owners) { owners.remove(path) }
        owner.cancel()
        throw failure
    }
    val repository = DefaultAppPreferencesRepository(dataStore)

    /** Only used to construct a private staging file, never as four independent live edits. */
    suspend fun writePortable(snapshot: PortableAppPreferencesSnapshot) = lifecycle.withLock {
        check(!closed) { "Preferences owner is closed" }
        dataStore.edit { preferences ->
            preferences.clear()
            if (snapshot.lastDestination.present) preferences[stringPreferencesKey("last_destination")] = snapshot.lastDestination.value.storageValue
            if (snapshot.themeId.present) preferences[stringPreferencesKey("theme_id")] = snapshot.themeId.value.storageValue
            if (snapshot.dndEnabled.present) preferences[booleanPreferencesKey("dnd_enabled")] = snapshot.dndEnabled.value
            if (snapshot.crossAppInterventionEnabled.present) preferences[booleanPreferencesKey("cross_app_intervention_enabled")] = snapshot.crossAppInterventionEnabled.value
        }
        check(repository.readStrictSnapshot().portable == snapshot) { "Portable preferences did not persist" }
    }

    suspend fun close() = withContext(NonCancellable) {
        lifecycle.withLock {
            if (!closed) {
                owner.cancelAndJoin()
                closed = true
                synchronized(owners) { check(owners.remove(path)) }
            }
        }
    }

    private companion object { val owners = mutableSetOf<String>() }
}
