package com.guanyi.mirra.data.export

import com.guanyi.mirra.data.backup.DurableFiles
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExportWorkspaceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val current = "11111111-1111-1111-1111-111111111111"
    private val previous = "22222222-2222-2222-2222-222222222222"
    private val operation = "33333333-3333-3333-3333-333333333333"
    private val files = DurableFiles(syncDirectory = {})

    @Test fun previousProcessAndLegacyDirectoriesAreReclaimedWithTheirPrivateContents() {
        val base = temporary.newFolder("exports")
        val stale = directory(base, "${previous}_$operation")
        val legacy = directory(base, "44444444-4444-4444-4444-444444444444")
        val unrelatedSibling = temporary.newFolder("outside")
        val outside = File(unrelatedSibling, "keep.txt").apply { writeText("outside") }
        ExportWorkspace(base, files, current).reclaimPreviousProcesses()
        assertFalse(stale.exists())
        assertFalse(legacy.exists())
        assertEquals("outside", outside.readText())
        assertTrue(base.isDirectory)
    }

    @Test fun currentProcessDirectoriesRemainAvailableToOtherStorageGenerations() {
        val base = temporary.newFolder("current")
        val active = directory(base, "${current}_$operation")
        val second = directory(base, "${current}_44444444-4444-4444-4444-444444444444")
        ExportWorkspace(base, files, current).reclaimPreviousProcesses()
        ExportWorkspace(base, files, current).reclaimPreviousProcesses()
        assertEquals("private contents", File(active, "input/facts.json").readText())
        assertEquals("private contents", File(second, "input/facts.json").readText())
    }

    @Test fun unknownDirectoriesAndOrdinaryFilesArePreserved() {
        val base = temporary.newFolder("unknown")
        val names = listOf("backup-work", "notes", "${previous}_not-a-uuid", "${previous}_${operation}_extra")
        val preserved = names.map { directory(base, it) }
        val uuidFile = File(base, "55555555-5555-5555-5555-555555555555").apply { writeText("ordinary file") }
        val note = File(base, "README.txt").apply { writeText("user explanation") }
        ExportWorkspace(base, files, current).reclaimPreviousProcesses()
        preserved.forEach { assertEquals("private contents", File(it, "input/facts.json").readText()) }
        assertEquals("ordinary file", uuidFile.readText())
        assertEquals("user explanation", note.readText())
    }

    @Test fun newOperationCreatesOnlyBaseAndReturnsUniqueCurrentProcessNamesAfterReclamation() {
        val base = File(temporary.root, "new-base")
        val subject = ExportWorkspace(base, files, current)
        val first = subject.newOperationDirectory()
        assertTrue(base.isDirectory)
        assertFalse("Service owns the actual operation mkdir", first.exists())
        assertEquals(base.canonicalFile, first.canonicalFile.parentFile)
        assertTrue(first.name.startsWith("${current}_"))
        assertEquals(first.name.substringAfter('_'), UUID.fromString(first.name.substringAfter('_')).toString())
        val stale = directory(base, "${previous}_$operation")
        val second = subject.newOperationDirectory()
        assertFalse(stale.exists())
        assertFalse(first == second)
        assertFalse(second.exists())
    }

    @Test fun defaultProcessIdentityIsSharedAcrossWorkspaceInstancesAndGenerations() {
        val base = temporary.newFolder("generations")
        val first = ExportWorkspace(base, files).newOperationDirectory()
        directory(base, first.name)
        val second = ExportWorkspace(base, files).newOperationDirectory()
        assertEquals(first.name.substringBefore('_'), second.name.substringBefore('_'))
        assertFalse(first.name == second.name)
        ExportWorkspace(base, files).reclaimPreviousProcesses()
        assertEquals("private contents", File(first, "input/facts.json").readText())
    }

    @Test fun malformedProcessIdentityAndBroadDotBaseAreRefused() {
        val base = temporary.newFolder("invalid")
        listOf("", "not-a-uuid", "../outside", "1-1-1-1-1", "$current/$operation").forEach { processId ->
            try { ExportWorkspace(base, files, processId); fail("Unsafe process identity was accepted: $processId") }
            catch (_: IllegalArgumentException) { }
        }
        listOf(File(base, "."), File(base, ".."), base.absoluteFile.toPath().root.toFile()).forEach { invalid ->
            try { ExportWorkspace(invalid, files, current).reclaimPreviousProcesses(); fail("Broad/ambiguous base was accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun canonicalBaseAndChildEscapeFailClosedWithoutDeletingExternalResources() {
        val base = temporary.newFolder("boundary")
        val outside = temporary.newFolder("protected")
        val keep = File(outside, "keep.txt").apply { writeText("protected") }
        // Canonical-path fault injection exercises the real DurableFiles guard without requiring host symlink privileges.
        val aliasedBase = object : File(base.path) { override fun getCanonicalFile(): File = outside }
        rejectedBoundary { ExportWorkspace(aliasedBase, files, current).reclaimPreviousProcesses() }
        val realChild = directory(base, "${previous}_$operation")
        val escapedChild = object : File(realChild.path) { override fun getCanonicalFile(): File = outside }
        val childList = object : File(base.path) { override fun listFiles(): Array<File> = arrayOf(escapedChild) }
        rejectedBoundary { ExportWorkspace(childList, files, current).reclaimPreviousProcesses() }
        assertEquals("protected", keep.readText())
        assertTrue(realChild.exists())
    }

    @Test fun deletionFailurePropagatesAndDoesNotReturnANewOperationHandle() {
        val base = temporary.newFolder("delete-failure")
        val stale = directory(base, "${previous}_$operation")
        val undeletable = object : File(stale.path) { override fun delete(): Boolean = false }
        val entries = object : File(base.path) { override fun listFiles(): Array<File> = arrayOf(undeletable) }
        try {
            ExportWorkspace(entries, files, current).newOperationDirectory()
            fail("Deletion failure must abort preparation")
        } catch (failure: IOException) { assertEquals("Cleanup failed", failure.message) }
        assertTrue(stale.exists())
    }

    @Test fun directoryEnumerationFailureIsNotTreatedAsAnEmptyCleanWorkspace() {
        val base = temporary.newFolder("enumeration-failure")
        val unknown = directory(base, "keep")
        val unreadable = object : File(base.path) { override fun listFiles(): Array<File>? = null }
        try { ExportWorkspace(unreadable, files, current).reclaimPreviousProcesses(); fail("Enumeration failure must propagate") }
        catch (_: IOException) { }
        assertEquals("private contents", File(unknown, "input/facts.json").readText())
    }

    @Test fun concurrentWorkspaceInstancesCanReclaimRepeatedlyWithoutDeletingCurrentOperations() {
        val base = temporary.newFolder("concurrent")
        repeat(80) { directory(base, "${previous}_${UUID.randomUUID()}") }
        val active = directory(base, "${current}_$operation")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 8).map {
                pool.submit {
                    start.await()
                    repeat(3) { ExportWorkspace(base, files, current).reclaimPreviousProcesses() }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(listOf(active.name), base.listFiles().orEmpty().map { it.name })
        assertEquals("private contents", File(active, "input/facts.json").readText())
    }

    private fun directory(base: File, name: String): File = File(base, name).also {
        check(File(it, "input").mkdirs())
        File(it, "input/facts.json").writeText("private contents")
    }

    private fun rejectedBoundary(block: () -> Unit) {
        try { block(); fail("Canonical path escape must fail closed") }
        catch (_: IOException) { }
        catch (_: IllegalArgumentException) { }
    }
}
