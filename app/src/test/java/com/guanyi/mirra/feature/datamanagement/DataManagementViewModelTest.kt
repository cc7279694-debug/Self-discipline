package com.guanyi.mirra.feature.datamanagement

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.domain.backup.FullBackupService
import com.guanyi.mirra.domain.backup.PreparedBackup
import com.guanyi.mirra.domain.backup.RestoreCandidate
import com.guanyi.mirra.domain.backup.FullBackupErrorCode
import com.guanyi.mirra.domain.backup.FullBackupOperationException
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DataManagementViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val prepared = PreparedBackup(File("private-backup.zip"), 1_791_425_600_000L, 2, 3, 4)

    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun destinationIsRequestedOnlyAfterPrivateBackupFinishes() = runTest(dispatcher) {
        val response = CompletableDeferred<PreparedBackup>()
        val vm = DataManagementViewModel(StubService(prepare = { response.await() }))
        try {
            vm.createBackup()
            assertEquals(DataManagementOperation.PREPARING_BACKUP, vm.uiState.value.operation)
            assertNull(vm.uiState.value.preparedBackup)
            runCurrent()
            assertNull(vm.uiState.value.exportRequest)
            response.complete(prepared)
            runCurrent()
            assertEquals(prepared, vm.uiState.value.preparedBackup)
            assertEquals(DataManagementOperation.AWAITING_EXPORT_LOCATION, vm.uiState.value.operation)
            assertNotNull(vm.uiState.value.exportRequest)
            assertNull(vm.uiState.value.error)
        } finally { response.cancel(); vm.viewModelScope.cancel() }
    }

    @Test fun cancelledDocumentPickerDiscardsPrivatePackageAndLeavesActionsAvailable() = runTest(dispatcher) {
        val released = mutableListOf<PreparedBackup>()
        val service = StubService(prepare = { prepared }, discard = { released += it })
        val vm = DataManagementViewModel(service)
        try {
            vm.createBackup(); runCurrent()
            vm.exportDestinationSelected(null); runCurrent()
            assertEquals(DataManagementOperation.IDLE, vm.uiState.value.operation)
            assertNull(vm.uiState.value.preparedBackup)
            assertNull(vm.uiState.value.exportRequest)
            assertEquals("已取消保存备份，当前数据未改变。", vm.uiState.value.result)
            assertEquals(listOf(prepared), released)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun privateBackupFailureDoesNotOpenSystemPickerOrExposePrivateFailure() = runTest(dispatcher) {
        val vm = DataManagementViewModel(StubService(prepare = { error("secret/path/private-note") }))
        try {
            vm.createBackup(); runCurrent()
            assertEquals(DataManagementOperation.IDLE, vm.uiState.value.operation)
            assertNull(vm.uiState.value.exportRequest)
            assertNull(vm.uiState.value.preparedBackup)
            assertEquals("无法创建完整备份。请先结束学习并确认可用空间后重试。", vm.uiState.value.error)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun repeatedRequestsCannotCreateParallelBackupOrRestore() = runTest(dispatcher) {
        val response = CompletableDeferred<PreparedBackup>()
        var preparations = 0
        val vm = DataManagementViewModel(StubService(prepare = { preparations++; response.await() }))
        try {
            vm.createBackup(); vm.createBackup(); vm.requestImport(); vm.confirmRestore()
            runCurrent()
            assertEquals(DataManagementOperation.PREPARING_BACKUP, vm.uiState.value.operation)
            assertNull(vm.uiState.value.importRequest)
            assertEquals(1, preparations)
            response.complete(prepared); runCurrent()
            assertEquals(DataManagementOperation.AWAITING_EXPORT_LOCATION, vm.uiState.value.operation)
        } finally { response.cancel(); vm.viewModelScope.cancel() }
    }

    @Test fun cancellingPreparationDiscardsLatePrivatePackageWithoutRequestingExport() = runTest(dispatcher) {
        val response = CompletableDeferred<PreparedBackup>()
        val released = mutableListOf<PreparedBackup>()
        val vm = DataManagementViewModel(StubService(
            prepare = { withContext(NonCancellable) { response.await() } }, discard = { released += it },
        ))
        try {
            vm.createBackup(); runCurrent()
            vm.cancelOperation(); runCurrent()
            assertEquals(DataManagementOperation.CANCELLING, vm.uiState.value.operation)
            response.complete(prepared); runCurrent()
            assertEquals(DataManagementOperation.IDLE, vm.uiState.value.operation)
            assertNull(vm.uiState.value.exportRequest)
            assertNull(vm.uiState.value.preparedBackup)
            assertEquals(listOf(prepared), released)
        } finally { response.cancel(); vm.viewModelScope.cancel() }
    }

    @Test fun selectingBackupRequiresAnExplicitUserRequestAndCancellationNeverRestores() = runTest(dispatcher) {
        val vm = DataManagementViewModel(StubService())
        try {
            vm.requestImport()
            assertEquals(DataManagementOperation.AWAITING_IMPORT_LOCATION, vm.uiState.value.operation)
            assertNotNull(vm.uiState.value.importRequest)
            vm.importSourceSelected(null); runCurrent()
            assertEquals(DataManagementOperation.IDLE, vm.uiState.value.operation)
            assertNull(vm.uiState.value.restoreCandidate)
            assertFalse(vm.uiState.value.showRestoreConfirmation)
            assertNull(vm.uiState.value.error)
            assertEquals("已取消选择备份，当前数据未改变。", vm.uiState.value.result)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun consumedPickerRequestDoesNotRelaunchDuringCompositionRecreation() = runTest(dispatcher) {
        val vm = DataManagementViewModel(StubService(prepare = { prepared }))
        try {
            vm.createBackup(); runCurrent()
            val request = vm.uiState.value.exportRequest!!
            vm.exportPickerLaunched(request.id)
            assertNull(vm.uiState.value.exportRequest)
            assertEquals(DataManagementOperation.AWAITING_EXPORT_LOCATION, vm.uiState.value.operation)
            assertEquals(prepared, vm.uiState.value.preparedBackup)
            vm.exportPickerLaunched(request.id)
            assertNull(vm.uiState.value.exportRequest)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun missingServiceIsShownAsUnavailableAndDoesNotRequestDocuments() = runTest(dispatcher) {
        val vm = DataManagementViewModel(null)
        try {
            vm.createBackup(); vm.requestImport(); runCurrent()
            assertFalse(vm.uiState.value.available)
            assertEquals(DataManagementOperation.IDLE, vm.uiState.value.operation)
            assertNull(vm.uiState.value.exportRequest)
            assertNull(vm.uiState.value.importRequest)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun typedRejectionsDisplayOnlyFixedReasonWithoutPrivateExceptionContent() = runTest(dispatcher) {
        val expected = listOf(
            FullBackupErrorCode.ACTIVE_LEARNING to "请先结束当前学习或取消启动，再进行备份或恢复。",
            FullBackupErrorCode.OWNED_CLEANUP to "学习保护尚未完成清理。请检查 Mirra 的勿扰与系统权限后重试，当前数据未删除。",
            FullBackupErrorCode.LOW_SPACE to "可用空间不足。请释放空间后重试，当前数据未删除。",
            FullBackupErrorCode.INVALID_ARCHIVE to "这份备份无效或已损坏。请选择完整的 Mirra 备份，当前数据未删除。",
            FullBackupErrorCode.WRITE_OR_READ_FAILED to "文件读取或保存失败。请检查所选位置与权限后重试，当前数据未删除。",
            FullBackupErrorCode.STORAGE_BUSY to "本地数据正在处理其他操作。请稍后重试，当前数据未删除。",
        )
        for ((code, message) in expected) {
            val vm = DataManagementViewModel(StubService(prepare = {
                throw FullBackupOperationException(code, IllegalStateException("/private/path/provider/token"))
            }))
            try {
                vm.createBackup(); runCurrent()
                assertEquals(message, vm.uiState.value.error)
                assertNull(vm.uiState.value.exportRequest)
                assertTrue(vm.uiState.value.available)
                assertFalse(vm.uiState.value.maintenanceBlocked)
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun unresolvedRestoreReasonFailsClosedAndCannotBeDismissedIntoLearning() = runTest(dispatcher) {
        val vm = DataManagementViewModel(StubService(prepare = {
            throw FullBackupOperationException(FullBackupErrorCode.RESTORE_BLOCKED,
                IllegalStateException("/private/rollback/original"))
        }))
        try {
            vm.createBackup(); runCurrent()
            assertTrue(vm.uiState.value.maintenanceBlocked)
            assertFalse(vm.uiState.value.available)
            assertEquals("恢复结果尚未确定，应用已停止开放学习与编辑。请保留全部文件并重新打开应用，不要清除应用数据。", vm.uiState.value.error)
            var left = false
            vm.leave { left = true }; vm.createBackup(); vm.requestImport(); runCurrent()
            assertFalse(left)
            assertNull(vm.uiState.value.exportRequest)
            assertNull(vm.uiState.value.importRequest)
            assertFalse(vm.uiState.value.restoreCompleted)
            assertNull(vm.uiState.value.result)
        } finally { vm.viewModelScope.cancel() }
    }
}

private class StubService(
    private val prepare: suspend () -> PreparedBackup = { error("No preparation expected") },
    private val discard: suspend (PreparedBackup) -> Unit = {},
) : FullBackupService {
    override suspend fun prepareBackup() = prepare()
    override suspend fun saveBackup(prepared: PreparedBackup, destination: Uri) = error("No export expected")
    override suspend fun inspectBackup(source: Uri): RestoreCandidate = error("No import expected")
    override suspend fun restore(candidate: RestoreCandidate) = error("No restore expected")
    override suspend fun discardPreparedBackup(prepared: PreparedBackup) = discard(prepared)
}
