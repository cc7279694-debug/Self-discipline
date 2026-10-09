package com.guanyi.mirra

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.domain.backup.FullBackupService
import com.guanyi.mirra.domain.backup.PreparedBackup
import com.guanyi.mirra.domain.backup.RestoreCandidate
import com.guanyi.mirra.feature.datamanagement.DataManagementOperation
import com.guanyi.mirra.feature.datamanagement.DataManagementUiState
import com.guanyi.mirra.feature.datamanagement.DataManagementViewModel
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real lifecycle/dispatcher boundaries; all service resources are this test's synthetic cache. */
@RunWith(AndroidJUnit4::class)
class DataManagementLifecycleTest {
    @Test fun clearingViewModelDiscardsBackupWaitingForDocumentPicker() = runBlocking {
        withFixture { fixture ->
            fixture.prepareBackup()
            withContext(Dispatchers.Main.immediate) {
                fixture.vm.exportPickerLaunched(checkNotNull(fixture.vm.uiState.value.exportRequest).id)
                fixture.store.clear()
            }
            fixture.awaitEvent(fixture.service.backupDiscarded, "The cleared picker owner must discard its private backup")
            fixture.awaitState { it.preparedBackup == null }
            assertFalse(fixture.service.backupFile.exists())
            assertEquals(1, fixture.service.backupDiscards.get())
            assertNull(fixture.vm.uiState.value.exportRequest)
        }
    }

    @Test fun clearingViewModelDiscardsValidatedRestoreCandidate() = runBlocking {
        withFixture { fixture ->
            fixture.inspectBackup()
            withContext(Dispatchers.Main.immediate) { fixture.store.clear() }
            fixture.awaitEvent(fixture.service.candidateDiscarded, "The cleared confirmation owner must discard its private candidate")
            fixture.awaitState { it.restoreCandidate == null }
            assertFalse(fixture.service.candidateFile.exists())
            assertEquals(1, fixture.service.candidateDiscards.get())
            assertFalse(fixture.vm.uiState.value.restoreCompleted)
        }
    }

    @Test fun repeatedStoreClearingDiscardsTheCapturedBackupOnlyOnce() = runBlocking {
        withFixture { fixture ->
            fixture.prepareBackup()
            withContext(Dispatchers.Main.immediate) { fixture.store.clear(); fixture.store.clear() }
            fixture.awaitEvent(fixture.service.backupDiscarded, "Repeated clearing must still release the original handle")
            fixture.awaitState { it.preparedBackup == null }
            withContext(Dispatchers.Main.immediate) { fixture.store.clear() }
            assertEquals(1, fixture.service.backupDiscards.get())
            assertFalse(fixture.service.backupFile.exists())
        }
    }

    @Test fun clearingDuringSaveWaitsForTheWriterAndDoesNotDiscardTwice() = runBlocking {
        withFixture { fixture ->
            fixture.service.holdSave = true
            fixture.prepareBackup()
            withContext(Dispatchers.Main.immediate) { fixture.vm.exportDestinationSelected(fixture.document) }
            fixture.awaitEvent(fixture.service.saveStarted, "The private backup writer must actually start")
            withContext(Dispatchers.Main.immediate) { fixture.store.clear() }
            assertTrue("A writer still using the package must retain it", fixture.service.backupFile.isFile)
            assertEquals(0, fixture.service.backupDiscards.get())
            fixture.service.finishSave.complete(Unit)
            fixture.awaitEvent(fixture.service.saveFinished, "The held writer must finish before cleanup")
            fixture.awaitEvent(fixture.service.backupDiscarded, "The cancelled writer must release its private backup")
            fixture.awaitState { it.preparedBackup == null && it.result == null }
            assertFalse(fixture.service.backupFile.exists())
            assertEquals("The writer's finally and lifecycle cleanup must not both discard", 1, fixture.service.backupDiscards.get())
            assertNull(fixture.vm.uiState.value.result)
        }
    }

