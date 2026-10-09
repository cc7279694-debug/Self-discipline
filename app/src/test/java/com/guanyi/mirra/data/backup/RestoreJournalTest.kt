package com.guanyi.mirra.data.backup

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestoreJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Death : Error()
    private fun resources(name: String, value: String): RestoreResources {
        val root = temporary.newFolder(name)
        return RestoreResources(File(root, "database.sqlite"), File(root, "preferences.preferences_pb"), File(root, "images")).also {
            it.database.writeText("db-$value")
            it.preferences.writeText("prefs-$value")
            it.images.mkdirs()
            File(it.images, "image.jpg").writeText("image-$value")
        }
    }
    private fun values(resources: RestoreResources) = listOf(resources.database.readText(), resources.preferences.readText(), File(resources.images, "image.jpg").readText())
    private fun journal(live: RestoreResources, effect: (String) -> Unit = {}, verify: (RestoreResources) -> Unit = {}): RestoreJournal =
        RestoreJournal(temporary.newFolder(), live, jvmDurableFiles(), verify, effect)

    @Test fun successfulReplacementChangesAllResourcesAndClearsJournal() {
        val live = resources("live", "old")
        val candidate = resources("candidate", "new")
        val subject = journal(live)
        subject.apply(subject.prepare(candidate))
        assertEquals(values(candidate), values(live))
        assertFalse(subject.hasPendingRecovery())
    }

    @Test fun deathAtEveryPreCommitBoundaryRestoresCompleteOldGeneration() {
        for (boundary in listOf("PREPARED", "SWITCHING", "database:removed", "database:published", "preferences:removed", "preferences:published", "images:removed", "images:published", "VERIFIED")) {
            val live = resources("live-${boundary.replace(':', '-')}", "old")
            val candidate = resources("candidate-${boundary.replace(':', '-')}", "new")
            val root = temporary.newFolder()
            val crashed = RestoreJournal(root, live, jvmDurableFiles(), {}, { if (it == boundary) throw Death() })
            try { crashed.apply(crashed.prepare(candidate)); fail("Expected simulated process death: $boundary") } catch (_: Death) { }
            RestoreJournal(root, live, jvmDurableFiles()).recoverBeforeOpeningStores()
            assertEquals(listOf("db-old", "prefs-old", "image-old"), values(live))
        }
    }

    @Test fun deathAfterCommitRetainsCompleteNewGeneration() {
        for (boundary in listOf("COMMITTED", "CLEANUP_PENDING")) {
            val live = resources("live-$boundary", "old")
            val candidate = resources("candidate-$boundary", "new")
            val root = temporary.newFolder()
            val crashed = RestoreJournal(root, live, jvmDurableFiles(), {}, { if (it == boundary) throw Death() })
            try { crashed.apply(crashed.prepare(candidate)); fail() } catch (_: Death) { }
            RestoreJournal(root, live, jvmDurableFiles()).recoverBeforeOpeningStores()
            assertEquals(values(candidate), values(live))
        }
    }

    @Test fun verificationFailureRollsBackBeforeReopening() {
        val live = resources("live", "old")
        val candidate = resources("candidate", "new")
        val subject = journal(live, verify = { if (it.database == live.database && it.database.readText() == "db-new") throw IOException("candidate failed") })
        try { subject.apply(subject.prepare(candidate)); fail() } catch (_: RestoreRolledBackException) { }
        assertEquals(listOf("db-old", "prefs-old", "image-old"), values(live))
        assertFalse(subject.hasPendingRecovery())
    }

    @Test fun corruptJournalFailsClosedAndPreservesBothDataSets() {
        val live = resources("live", "old")
        val candidate = resources("candidate", "new")
        val root = temporary.newFolder()
        File(root, "restore.journal").writeText("corrupted")
        val subject = RestoreJournal(root, live, jvmDurableFiles())
        try { subject.recoverBeforeOpeningStores(); fail() } catch (_: RestoreSafetyException) { }
        assertEquals(listOf("db-old", "prefs-old", "image-old"), values(live))
        assertTrue(candidate.database.exists())
        assertTrue(subject.hasPendingRecovery())
    }

    @Test fun rollbackWithMissingOldSnapshotFailsClosed() {
        val live = resources("live", "old")
        val candidate = resources("candidate", "new")
        val root = temporary.newFolder()
        val subject = RestoreJournal(root, live, jvmDurableFiles(), {}, { if (it == "database:published") throw Death() })
        try { subject.apply(subject.prepare(candidate)); fail() } catch (_: Death) { }
        root.walkTopDown().first { it.name == "old" }.let { File(it, "database.sqlite").delete() }
        try { RestoreJournal(root, live, jvmDurableFiles()).recoverBeforeOpeningStores(); fail() } catch (_: RestoreSafetyException) { }
        assertTrue(File(root, "restore.journal").exists())
    }

    @Test fun absentOriginalPreferencesRemainAbsentAfterRollback() {
        val live = resources("live", "old").also { it.preferences.delete() }
        val candidate = resources("candidate", "new")
        val root = temporary.newFolder()
        val subject = RestoreJournal(root, live, jvmDurableFiles(), {}, { if (it == "images:published") throw Death() })
        try { subject.apply(subject.prepare(candidate)); fail() } catch (_: Death) { }
        RestoreJournal(root, live, jvmDurableFiles()).recoverBeforeOpeningStores()
        assertFalse(live.preferences.exists())
        assertEquals("db-old", live.database.readText())
    }

    @Test fun directoryDurabilityFailureBeforeJournalDoesNotChangeLiveData() {
        val live = resources("live", "old")
        val candidate = resources("candidate", "new")
        val subject = RestoreJournal(temporary.newFolder(), live, DurableFiles(syncDirectory = { throw IOException("sync failed") }))
        try { subject.prepare(candidate); fail() } catch (_: IOException) { }
        assertEquals(listOf("db-old", "prefs-old", "image-old"), values(live))
    }

    @Test fun committedGenerationCorruptionNeverRollsBackOrOpensMixedData() {
        val live = resources("live", "old")
        val candidate = resources("candidate", "new")
        val root = temporary.newFolder()
        val subject = RestoreJournal(root, live, jvmDurableFiles(), {}, { if (it == "COMMITTED") throw Death() })
        try { subject.apply(subject.prepare(candidate)); fail() } catch (_: Death) { }
        live.preferences.writeText("corruption")
        try { RestoreJournal(root, live, jvmDurableFiles()).recoverBeforeOpeningStores(); fail() } catch (_: RestoreSafetyException) { }
        assertEquals("db-new", live.database.readText())
        assertTrue(File(root, "restore.journal").exists())
    }
}
