package com.guanyi.mirra.data.maintenance

import com.guanyi.mirra.data.preferences.DefaultAppPreferencesRepository
import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.data.repository.SearchRepository
import com.guanyi.mirra.data.repository.SearchResponse
import com.guanyi.mirra.domain.maintenance.MaintenanceUnavailableException
import com.guanyi.mirra.domain.maintenance.RetiredStorageEpochException
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import com.guanyi.mirra.navigation.TopLevelDestination
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GateRepositoryWriterTest {
    @Test fun `four preference setters cannot alter original preferences during exclusive`() = runTest {
        val dataStore = MemoryPreferences()
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val preferences = GateAppPreferencesRepository(DefaultAppPreferencesRepository(dataStore), gate)
        preferences.setThemeId(MirraThemeId.NIGHT)
        val original = dataStore.data.value
        gate.coordinator.withExclusive(1_000) {
            expect<MaintenanceUnavailableException> { preferences.setThemeId(MirraThemeId.BLUE) }
            expect<MaintenanceUnavailableException> { preferences.setLastDestination(TopLevelDestination.Knowledge) }
            expect<MaintenanceUnavailableException> { preferences.setDndEnabled(true) }
            expect<MaintenanceUnavailableException> { preferences.setCrossAppInterventionEnabled(true) }
            assertEquals(original, dataStore.data.value)
        }
        assertEquals("night", dataStore.data.value[stringPreferencesKey("theme_id")])
    }

    @Test fun `query repair is registered and snapshot waits for its index changes`() = runTest {
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val repaired = CompletableDeferred<Unit>()
        val startRepair = CompletableDeferred<Unit>()
        var index = "damaged"
        val search = GateSearchRepository(object : SearchRepository {
            override suspend fun search(rawQuery: String, limit: Int): SearchResponse {
                startRepair.complete(Unit)
                repaired.await()
                index = "repaired"
                return SearchResponse(emptyList())
            }
            override suspend fun rebuildIndex() { index = "rebuilt" }
        }, gate)
        val query = async { search.search("中文") }
        startRepair.await()
        val snapshot = async { gate.coordinator.withExclusive(1_000) { index } }
        runCurrent()
        assertFalse(snapshot.isCompleted)
        expect<MaintenanceUnavailableException> { search.rebuildIndex() }
        repaired.complete(Unit)
        query.await()
        assertEquals("repaired", snapshot.await())
    }

    @Test fun `delayed configuration writer from retired container cannot alter either epoch`() = runTest {
        val original = MemoryPreferences()
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val old = GateAppPreferencesRepository(DefaultAppPreferencesRepository(original), gate)
        old.setDndEnabled(false)
        gate.coordinator.withExclusive(1_000) { gate.retire(it, "replace") }
        val replacementStore = MemoryPreferences()
        val replacement = GateAppPreferencesRepository(DefaultAppPreferencesRepository(replacementStore),
            StorageMaintenanceGate(leaseScope = backgroundScope))
        replacement.setDndEnabled(true)
        expect<RetiredStorageEpochException> { old.setDndEnabled(true) }
        assertFalse(DefaultAppPreferencesRepository(original).readStrictSnapshot().portable.dndEnabled.value)
        assertTrue(DefaultAppPreferencesRepository(replacementStore).readStrictSnapshot().portable.dndEnabled.value)
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected ${T::class.java.simpleName}, got $error", error is T)
    }
}