    @Test fun clearingDuringRestoreWaitsForTheReplacementBeforeDiscardingCandidate() = runBlocking {
        withFixture { fixture ->
            fixture.service.holdRestore = true
            fixture.inspectBackup()
            withContext(Dispatchers.Main.immediate) {
                fixture.vm.requestRestoreConfirmation()
                fixture.vm.confirmRestore()
            }
            fixture.awaitEvent(fixture.service.restoreStarted, "The candidate consumer must actually start")
            withContext(Dispatchers.Main.immediate) { fixture.store.clear() }
            assertTrue("The active replacement must retain its candidate", fixture.service.candidateFile.isFile)
            assertEquals(0, fixture.service.candidateDiscards.get())
            fixture.service.finishRestore.complete(Unit)
            fixture.awaitEvent(fixture.service.restoreFinished, "The held replacement must finish before cleanup")
            fixture.awaitEvent(fixture.service.candidateDiscarded, "The cleared owner must release the completed consumer's candidate")
            fixture.awaitState { it.restoreCandidate == null }
            assertFalse(fixture.service.candidateFile.exists())
            assertEquals(1, fixture.service.candidateDiscards.get())
            assertFalse("Destruction must not fabricate confirmed restore success", fixture.vm.uiState.value.restoreCompleted)
            assertNull(fixture.vm.uiState.value.result)
        }
    }

    @Test fun lifecycleCleanupFailureRetainsTheHandleAndDoesNotClaimSuccess() = runBlocking {
        withFixture { fixture ->
            fixture.service.failBackupDiscard = true
            fixture.prepareBackup()
            withContext(Dispatchers.Main.immediate) { fixture.store.clear() }
            fixture.awaitEvent(fixture.service.backupDiscardAttempted, "A cleared owner must attempt private backup cleanup")
            val state = fixture.awaitState { it.error != null }
            assertTrue(fixture.service.backupFile.isFile)
            assertEquals(fixture.service.prepared, state.preparedBackup)
            assertNull(state.result)
            assertFalse(state.restoreCompleted)
            assertFalse("Private cache paths must not escape through lifecycle errors", checkNotNull(state.error).contains(fixture.service.backupFile.path))
            assertEquals(1, fixture.service.backupDiscards.get())
        }
    }

    private suspend fun withFixture(block: suspend (LifecycleFixture) -> Unit) {
        val fixture = LifecycleFixture.create()
        try { block(fixture) } finally { fixture.close() }
    }
}

private class LifecycleFixture private constructor(
    private val root: File,
    val service: LifecycleService,
    val vm: DataManagementViewModel,
    val store: ViewModelStore,
    private val vmJob: Job,
) {
    val document: Uri = Uri.fromFile(File(root, "document.zip"))

    suspend fun prepareBackup() {
        withContext(Dispatchers.Main.immediate) { vm.createBackup() }
        awaitState { it.operation == DataManagementOperation.AWAITING_EXPORT_LOCATION }
        assertEquals(service.prepared, vm.uiState.value.preparedBackup)
        assertTrue(service.backupFile.isFile)
    }

    suspend fun inspectBackup() {
        withContext(Dispatchers.Main.immediate) { vm.requestImport(); vm.importSourceSelected(document) }
        awaitState { it.restoreCandidate != null }
        assertEquals(service.candidate, vm.uiState.value.restoreCandidate)
        assertTrue(service.candidateFile.isFile)
    }

    suspend fun awaitState(predicate: (DataManagementUiState) -> Boolean): DataManagementUiState =
        withTimeout(30_000) { vm.uiState.first(predicate) }

    suspend fun awaitEvent(event: CompletableDeferred<Unit>, reason: String) {
        try { withTimeout(30_000) { event.await() } }
        catch (_: TimeoutCancellationException) { fail(reason) }
    }

    suspend fun close() = withContext(NonCancellable) {
        service.finishSave.complete(Unit)
        service.finishRestore.complete(Unit)
        withContext(Dispatchers.Main.immediate) { store.clear() }
        withTimeout(30_000) { vmJob.join() }
        withContext(Dispatchers.IO) {
            service.serial.withLock {
                check(root.name.matches(Regex("data-management-lifecycle-[0-9a-f-]{36}")))
                val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
                check(root.canonicalFile.parentFile == cache && root.canonicalFile == root.absoluteFile)
                check(root.deleteRecursively()) { "Only this synthetic lifecycle cache may be removed" }
            }
        }
    }

    companion object {
        suspend fun create(): LifecycleFixture {
            val root = withContext(Dispatchers.IO) {
                val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
                File(cache, "data-management-lifecycle-${UUID.randomUUID()}").also {
                    check(it.canonicalFile.parentFile == cache && !it.exists() && it.mkdir())
                }
            }
            val service = withContext(Dispatchers.IO) { LifecycleService(root) }
            return withContext(Dispatchers.Main.immediate) {
                val vm = DataManagementViewModel(service)
                val store = ViewModelStore().apply { put("data-management", vm) }
                LifecycleFixture(root, service, vm, store, checkNotNull(vm.viewModelScope.coroutineContext[Job]))
            }
        }
    }
}

