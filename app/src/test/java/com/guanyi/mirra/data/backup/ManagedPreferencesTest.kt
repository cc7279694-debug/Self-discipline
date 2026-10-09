package com.guanyi.mirra.data.backup

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.guanyi.mirra.data.preferences.*
import com.guanyi.mirra.navigation.TopLevelDestination
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun portable() = PortableAppPreferencesSnapshot(
        PreferenceSnapshotValue(TopLevelDestination.Knowledge, true),
        PreferenceSnapshotValue(MirraThemeId.NIGHT, true),
        PreferenceSnapshotValue(true, true), PreferenceSnapshotValue(false, false),
    )
    @Test fun portableImportIsOneSnapshotAndRetainsMissingKeys() = runTest {
        val owner = ManagedPreferences(File(temporary.root, "prefs.preferences_pb"))
        try {
            owner.writePortable(portable())
            assertEquals(portable(), owner.repository.readStrictSnapshot().portable)
            assertEquals(3, owner.repository.readStrictSnapshot().original.values.size)
        } finally { owner.close() }
    }
    @Test fun closedInstanceCanBeReopenedWithoutSecondActiveDataStore() = runTest {
        val file = File(temporary.root, "prefs.preferences_pb")
        ManagedPreferences(file).let { it.writePortable(portable()); it.close() }
        val reopened = ManagedPreferences(file)
        try { assertEquals(portable(), reopened.repository.readStrictSnapshot().portable) } finally { reopened.close() }
    }
    @Test fun originalRawFileIncludesUnknownTypedKeysForRollback() = runTest {
        val file = File(temporary.root, "prefs.preferences_pb")
        val owner = ManagedPreferences(file)
        owner.dataStore.edit { it[stringPreferencesKey("unknown_private_key")] = "keep exactly" }
        owner.close()
        val saved = file.readBytes()
        // Build imported preferences in a NEW staging file, exactly as production restore does.
        // Android's live setting updates are tested on Android, not Windows FileStorage.renameTo.
        val staging = File(temporary.root, "staging.preferences_pb")
        val second = ManagedPreferences(staging)
        second.writePortable(portable())
        second.close()
        file.writeBytes(staging.readBytes())
        file.writeBytes(saved)
        val rolledBack = ManagedPreferences(file)
        try {
            val original = rolledBack.repository.readStrictSnapshot().original
            assertEquals(StoredPreferenceValue.StringValue("keep exactly"), original.values["unknown_private_key"])
            assertFalse(rolledBack.repository.readStrictSnapshot().portable.themeId.present)
        } finally { rolledBack.close() }
    }
    @Test fun duplicateActiveOwnerIsRejectedBeforeOpeningFile() = runTest {
        val file = File(temporary.root, "prefs.preferences_pb")
        val owner = ManagedPreferences(file)
        try {
            try { ManagedPreferences(file); fail("Duplicate owner accepted") } catch (_: IllegalStateException) { }
            owner.writePortable(portable())
        } finally { owner.close() }
    }
    @Test fun closeIsIdempotentAndClosedOwnerCannotImport() = runTest {
        val owner = ManagedPreferences(File(temporary.root, "prefs.preferences_pb"))
        owner.close()
        owner.close()
        try { owner.writePortable(portable()); fail() } catch (_: IllegalStateException) { }
    }
}
