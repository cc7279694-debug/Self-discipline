package com.guanyi.mirra.feature.datamanagement

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.domain.backup.DataExportFormat
import com.guanyi.mirra.domain.backup.DataExportService
import com.guanyi.mirra.domain.backup.PreparedDataExport
import com.guanyi.mirra.domain.backup.FullBackupService
import com.guanyi.mirra.domain.backup.PreparedBackup
import com.guanyi.mirra.domain.backup.RestoreCandidate
import com.guanyi.mirra.domain.backup.FullBackupErrorCode
import com.guanyi.mirra.domain.backup.FullBackupException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DataManagementOperation {
    IDLE, PREPARING_BACKUP, AWAITING_EXPORT_LOCATION, SAVING_BACKUP,
    AWAITING_IMPORT_LOCATION, VERIFYING_BACKUP, RESTORING, CANCELLING,
    PREPARING_DATA_EXPORT, AWAITING_DATA_EXPORT_LOCATION, SAVING_DATA_EXPORT, CLEANING_DATA_EXPORT,
}

data class ExportDocumentRequest(val id: Long, val fileName: String, val mimeType: String = "application/zip")

data class DataManagementUiState(
    val available: Boolean = true,
    val operation: DataManagementOperation = DataManagementOperation.IDLE,
    val preparedBackup: PreparedBackup? = null,
    val exportRequest: ExportDocumentRequest? = null,
    val importRequest: Long? = null,
    val restoreCandidate: RestoreCandidate? = null,
    val showRestoreConfirmation: Boolean = false,
    val restoreCompleted: Boolean = false,
    val maintenanceBlocked: Boolean = false,
    val error: String? = null,
    val result: String? = null,
    val dataExportAvailable: Boolean = false,
    val pendingDataExportFormat: DataExportFormat? = null,
    val preparedDataExport: PreparedDataExport? = null,
    val dataExportRequest: ExportDocumentRequest? = null,
    val activeDataExportRequest: ExportDocumentRequest? = null,
) {
    val isBusy: Boolean get() = operation != DataManagementOperation.IDLE
    val canCancel: Boolean get() = operation in setOf(
        DataManagementOperation.PREPARING_BACKUP,
        DataManagementOperation.SAVING_BACKUP,
        DataManagementOperation.VERIFYING_BACKUP,
        DataManagementOperation.PREPARING_DATA_EXPORT,
        DataManagementOperation.SAVING_DATA_EXPORT,
    )
    val canStartOperation: Boolean get() = available && !isBusy && restoreCandidate == null && pendingDataExportFormat == null
}

