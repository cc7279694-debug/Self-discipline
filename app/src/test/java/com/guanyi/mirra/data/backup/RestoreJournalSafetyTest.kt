package com.guanyi.mirra.data.backup

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Decision metadata must be protected independently of the hashes of resource bytes. */
class RestoreJournalSafetyTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Death : Error()

    private fun resources(name: String, value: String): RestoreResources {
        val root = temporary.newFolder(name)
        return RestoreResources(
            File(root, "database.sqlite"),
            File(root, "preferences.preferences_pb"),
            File(root, "images"),
        ).also {
            it.database.writeText("database-$value")
            it.preferences.writeText("preferences-$value")
            check(it.images.mkdir())
            File(it.images, "image.jpg").writeText("image-$value")
        }
    }

    @Test fun alteredPrecommitDecisionCannotSelectCandidateOrDeleteSafetySnapshot() {
        val live = resources("live", "old")
        val candidate = resources("candidate", "new")
        val root = temporary.newFolder("journal")
        val crashed = RestoreJournal(root, live, jvmDurableFiles(), effect = {
            if (it == "VERIFIED") throw Death()
        })
        try {
            crashed.apply(crashed.prepare(candidate))
            fail("Expected simulated death before COMMITTED")
        } catch (_: Death) {
            // Resource bytes are complete, but no commit decision has been durably recorded.
        }

        val pointer = File(root, "restore.journal")
        val original = pointer.readText()
        assertTrue(original.contains("state=SWITCHING"))
        pointer.writeText(original.replace("state=SWITCHING", "state=COMMITTED"))
        val oldDatabase = root.walkTopDown().first {
            it.name == "database.sqlite" && it.parentFile?.name == "old"
        }

        try {
            RestoreJournal(root, live, jvmDurableFiles()).recoverBeforeOpeningStores()
            fail("Changed journal metadata was accepted as a commit decision")
        } catch (_: RestoreSafetyException) {
            // Fail closed: no generation may be opened on an unverified decision.
        }

        assertTrue(pointer.exists())
        assertTrue(oldDatabase.exists())
        assertEquals("database-old", oldDatabase.readText())
        assertEquals("database-new", live.database.readText())
    }

    @Test fun pointerAbsenceAfterCleanupSyncFailureDoesNotProveRollbackToOldData() {
        val live = resources("live-cleanup", "old")
        val candidate = resources("candidate-cleanup", "new")
        val root = temporary.newFolder("journal-cleanup")
        val pointer = File(root, "restore.journal")
        val adapter = jvmDurableFiles()
        var cleanupRecorded = false
        var deletionSyncFailed = false
        val subject = RestoreJournal(root, live, DurableFiles(
            syncDirectory = { directory ->
                if (cleanupRecorded && directory == root && !pointer.exists()) {
                    deletionSyncFailed = true
                    throw IOException("Pointer deletion durability is uncertain")
                }
            },
            atomicReplace = adapter.atomicReplace,
        ), effect = { if (it == "CLEANUP_PENDING") cleanupRecorded = true })

        try {
            subject.apply(subject.prepare(candidate))
            fail("The cleanup directory sync failure must propagate")
        } catch (failure: IOException) {
            assertEquals("Pointer deletion durability is uncertain", failure.message)
        }

        assertTrue("The fault must occur after the durable cleanup decision", cleanupRecorded)
        assertTrue("The pointer-deletion sync boundary must actually be reached", deletionSyncFailed)
        assertFalse("Pointer absence is not a rollback outcome", subject.hasPendingRecovery())
        assertEquals("database-new", live.database.readText())
        assertEquals("preferences-new", live.preferences.readText())
        assertEquals("image-new", File(live.images, "image.jpg").readText())
        val retainedOldDatabase = root.walkTopDown().first {
            it.name == "database.sqlite" && it.parentFile?.name == "old"
        }
        assertEquals("Cleanup failure must retain the old safety snapshot", "database-old", retainedOldDatabase.readText())
        // The host must fail closed on the reported exception. It cannot reinterpret this
        // complete NEW generation as OLD merely because hasPendingRecovery() now returns false.
    }
}
