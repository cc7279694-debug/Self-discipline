package com.guanyi.mirra.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.guanyi.mirra.navigation.TopLevelDestination
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictAppPreferencesSnapshotTest {
    @Test
    fun strictSnapshotKeepsAllFourPresentValues() = runTest {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("last_destination") to "knowledge",
            stringPreferencesKey("theme_id") to "night",
            booleanPreferencesKey("dnd_enabled") to true,
            booleanPreferencesKey("cross_app_intervention_enabled") to true,
        )

        val snapshot = repository(preferences).readStrictSnapshot()

        assertEquals(PreferenceSnapshotValue(TopLevelDestination.Knowledge, true), snapshot.portable.lastDestination)
        assertEquals(PreferenceSnapshotValue(MirraThemeId.NIGHT, true), snapshot.portable.themeId)
        assertEquals(PreferenceSnapshotValue(true, true), snapshot.portable.dndEnabled)
        assertEquals(PreferenceSnapshotValue(true, true), snapshot.portable.crossAppInterventionEnabled)
        assertEquals(StoredPreferenceValue.StringValue("knowledge"), snapshot.original.values["last_destination"])
        assertEquals(StoredPreferenceValue.StringValue("night"), snapshot.original.values["theme_id"])
        assertEquals(StoredPreferenceValue.BooleanValue(true), snapshot.original.values["dnd_enabled"])
        assertEquals(StoredPreferenceValue.BooleanValue(true), snapshot.original.values["cross_app_intervention_enabled"])
    }

    @Test
    fun missingKeysUseDeclaredDefaultsWithoutInventingOriginalValues() = runTest {
        val snapshot = repository(emptyPreferences()).readStrictSnapshot()

        assertEquals(PreferenceSnapshotValue(TopLevelDestination.Start, false), snapshot.portable.lastDestination)
        assertEquals(PreferenceSnapshotValue(MirraThemeId.BLUE, false), snapshot.portable.themeId)
        assertEquals(PreferenceSnapshotValue(false, false), snapshot.portable.dndEnabled)
        assertEquals(PreferenceSnapshotValue(false, false), snapshot.portable.crossAppInterventionEnabled)
        assertTrue(snapshot.original.values.isEmpty())
    }

    @Test
    fun partialSnapshotDistinguishesPresentDefaultsFromMissingKeys() = runTest {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("last_destination") to "start",
            booleanPreferencesKey("cross_app_intervention_enabled") to false,
        )

        val snapshot = repository(preferences).readStrictSnapshot()

        assertEquals(PreferenceSnapshotValue(TopLevelDestination.Start, true), snapshot.portable.lastDestination)
        assertEquals(PreferenceSnapshotValue(MirraThemeId.BLUE, false), snapshot.portable.themeId)
        assertEquals(PreferenceSnapshotValue(false, false), snapshot.portable.dndEnabled)
        assertEquals(PreferenceSnapshotValue(false, true), snapshot.portable.crossAppInterventionEnabled)
        assertEquals(setOf("last_destination", "cross_app_intervention_enabled"), snapshot.original.values.keys)
    }

    @Test
    fun invalidDestinationFailsStrictReadWhileUiKeepsItsFallback() = runTest {
        val repository = repository(mutablePreferencesOf(stringPreferencesKey("last_destination") to "unknown"))

        assertFails<IllegalArgumentException> { repository.readStrictSnapshot() }
        assertEquals(TopLevelDestination.Start, repository.lastDestination.first())
    }

    @Test
    fun invalidThemeFailsStrictReadWhileUiKeepsItsFallback() = runTest {
        val repository = repository(mutablePreferencesOf(stringPreferencesKey("theme_id") to "unknown"))

        assertFails<IllegalArgumentException> { repository.readStrictSnapshot() }
        assertEquals(MirraThemeId.BLUE, repository.themeId.first())
    }

    @Test
    fun everyKnownKeyRejectsAnExistingWrongType() = runTest {
        val preferencesWithWrongTypes = listOf(
            mutablePreferencesOf(booleanPreferencesKey("last_destination") to true),
            mutablePreferencesOf(intPreferencesKey("theme_id") to 3),
            mutablePreferencesOf(stringPreferencesKey("dnd_enabled") to "false"),
            mutablePreferencesOf(longPreferencesKey("cross_app_intervention_enabled") to 0L),
        )

        for (preferences in preferencesWithWrongTypes) {
            assertFails<IllegalArgumentException> { repository(preferences).readStrictSnapshot() }
        }
    }

    @Test
    fun ioFailurePropagatesFromStrictReadWhileAllUiFlowsKeepDefaults() = runTest {
        val failure = IOException("unreadable preferences")
        val repository = DefaultAppPreferencesRepository(ReadOnlyDataStore(flow { throw failure }))

        assertSame(failure, assertFails<IOException> { repository.readStrictSnapshot() })
        assertEquals(TopLevelDestination.Start, repository.lastDestination.first())
        assertEquals(MirraThemeId.BLUE, repository.themeId.first())
        assertFalse(repository.dndEnabled.first())
        assertFalse(repository.crossAppInterventionEnabled.first())
    }

    @Test
    fun corruptionPropagatesFromStrictReadWithoutDefaultSnapshot() = runTest {
        val failure = CorruptionException("malformed preferences")
        val repository = DefaultAppPreferencesRepository(ReadOnlyDataStore(flow { throw failure }))

        assertSame(failure, assertFails<CorruptionException> { repository.readStrictSnapshot() })
    }

    @Test
    fun nonIoReadFailurePropagatesUnchanged() = runTest {
        val failure = IllegalStateException("read failed")
        val repository = DefaultAppPreferencesRepository(ReadOnlyDataStore(flow { throw failure }))

        assertSame(failure, assertFails<IllegalStateException> { repository.readStrictSnapshot() })
    }

    @Test
    fun cancellationPropagatesWithoutDefaultSnapshot() = runTest {
        val failure = CancellationException("strict read cancelled")
        val repository = DefaultAppPreferencesRepository(ReadOnlyDataStore(flow { throw failure }))
        assertSame(failure, assertFails<CancellationException> { repository.readStrictSnapshot() })
    }

    @Test
    fun cancellingBeforeFirstSnapshotDoesNotReturnDefaults() = runTest {
        val started = CompletableDeferred<Unit>()
        var returned = false
        val repository = DefaultAppPreferencesRepository(ReadOnlyDataStore(flow<Preferences> {
            started.complete(Unit)
            awaitCancellation()
        }))
        val reader = launch {
            repository.readStrictSnapshot()
            returned = true
        }
        started.await()
        reader.cancel()
        reader.join()
        assertFalse(returned)
        assertTrue(reader.isCancelled)
    }

    @Test
    fun strictReadTakesAllValuesFromOneFirstEmission() = runTest {
        var collections = 0
        val repository = DefaultAppPreferencesRepository(ReadOnlyDataStore(flow {
            collections += 1
            if (collections == 1) {
                emit(mutablePreferencesOf(
                    stringPreferencesKey("last_destination") to "profile",
                    stringPreferencesKey("theme_id") to "mono",
                    booleanPreferencesKey("dnd_enabled") to true,
                    booleanPreferencesKey("cross_app_intervention_enabled") to false,
                    intPreferencesKey("unknown_revision") to 11,
                ))
            } else {
                emit(mutablePreferencesOf(
                    stringPreferencesKey("last_destination") to "start",
                    stringPreferencesKey("theme_id") to "blue",
                    booleanPreferencesKey("dnd_enabled") to false,
                    booleanPreferencesKey("cross_app_intervention_enabled") to true,
                    intPreferencesKey("unknown_revision") to 12,
                ))
            }
            throw AssertionError("Strict read continued after the first snapshot")
        }))

        val snapshot = repository.readStrictSnapshot()

        assertEquals(PreferenceSnapshotValue(TopLevelDestination.Profile, true), snapshot.portable.lastDestination)
        assertEquals(PreferenceSnapshotValue(MirraThemeId.MONO, true), snapshot.portable.themeId)
        assertEquals(PreferenceSnapshotValue(true, true), snapshot.portable.dndEnabled)
        assertEquals(PreferenceSnapshotValue(false, true), snapshot.portable.crossAppInterventionEnabled)
        assertEquals(StoredPreferenceValue.IntValue(11), snapshot.original.values["unknown_revision"])
        assertEquals(1, collections)
    }

    @Test
    fun originalSnapshotPreservesUnknownKeysWithAllSupportedTypes() = runTest {
        val preferences = mutablePreferencesOf(
            booleanPreferencesKey("unknown_boolean") to true,
            floatPreferencesKey("unknown_float") to 1.25f,
            doublePreferencesKey("unknown_double") to 2.5,
            intPreferencesKey("unknown_int") to -3,
            longPreferencesKey("unknown_long") to 4_000_000_000L,
            stringPreferencesKey("unknown_string") to "old value",
            stringSetPreferencesKey("unknown_set") to setOf("a", "b"),
            byteArrayPreferencesKey("unknown_bytes") to byteArrayOf(1, -2, 3),
        )

        val snapshot = repository(preferences).readStrictSnapshot()

        assertEquals(8, snapshot.original.values.size)
        assertEquals(StoredPreferenceValue.BooleanValue(true), snapshot.original.values["unknown_boolean"])
        assertEquals(StoredPreferenceValue.FloatValue(1.25f), snapshot.original.values["unknown_float"])
        assertEquals(StoredPreferenceValue.DoubleValue(2.5), snapshot.original.values["unknown_double"])
        assertEquals(StoredPreferenceValue.IntValue(-3), snapshot.original.values["unknown_int"])
        assertEquals(StoredPreferenceValue.LongValue(4_000_000_000L), snapshot.original.values["unknown_long"])
        assertEquals(StoredPreferenceValue.StringValue("old value"), snapshot.original.values["unknown_string"])
        assertEquals(setOf("a", "b"), (snapshot.original.values["unknown_set"] as StoredPreferenceValue.StringSetValue).value)
        assertArrayEquals(byteArrayOf(1, -2, 3), (snapshot.original.values["unknown_bytes"] as StoredPreferenceValue.ByteArrayValue).value)
        assertFalse(snapshot.portable.lastDestination.present)
        assertFalse(snapshot.portable.themeId.present)
        assertFalse(snapshot.portable.dndEnabled.present)
        assertFalse(snapshot.portable.crossAppInterventionEnabled.present)
    }

    @Test
    fun originalSnapshotDoesNotFollowLaterPreferenceChanges() = runTest {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("theme_id") to "mono",
            stringPreferencesKey("unknown") to "old",
        )
        val snapshot = repository(preferences).readStrictSnapshot()

        preferences[stringPreferencesKey("theme_id")] = "night"
        preferences[stringPreferencesKey("unknown")] = "new"
        preferences[booleanPreferencesKey("dnd_enabled")] = true

        assertEquals(PreferenceSnapshotValue(MirraThemeId.MONO, true), snapshot.portable.themeId)
        assertEquals(StoredPreferenceValue.StringValue("old"), snapshot.original.values["unknown"])
        assertFalse(snapshot.original.values.containsKey("dnd_enabled"))
    }

    @Test
    fun originalMapCannotBeChangedThroughItsSourceOrReturnedView() {
        val source = linkedMapOf("old" to StoredPreferenceValue.StringValue("value"))
        val snapshot = OriginalAppPreferencesSnapshot(source)
        source.clear()

        assertEquals(StoredPreferenceValue.StringValue("value"), snapshot.values["old"])
        assertFailsSync<UnsupportedOperationException> {
            (snapshot.values as MutableMap<String, StoredPreferenceValue>).clear()
        }
        assertEquals(1, snapshot.values.size)
    }

    @Test
    fun stringSetCannotBeChangedThroughItsSourceOrReturnedView() {
        val source = linkedSetOf("a", "b")
        val value = StoredPreferenceValue.StringSetValue(source)
        source.clear()

        assertEquals(setOf("a", "b"), value.value)
        assertFailsSync<UnsupportedOperationException> { (value.value as MutableSet<String>).clear() }
        assertEquals(setOf("a", "b"), value.value)
    }

    @Test
    fun byteArrayCannotBeChangedThroughItsSourceOrReturnedView() {
        val source = byteArrayOf(1, 2, 3)
        val value = StoredPreferenceValue.ByteArrayValue(source)
        source[0] = 9
        val returned = value.value
        returned[1] = 8

        assertArrayEquals(byteArrayOf(1, 2, 3), value.value)
    }

    @Test
    fun originalByteArrayAndStringSetStayDetachedAfterRead() = runTest {
        val preferences = mutablePreferencesOf(
            stringSetPreferencesKey("unknown_set") to setOf("a", "b"),
            byteArrayPreferencesKey("unknown_bytes") to byteArrayOf(1, 2, 3),
        )
        val snapshot = repository(preferences).readStrictSnapshot()
        preferences[stringSetPreferencesKey("unknown_set")] = setOf("c")
        preferences[byteArrayPreferencesKey("unknown_bytes")] = byteArrayOf(9)
        val bytes = snapshot.original.values["unknown_bytes"] as StoredPreferenceValue.ByteArrayValue
        bytes.value[0] = 8

        assertEquals(setOf("a", "b"), (snapshot.original.values["unknown_set"] as StoredPreferenceValue.StringSetValue).value)
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes.value)
    }

    @Test
    fun existingUiSettersKeepIndependentKeysAndValues() = runTest {
        val store = InMemoryDataStore(emptyPreferences())
        val repository: AppPreferencesRepository = DefaultAppPreferencesRepository(store)

        repository.setLastDestination(TopLevelDestination.Knowledge)
        repository.setThemeId(MirraThemeId.NIGHT)
        repository.setDndEnabled(true)
        repository.setCrossAppInterventionEnabled(true)
        repository.setCrossAppInterventionEnabled(false)

        assertEquals(TopLevelDestination.Knowledge, repository.lastDestination.first())
        assertEquals(MirraThemeId.NIGHT, repository.themeId.first())
        assertTrue(repository.dndEnabled.first())
        assertFalse(repository.crossAppInterventionEnabled.first())
        assertEquals(setOf("last_destination", "theme_id", "dnd_enabled", "cross_app_intervention_enabled"), store.data.first().asMap().keys.map { it.name }.toSet())
    }

    private fun repository(preferences: Preferences) =
        DefaultAppPreferencesRepository(ReadOnlyDataStore(flowOf(preferences)))

    private class ReadOnlyDataStore(override val data: Flow<Preferences>) : DataStore<Preferences> {
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            error("Strict snapshot and UI readers must not update preferences")
    }

    private class InMemoryDataStore(preferences: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(preferences)

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(data.value)
            data.value = updated
            return updated
        }
    }

    private suspend inline fun <reified T : Throwable> assertFails(block: suspend () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }

    private inline fun <reified T : Throwable> assertFailsSync(block: () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }
}