class DataManagementViewModel(
    private val service: FullBackupService?,
    private val exportService: DataExportService? = null,
) : ViewModel() {
    private val state = MutableStateFlow(DataManagementUiState(
        available = service != null, dataExportAvailable = exportService != null,
    ))
    val uiState: StateFlow<DataManagementUiState> = state.asStateFlow()
    private var operationJob: Job? = null
    private var requestSequence = 0L
    private var dataExportDestination: CompletableDeferred<Uri?>? = null

    fun requestDataExport(format: DataExportFormat) {
        if (exportService == null || !state.value.canStartOperation) return
        state.value = state.value.copy(pendingDataExportFormat = format, error = null, result = null)
    }

    fun dismissDataExportConfirmation() {
        state.value = state.value.copy(pendingDataExportFormat = null)
    }

    fun confirmDataExport() {
        val owner = exportService ?: return
        val format = state.value.pendingDataExportFormat ?: return
        if (!state.value.available || state.value.isBusy || state.value.restoreCandidate != null) return
        val selection = CompletableDeferred<Uri?>()
        dataExportDestination = selection
        begin(DataManagementOperation.PREPARING_DATA_EXPORT)
        operationJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var prepared: PreparedDataExport? = null
            var saved = false
            var saveStarted = false
            try {
                val packageToSave = owner.prepareExport(format)
                prepared = packageToSave
                currentCoroutineContext().ensureActive()
                check(packageToSave.format == format)
                val request = ExportDocumentRequest(++requestSequence,
                    dataExportFileName(packageToSave.createdAt, format), format.mimeType)
                state.value = state.value.copy(
                    operation = DataManagementOperation.AWAITING_DATA_EXPORT_LOCATION,
                    preparedDataExport = packageToSave, dataExportRequest = request,
                    activeDataExportRequest = request,
                )
                val destination = selection.await()
                if (destination == null) cancelled("已取消保存导出，当前数据未改变。")
                else {
                    saveStarted = true
                    owner.saveExport(packageToSave, destination)
                    currentCoroutineContext().ensureActive()
                    saved = true
                }
            } catch (cancellation: CancellationException) {
                cancelled(if (saveStarted) "保存已取消。所选位置可能留有不完整文件；当前数据未改变。"
                    else "导出已取消，当前数据未改变。")
                throw cancellation
            } catch (_: DataExportPickerException) {
                failed("无法打开文件保存位置。请重试；当前数据未改变。")
            } catch (failure: Exception) {
                rejectedDataExport(failure, if (saveStarted)
                    "导出未能保存。所选位置可能留有不完整文件，请检查后重试；当前数据未改变。"
                    else "无法准备导出数据。请先结束学习并确认可用空间后重试；当前数据未改变。")
            } finally {
                state.value = state.value.copy(operation = if (saved) DataManagementOperation.CLEANING_DATA_EXPORT
                    else DataManagementOperation.CANCELLING,
                    dataExportRequest = null)
                val cleaned = prepared?.let { discardDataExport(it) } ?: true
                state.value = state.value.copy(operation = DataManagementOperation.IDLE,
                    preparedDataExport = null, dataExportRequest = null, activeDataExportRequest = null,
                    result = if (saved && cleaned && !state.value.maintenanceBlocked)
                        "${if (format == DataExportFormat.JSON) "JSON 数据" else "CSV 数据包"}已保存。仅供查看和分析，不能用于完整恢复。"
                    else state.value.result)
                dataExportDestination = null
                operationJob = null
            }
        }
    }

    fun dataExportPickerLaunched(id: Long) {
        if (state.value.dataExportRequest?.id == id) state.value = state.value.copy(dataExportRequest = null)
    }

    fun dataExportDestinationSelected(id: Long, destination: Uri?) {
        if (state.value.activeDataExportRequest?.id != id ||
            state.value.operation != DataManagementOperation.AWAITING_DATA_EXPORT_LOCATION) return
        val selection = dataExportDestination ?: return
        begin(if (destination == null) DataManagementOperation.CANCELLING else DataManagementOperation.SAVING_DATA_EXPORT)
        selection.complete(destination)
    }

    fun dataExportPickerLaunchFailed(id: Long) {
        if (state.value.activeDataExportRequest?.id != id ||
            state.value.operation != DataManagementOperation.AWAITING_DATA_EXPORT_LOCATION) return
        begin(DataManagementOperation.CANCELLING)
        dataExportDestination?.completeExceptionally(DataExportPickerException())
    }

    fun createBackup() {
        val owner = service ?: return
        if (!state.value.canStartOperation) return
        begin(DataManagementOperation.PREPARING_BACKUP)
        operationJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var packageToDiscard: PreparedBackup? = null
            try {
                val prepared = owner.prepareBackup()
                packageToDiscard = prepared
                currentCoroutineContext().ensureActive()
                val request = ExportDocumentRequest(++requestSequence, backupFileName(prepared.createdAt))
                state.value = state.value.copy(
                    operation = DataManagementOperation.AWAITING_EXPORT_LOCATION,
                    preparedBackup = prepared, exportRequest = request,
                )
                packageToDiscard = null
            } catch (cancellation: CancellationException) {
                cancelled("操作已取消，当前数据未改变。")
                throw cancellation
            } catch (failure: Exception) {
                rejected(failure, "无法创建完整备份。请先结束学习并确认可用空间后重试。")
            } finally {
                if (!state.value.maintenanceBlocked) packageToDiscard?.let { discardPrepared(it) }
                if (state.value.operation != DataManagementOperation.AWAITING_EXPORT_LOCATION) {
                    state.value = state.value.copy(operation = DataManagementOperation.IDLE)
                }
                operationJob = null
            }
        }
    }

    fun exportPickerLaunched(id: Long) {
        if (state.value.exportRequest?.id == id) state.value = state.value.copy(exportRequest = null)
    }

    fun exportDestinationSelected(destination: Uri?) {
        val owner = service ?: return
        val prepared = state.value.preparedBackup ?: return
        if (state.value.operation != DataManagementOperation.AWAITING_EXPORT_LOCATION) return
        begin(if (destination == null) DataManagementOperation.CANCELLING else DataManagementOperation.SAVING_BACKUP)
        operationJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (destination == null) cancelled("已取消保存备份，当前数据未改变。")
                else {
                    owner.saveBackup(prepared, destination)
                    currentCoroutineContext().ensureActive()
                    state.value = state.value.copy(
                        result = "完整备份已保存。请妥善保管这份文件。")
                }
            } catch (cancellation: CancellationException) {
                cancelled("保存已取消，当前数据未改变。")
                throw cancellation
            } catch (failure: Exception) {
                rejected(failure, "备份未能保存。请检查所选位置与可用空间后重新创建；当前数据未改变。")
            } finally {
                if (!state.value.maintenanceBlocked) discardPrepared(prepared)
                state.value = state.value.copy(operation = DataManagementOperation.IDLE,
                    preparedBackup = prepared.takeIf { state.value.maintenanceBlocked }, exportRequest = null)
                operationJob = null
            }
        }
    }

    fun requestImport() {
        if (!state.value.canStartOperation) return
        begin(DataManagementOperation.AWAITING_IMPORT_LOCATION)
        state.value = state.value.copy(importRequest = ++requestSequence)
    }

    fun importPickerLaunched(id: Long) {
        if (state.value.importRequest == id) state.value = state.value.copy(importRequest = null)
    }

    fun importSourceSelected(source: Uri?) {
        val owner = service ?: return
        if (state.value.operation != DataManagementOperation.AWAITING_IMPORT_LOCATION) return
        if (source == null) {
            cancelled("已取消选择备份，当前数据未改变。")
            state.value = state.value.copy(importRequest = null)
            return
        }
        begin(DataManagementOperation.VERIFYING_BACKUP)
        operationJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var candidateToDiscard: RestoreCandidate? = null
            try {
                val candidate = owner.inspectBackup(source)
                candidateToDiscard = candidate
                currentCoroutineContext().ensureActive()
                state.value = state.value.copy(operation = DataManagementOperation.IDLE,
                    restoreCandidate = candidate)
                candidateToDiscard = null
            } catch (cancellation: CancellationException) {
                cancelled("验证已取消，当前数据未改变。")
                throw cancellation
            } catch (failure: Exception) {
                rejected(failure, "无法验证这份备份。请确认是完整的 Mirra 备份、文件未损坏且有足够空间；当前数据未改变。")
            } finally {
                if (!state.value.maintenanceBlocked) candidateToDiscard?.let { discardCandidate(it) }
                state.value = state.value.copy(operation = DataManagementOperation.IDLE)
                operationJob = null
            }
        }
    }

    fun requestRestoreConfirmation() {
        if (state.value.available && !state.value.isBusy && state.value.restoreCandidate != null) {
            state.value = state.value.copy(showRestoreConfirmation = true, error = null, result = null)
        }
    }

    fun dismissRestoreConfirmation() { state.value = state.value.copy(showRestoreConfirmation = false) }

    fun confirmRestore() {
        val owner = service ?: return
        val candidate = state.value.restoreCandidate ?: return
        if (!state.value.available || state.value.isBusy || !state.value.showRestoreConfirmation) return
        begin(DataManagementOperation.RESTORING)
        operationJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                owner.restore(candidate)
                currentCoroutineContext().ensureActive()
                discardCandidate(candidate)
                if (!state.value.maintenanceBlocked) {
                    state.value = state.value.copy(operation = DataManagementOperation.IDLE,
                        restoreCandidate = null, restoreCompleted = true,
                        result = "完整备份已恢复。学习记录与设置已重新载入。")
                }
            } catch (cancellation: CancellationException) {
                failed("恢复已中断。请保留现有文件并重新打开应用，确认恢复结果。")
                throw cancellation
            } catch (failure: Exception) {
                rejected(failure, "恢复未完成。请检查当前数据后重试；若应用提示维护，请保留现有文件。")
            } finally {
                state.value = state.value.copy(operation = DataManagementOperation.IDLE)
                operationJob = null
            }
        }
    }

    fun restoreCompletionHandled() { state.value = state.value.copy(restoreCompleted = false) }

    fun cancelRestoreCandidate() {
        val candidate = state.value.restoreCandidate ?: return
        if (state.value.isBusy || state.value.maintenanceBlocked) return
        begin(DataManagementOperation.CANCELLING)
        operationJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (discardCandidate(candidate)) state.value = state.value.copy(
                operation = DataManagementOperation.IDLE, restoreCandidate = null,
                result = "已取消这份备份，当前数据未改变。")
            else state.value = state.value.copy(operation = DataManagementOperation.IDLE)
            operationJob = null
        }
    }

    fun cancelOperation() {
        if (!state.value.canCancel) return
        state.value = state.value.copy(operation = DataManagementOperation.CANCELLING)
        operationJob?.cancel()
    }

    fun pickerLaunchFailed(export: Boolean) {
        if (export && state.value.operation == DataManagementOperation.AWAITING_EXPORT_LOCATION) {
            val prepared = state.value.preparedBackup ?: return
            begin(DataManagementOperation.CANCELLING)
            operationJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                discardPrepared(prepared)
                state.value = state.value.copy(preparedBackup = null)
                failed("无法打开文件保存位置。请重新打开应用后重试；当前数据未改变。")
                operationJob = null
            }
        } else if (!export && state.value.operation == DataManagementOperation.AWAITING_IMPORT_LOCATION) {
            failed("无法打开文件选择器。请重新打开应用后重试；当前数据未改变。")
        }
    }

    fun leave(onLeave: () -> Unit) {
        if (state.value.isBusy || state.value.maintenanceBlocked) return
        val candidate = state.value.restoreCandidate
        if (candidate == null) { onLeave(); return }
        begin(DataManagementOperation.CANCELLING)
        operationJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val discarded = discardCandidate(candidate)
            state.value = state.value.copy(operation = DataManagementOperation.IDLE,
                restoreCandidate = if (discarded) null else candidate)
            operationJob = null
            if (discarded) onLeave()
        }
    }

    private fun begin(operation: DataManagementOperation) {
        state.value = state.value.copy(operation = operation, error = null, result = null,
            exportRequest = null, importRequest = null, showRestoreConfirmation = false,
            restoreCompleted = false, pendingDataExportFormat = null, dataExportRequest = null)
    }

    private fun cancelled(message: String) {
        val operation = if (state.value.operation in setOf(DataManagementOperation.PREPARING_BACKUP,
                DataManagementOperation.SAVING_BACKUP, DataManagementOperation.VERIFYING_BACKUP,
                DataManagementOperation.PREPARING_DATA_EXPORT, DataManagementOperation.SAVING_DATA_EXPORT,
                DataManagementOperation.CANCELLING)) DataManagementOperation.CANCELLING else DataManagementOperation.IDLE
        state.value = state.value.copy(operation = operation,
            exportRequest = null, importRequest = null, result = message, error = null)
    }

    private fun failed(message: String) {
        val operation = if (state.value.operation in setOf(DataManagementOperation.PREPARING_BACKUP,
                DataManagementOperation.SAVING_BACKUP, DataManagementOperation.VERIFYING_BACKUP,
                DataManagementOperation.PREPARING_DATA_EXPORT, DataManagementOperation.SAVING_DATA_EXPORT,
                DataManagementOperation.RESTORING)) state.value.operation else DataManagementOperation.IDLE
        state.value = state.value.copy(operation = operation,
            exportRequest = null, importRequest = null, showRestoreConfirmation = false,
            error = message, result = null)
    }

    private fun rejected(failure: Exception, fallback: String) {
        val code = (failure as? FullBackupException)?.code
        failed(code?.message ?: fallback)
        if (code == FullBackupErrorCode.RESTORE_BLOCKED) {
            state.value = state.value.copy(available = false, maintenanceBlocked = true,
                showRestoreConfirmation = false, restoreCompleted = false)
        }
    }

    private fun rejectedDataExport(failure: Exception, fallback: String) {
        when ((failure as? FullBackupException)?.code) {
            FullBackupErrorCode.ACTIVE_LEARNING ->
                failed("请先结束当前学习或取消启动，再导出数据；当前数据未改变。")
            FullBackupErrorCode.WRITE_OR_READ_FAILED -> failed(fallback)
            FullBackupErrorCode.INVALID_ARCHIVE ->
                failed("导出暂存文件已变化或失效。请重新导出；当前数据未改变。")
            else -> rejected(failure, fallback)
        }
    }

    private suspend fun discardDataExport(prepared: PreparedDataExport): Boolean = withContext(NonCancellable) {
        try { exportService?.discardPreparedExport(prepared); true }
        catch (failure: Exception) {
            rejectedDataExport(failure, "临时导出文件清理未完成，文件可能仍留在本机。请重新打开应用后再试；当前数据未改变。")
            false
        }
    }

    private suspend fun discardPrepared(prepared: PreparedBackup): Boolean = withContext(NonCancellable) {
        try { service?.discardPreparedBackup(prepared); true }
        catch (failure: Exception) {
            rejected(failure, "临时备份清理未完成。请重新打开应用后再试。")
            false
        }
    }

    private suspend fun discardCandidate(candidate: RestoreCandidate): Boolean = withContext(NonCancellable) {
        try { service?.discardRestoreCandidate(candidate); true }
        catch (failure: Exception) {
            rejected(failure, "临时备份清理未完成。请重新打开应用后再试。")
            false
        }
    }
}