private class LifecycleService(root: File) : FullBackupService {
    val serial = Mutex()
    val backupFile = File(root, "private-backup.zip").apply { writeText("Synthetic lifecycle backup only") }
    val candidateFile = File(root, "private-candidate.txt").apply { writeText("Synthetic lifecycle candidate only") }
    val prepared = PreparedBackup(backupFile, 1_791_425_600_000L, 1, 1, 0)
    val candidate = RestoreCandidate("lifecycle-${UUID.randomUUID()}", 1_791_425_600_000L, 1, 1, 0)
    val backupDiscards = AtomicInteger()
    val candidateDiscards = AtomicInteger()
    val backupDiscardAttempted = CompletableDeferred<Unit>()
    val backupDiscarded = CompletableDeferred<Unit>()
    val candidateDiscarded = CompletableDeferred<Unit>()
    val saveStarted = CompletableDeferred<Unit>()
    val saveFinished = CompletableDeferred<Unit>()
    val restoreStarted = CompletableDeferred<Unit>()
    val restoreFinished = CompletableDeferred<Unit>()
    val finishSave = CompletableDeferred<Unit>()
    val finishRestore = CompletableDeferred<Unit>()
    var holdSave = false
    var holdRestore = false
    var failBackupDiscard = false

    override suspend fun prepareBackup() = prepared
    override suspend fun inspectBackup(source: Uri) = candidate

    override suspend fun saveBackup(prepared: PreparedBackup, destination: Uri) = serial.withLock {
        check(prepared == this.prepared && backupFile.isFile)
        saveStarted.complete(Unit)
        if (holdSave) withContext(NonCancellable) { finishSave.await() }
        check(backupFile.isFile) { "Cleanup must not remove a package while the writer is using it" }
        saveFinished.complete(Unit)
        Unit
    }

    override suspend fun restore(candidate: RestoreCandidate) = serial.withLock {
        check(candidate == this.candidate && candidateFile.isFile)
        restoreStarted.complete(Unit)
        if (holdRestore) withContext(NonCancellable) { finishRestore.await() }
        check(candidateFile.isFile) { "Cleanup must not remove a candidate while replacement is using it" }
        restoreFinished.complete(Unit)
        Unit
    }

    override suspend fun discardPreparedBackup(prepared: PreparedBackup) = serial.withLock {
        check(prepared == this.prepared)
        backupDiscards.incrementAndGet()
        backupDiscardAttempted.complete(Unit)
        check(!failBackupDiscard) { backupFile.path }
        if (backupFile.exists()) check(backupFile.delete())
        backupDiscarded.complete(Unit)
        Unit
    }

    override suspend fun discardRestoreCandidate(candidate: RestoreCandidate) = serial.withLock {
        check(candidate == this.candidate)
        candidateDiscards.incrementAndGet()
        if (candidateFile.exists()) check(candidateFile.delete())
        candidateDiscarded.complete(Unit)
        Unit
    }
}
