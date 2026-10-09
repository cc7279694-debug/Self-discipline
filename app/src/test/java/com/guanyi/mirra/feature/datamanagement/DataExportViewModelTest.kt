package com.guanyi.mirra.feature.datamanagement

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.domain.backup.DataExportFormat
import com.guanyi.mirra.domain.backup.DataExportService
import com.guanyi.mirra.domain.backup.FullBackupErrorCode
import com.guanyi.mirra.domain.backup.FullBackupOperationException
import com.guanyi.mirra.domain.backup.FullBackupService
import com.guanyi.mirra.domain.backup.PreparedBackup
import com.guanyi.mirra.domain.backup.PreparedDataExport
import com.guanyi.mirra.domain.backup.RestoreCandidate
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DataExportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val createdAt = 1_791_425_600_000L
    private val json = PreparedDataExport(File("private-export.json"), createdAt, DataExportFormat.JSON)
    private val csv = PreparedDataExport(File("private-export.zip"), createdAt, DataExportFormat.CSV_ZIP)

    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun explicitPrivacyConfirmationIsRequiredBeforePreparingAnyFacts() = runTest(dispatcher) {
        val formats = mutableListOf<DataExportFormat>()
        val vm = vm(export = export(prepare = { formats += it; json }))
        try {
            vm.confirmDataExport(); runCurrent()
            assertTrue(formats.isEmpty())
            vm.requestDataExport(DataExportFormat.JSON); runCurrent()
            assertEquals(DataExportFormat.JSON, vm.uiState.value.pendingDataExportFormat)
            assertTrue(formats.isEmpty())
            assertNull(vm.uiState.value.dataExportRequest)
            vm.confirmDataExport(); runCurrent()
            assertEquals(listOf(DataExportFormat.JSON), formats)
            assertEquals(DataManagementOperation.AWAITING_DATA_EXPORT_LOCATION, vm.uiState.value.operation)
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun dismissingConfirmationCreatesNoPrivateFileOrSystemRequest() = runTest(dispatcher) {
        var preparations = 0
        val vm = vm(export = export(prepare = { preparations++; json }))
        try {
            vm.requestDataExport(DataExportFormat.CSV_ZIP)
            assertEquals(DataExportFormat.CSV_ZIP, vm.uiState.value.pendingDataExportFormat)
            vm.dismissDataExportConfirmation(); vm.confirmDataExport(); runCurrent()
            assertNull(vm.uiState.value.pendingDataExportFormat)
            assertEquals(0, preparations)
            assertNull(vm.uiState.value.preparedDataExport)
            assertNull(vm.uiState.value.dataExportRequest)
            assertEquals(DataManagementOperation.IDLE, vm.uiState.value.operation)
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun jsonAndCsvRequestTheirOwnMimeTypeAndExtensionOnlyAfterPreparation() = runTest(dispatcher) {
        for ((format, prepared, mime, suffix) in listOf(
            ExportCase(DataExportFormat.JSON, json, "application/json", ".json"),
            ExportCase(DataExportFormat.CSV_ZIP, csv, "application/zip", ".zip"),
        )) {
            val response = CompletableDeferred<PreparedDataExport>()
            val vm = vm(export = export(prepare = { response.await() }))
            try {
                vm.requestDataExport(format); vm.confirmDataExport(); runCurrent()
                assertEquals(DataManagementOperation.PREPARING_DATA_EXPORT, vm.uiState.value.operation)
                assertNull(vm.uiState.value.dataExportRequest)
                response.complete(prepared); runCurrent()
                val request = vm.uiState.value.dataExportRequest!!
                assertEquals(mime, request.mimeType)
                assertTrue(request.fileName.startsWith("Mirra-export-"))
                assertTrue(request.fileName.endsWith(suffix))
                assertEquals(prepared, vm.uiState.value.preparedDataExport)
                assertEquals(request, vm.uiState.value.activeDataExportRequest)
                vm.dataExportPickerLaunched(request.id)
                assertNull(vm.uiState.value.dataExportRequest)
                assertEquals(request, vm.uiState.value.activeDataExportRequest)
            } finally { response.cancel(); vm.viewModelScope.cancel(); runCurrent() }
        }
    }

    @Test fun cancellingSystemPickerCleansOnlyPreparedExportAndKeepsActionsAvailable() = runTest(dispatcher) {
        val discarded = mutableListOf<PreparedDataExport>()
        var saves = 0
        val vm = vm(export = export(prepare = { json }, save = { _, _ -> saves++ }, discard = { discarded += it }))
        try {
            val request = prepare(vm, DataExportFormat.JSON)
            vm.dataExportDestinationSelected(request.id, null); runCurrent()
            assertEquals(listOf(json), discarded)
            assertEquals(0, saves)
            assertNull(vm.uiState.value.preparedDataExport)
            assertNull(vm.uiState.value.activeDataExportRequest)
            assertNull(vm.uiState.value.dataExportRequest)
            assertEquals(DataManagementOperation.IDLE, vm.uiState.value.operation)
            assertTrue(vm.uiState.value.canStartOperation)
            assertNull(vm.uiState.value.error)
            assertTrue(vm.uiState.value.result!!.contains("当前数据未改变"))
            assertFalse(vm.uiState.value.restoreCompleted)
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun duplicateAndOldPickerResultsCannotConsumeTheCurrentExport() = runTest(dispatcher) {
        var saves = 0
        val discarded = mutableListOf<PreparedDataExport>()
        val vm = vm(export = export(prepare = { json }, save = { _, _ -> saves++ }, discard = { discarded += it }))
        try {
            val old = prepare(vm, DataExportFormat.JSON)
            vm.dataExportDestinationSelected(old.id, null); runCurrent()
            val current = prepare(vm, DataExportFormat.JSON)
            assertNotEquals(old.id, current.id)
            vm.dataExportDestinationSelected(old.id, null); runCurrent()
            assertEquals(0, saves)
            assertEquals(DataManagementOperation.AWAITING_DATA_EXPORT_LOCATION, vm.uiState.value.operation)
            vm.dataExportDestinationSelected(current.id, null)
            vm.dataExportDestinationSelected(current.id, null); runCurrent()
            assertEquals(0, saves)
            assertEquals(listOf(json, json), discarded)
            assertNull(vm.uiState.value.preparedDataExport)
            assertTrue(vm.uiState.value.result!!.contains("已取消"))
            assertFalse(vm.uiState.value.restoreCompleted)
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun preparingExportPreventsParallelBackupImportOrExport() = runTest(dispatcher) {
        val response = CompletableDeferred<PreparedDataExport>()
        var preparations = 0
        var backups = 0
        val vm = vm(backup = backup(prepare = { backups++; error("unexpected backup") }),
            export = export(prepare = { preparations++; response.await() }))
        try {
            vm.requestDataExport(DataExportFormat.JSON); vm.confirmDataExport(); runCurrent()
            vm.createBackup(); vm.requestImport(); vm.requestDataExport(DataExportFormat.CSV_ZIP); vm.confirmDataExport(); runCurrent()
            assertEquals(1, preparations)
            assertEquals(0, backups)
            assertNull(vm.uiState.value.importRequest)
            assertNull(vm.uiState.value.exportRequest)
            assertNull(vm.uiState.value.pendingDataExportFormat)
            assertEquals(DataManagementOperation.PREPARING_DATA_EXPORT, vm.uiState.value.operation)
        } finally { response.cancel(); vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun pendingConfirmationPreventsBackupUntilDismissed() = runTest(dispatcher) {
        var backups = 0
        val vm = vm(backup = backup(prepare = {
            backups++; PreparedBackup(File("backup.zip"), createdAt, 0, 0, 0)
        }))
        try {
            vm.requestDataExport(DataExportFormat.JSON)
            vm.createBackup(); vm.requestImport(); runCurrent()
            assertEquals(0, backups)
            assertNull(vm.uiState.value.importRequest)
            vm.dismissDataExportConfirmation(); vm.createBackup(); runCurrent()
            assertEquals(1, backups)
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun activeBackupDoesNotOpenExportConfirmation() = runTest(dispatcher) {
        val response = CompletableDeferred<PreparedBackup>()
        val vm = vm(backup = backup(prepare = { response.await() }))
        try {
            vm.createBackup(); runCurrent()
            vm.requestDataExport(DataExportFormat.JSON); vm.confirmDataExport(); runCurrent()
            assertNull(vm.uiState.value.pendingDataExportFormat)
            assertNull(vm.uiState.value.preparedDataExport)
            assertEquals(DataManagementOperation.PREPARING_BACKUP, vm.uiState.value.operation)
        } finally { response.cancel(); vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun deniedPreparationDoesNotOpenPickerOrRevealPrivateFailure() = runTest(dispatcher) {
        for (code in listOf(FullBackupErrorCode.ACTIVE_LEARNING, FullBackupErrorCode.LOW_SPACE,
            FullBackupErrorCode.STORAGE_BUSY, FullBackupErrorCode.OWNED_CLEANUP)) {
            val vm = vm(export = export(prepare = {
                throw FullBackupOperationException(code, IllegalStateException("private-note-and-package/path"))
            }))
            try {
                vm.requestDataExport(DataExportFormat.JSON); vm.confirmDataExport(); runCurrent()
                assertEquals(DataManagementOperation.IDLE, vm.uiState.value.operation)
                assertNull(vm.uiState.value.dataExportRequest)
                assertNull(vm.uiState.value.preparedDataExport)
                assertNotNull(vm.uiState.value.error)
                assertFalse(vm.uiState.value.error!!.contains("private-note-and-package"))
                assertNull(vm.uiState.value.result)
                assertFalse(vm.uiState.value.restoreCompleted)
            } finally { vm.viewModelScope.cancel(); runCurrent() }
        }
    }

    @Test fun typedExportReadWriteFailureUsesPreparationAdviceBeforeAnyExternalDocumentIsChosen() = runTest(dispatcher) {
        val vm = vm(export = export(prepare = {
            throw FullBackupOperationException(FullBackupErrorCode.WRITE_OR_READ_FAILED,
                IllegalStateException("private-provider-and-note/path"))
        }))
        try {
            vm.requestDataExport(DataExportFormat.JSON); vm.confirmDataExport(); runCurrent()
            val message = vm.uiState.value.error!!
            assertTrue(message.contains("无法准备导出数据"))
            assertFalse(message.contains("不完整文件"))
            assertTrue(message.contains("当前数据未改变"))
            assertFalse(message.contains("private-provider-and-note"))
            assertNull(vm.uiState.value.result)
            assertNull(vm.uiState.value.dataExportRequest)
            assertEquals(DataManagementOperation.IDLE, vm.uiState.value.operation)
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun changedExportPackageRequestsNewExportInsteadOfSelectingFullBackup() = runTest(dispatcher) {
        val vm = vm(export = export(prepare = {
            throw FullBackupOperationException(FullBackupErrorCode.INVALID_ARCHIVE,
                IllegalStateException("private-export-path"))
        }))
        try {
            vm.requestDataExport(DataExportFormat.CSV_ZIP); vm.confirmDataExport(); runCurrent()
            val message = vm.uiState.value.error!!
            assertTrue(message.contains("导出暂存"))
            assertTrue(message.contains("重新导出"))
            assertTrue(message.contains("当前数据未改变"))
            assertFalse(message.contains("请选择完整"))
            assertFalse(message.contains("private-export-path"))
            assertNull(vm.uiState.value.result)
            assertNull(vm.uiState.value.dataExportRequest)
            assertEquals(DataManagementOperation.IDLE, vm.uiState.value.operation)
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun cancellingPreparationDiscardsLatePackageBeforeReleasingBusyState() = runTest(dispatcher) {
        val response = CompletableDeferred<PreparedDataExport>()
        val cleanup = CompletableDeferred<Unit>()
        val discarded = mutableListOf<PreparedDataExport>()
        val vm = vm(export = export(prepare = { withContext(NonCancellable) { response.await() } },
            discard = { discarded += it; cleanup.await() }))
        try {
            vm.requestDataExport(DataExportFormat.JSON); vm.confirmDataExport(); runCurrent()
            vm.cancelOperation(); runCurrent()
            response.complete(json); runCurrent()
            assertEquals(listOf(json), discarded)
            assertEquals(DataManagementOperation.CANCELLING, vm.uiState.value.operation)
            assertNull(vm.uiState.value.dataExportRequest)
            cleanup.complete(Unit); runCurrent()
            assertEquals(DataManagementOperation.IDLE, vm.uiState.value.operation)
            assertNull(vm.uiState.value.preparedDataExport)
        } finally { response.cancel(); cleanup.complete(Unit); vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun clearingViewModelWhilePickerIsOpenStillDiscardsPrivateExport() = runTest(dispatcher) {
        val discarded = mutableListOf<PreparedDataExport>()
        val vm = vm(export = export(prepare = { json }, discard = { discarded += it }))
        prepare(vm, DataExportFormat.JSON)
        vm.viewModelScope.cancel(); runCurrent()
        assertEquals(listOf(json), discarded)
        assertNull(vm.uiState.value.preparedDataExport)
        assertNull(vm.uiState.value.activeDataExportRequest)
    }

    @Test fun pickerLaunchFailureCleansFileAndOffersHonestRetry() = runTest(dispatcher) {
        val discarded = mutableListOf<PreparedDataExport>()
        val vm = vm(export = export(prepare = { json }, discard = { discarded += it }))
        try {
            val request = prepare(vm, DataExportFormat.JSON)
            vm.dataExportPickerLaunchFailed(request.id); runCurrent()
            assertEquals(listOf(json), discarded)
            assertEquals(DataManagementOperation.IDLE, vm.uiState.value.operation)
            assertNull(vm.uiState.value.result)
            assertTrue(vm.uiState.value.error!!.contains("当前数据未改变"))
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun cancelledPickerCleanupFailureIsVisibleWithoutClaimingSuccess() = runTest(dispatcher) {
        val vm = vm(export = export(prepare = { json }, save = { _, _ -> }, discard = { error("private-cleanup-path") }))
        try {
            val request = prepare(vm, DataExportFormat.JSON)
            vm.dataExportDestinationSelected(request.id, null); runCurrent()
            assertNull(vm.uiState.value.result)
            assertNotNull(vm.uiState.value.error)
            assertFalse(vm.uiState.value.error!!.contains("private-cleanup-path"))
            assertEquals(DataManagementOperation.IDLE, vm.uiState.value.operation)
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    private fun TestScope.prepare(vm: DataManagementViewModel, format: DataExportFormat): ExportDocumentRequest {
        vm.requestDataExport(format); vm.confirmDataExport(); runCurrent()
        assertNotNull("Confirmed export must request a destination after preparation", vm.uiState.value.dataExportRequest)
        val request = vm.uiState.value.dataExportRequest!!
        vm.dataExportPickerLaunched(request.id)
        return request
    }
    private fun vm(backup: FullBackupService = backup(), export: DataExportService = export()) =
        DataManagementViewModel(backup, export)
    private fun export(
        prepare: suspend (DataExportFormat) -> PreparedDataExport = { error("No preparation expected") },
        save: suspend (PreparedDataExport, Uri) -> Unit = { _, _ -> error("No save expected") },
        discard: suspend (PreparedDataExport) -> Unit = {},
    ) = object : DataExportService {
        override suspend fun prepareExport(format: DataExportFormat) = prepare(format)
        override suspend fun saveExport(prepared: PreparedDataExport, destination: Uri) = save(prepared, destination)
        override suspend fun discardPreparedExport(prepared: PreparedDataExport) = discard(prepared)
    }
    private fun backup(prepare: suspend () -> PreparedBackup = { error("No backup expected") }) = object : FullBackupService {
        override suspend fun prepareBackup() = prepare()
        override suspend fun saveBackup(prepared: PreparedBackup, destination: Uri) = error("No backup save expected")
        override suspend fun inspectBackup(source: Uri): RestoreCandidate = error("No restore inspection expected")
        override suspend fun restore(candidate: RestoreCandidate) = error("No restore expected")
    }
    private data class ExportCase(val format: DataExportFormat, val prepared: PreparedDataExport, val mime: String, val suffix: String)
}