private class DataExportPickerException : Exception()

private fun dataExportFileName(createdAt: Long, format: DataExportFormat): String {
    val date = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault())
    return "Mirra-export-${DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").format(date)}.${format.extension}"
}

private fun backupFileName(createdAt: Long): String {
    val date = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault())
    return "Mirra-backup-${DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").format(date)}.zip"
}

private val FullBackupErrorCode.message: String get() = when (this) {
    FullBackupErrorCode.ACTIVE_LEARNING -> "请先结束当前学习或取消启动，再进行备份或恢复。"
    FullBackupErrorCode.OWNED_CLEANUP -> "学习保护尚未完成清理。请检查 Mirra 的勿扰与系统权限后重试，当前数据未删除。"
    FullBackupErrorCode.LOW_SPACE -> "可用空间不足。请释放空间后重试，当前数据未删除。"
    FullBackupErrorCode.INVALID_ARCHIVE -> "这份备份无效或已损坏。请选择完整的 Mirra 备份，当前数据未删除。"
    FullBackupErrorCode.WRITE_OR_READ_FAILED -> "文件读取或保存失败。请检查所选位置与权限后重试，当前数据未删除。"
    FullBackupErrorCode.RESTORE_BLOCKED -> "恢复结果尚未确定，应用已停止开放学习与编辑。请保留全部文件并重新打开应用，不要清除应用数据。"
    FullBackupErrorCode.STORAGE_BUSY -> "本地数据正在处理其他操作。请稍后重试，当前数据未删除。"
}
