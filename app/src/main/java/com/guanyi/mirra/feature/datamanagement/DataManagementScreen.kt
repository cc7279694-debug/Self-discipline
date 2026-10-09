package com.guanyi.mirra.feature.datamanagement

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guanyi.mirra.domain.backup.DataExportFormat
import com.guanyi.mirra.domain.backup.RestoreCandidate
import com.guanyi.mirra.ui.components.MirraPrimaryButton
import com.guanyi.mirra.ui.components.MirraSecondaryButton
import com.guanyi.mirra.ui.components.MirraTextAction
import com.guanyi.mirra.ui.theme.MirraTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DataManagementScreen(
    viewModel: DataManagementViewModel,
    onBack: () -> Unit,
    onRestoreComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        viewModel.exportDestinationSelected(it)
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        viewModel.importSourceSelected(it)
    }
    LaunchedEffect(state.exportRequest?.id) {
        state.exportRequest?.let { request ->
            viewModel.exportPickerLaunched(request.id)
            try { createDocument.launch(request.fileName) }
            catch (_: Exception) { viewModel.pickerLaunchFailed(export = true) }
        }
    }
    LaunchedEffect(state.importRequest) {
        state.importRequest?.let { request ->
            viewModel.importPickerLaunched(request)
            // Some document providers do not report ZIP MIME types consistently; validation owns acceptance.
            try { openDocument.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
            catch (_: Exception) { viewModel.pickerLaunchFailed(export = false) }
        }
    }
    state.activeDataExportRequest?.let { request ->
        key(request.id) {
            DataExportDocumentPicker(request, state.dataExportRequest?.id == request.id,
                viewModel::dataExportPickerLaunched, viewModel::dataExportDestinationSelected,
                viewModel::dataExportPickerLaunchFailed)
        }
    }
    LaunchedEffect(state.restoreCompleted) {
        if (state.restoreCompleted) {
            viewModel.restoreCompletionHandled()
            onRestoreComplete()
        }
    }
    BackHandler {
        when {
            state.canCancel -> viewModel.cancelOperation()
            !state.isBusy && !state.maintenanceBlocked -> viewModel.leave(onBack)
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MirraTheme.colors.background).testTag("data-management-list"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item("title") {
            Text("数据管理", style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold, color = MirraTheme.colors.textPrimary)
        }
        item("privacy") {
            Text("完整备份包含私人笔记和图片。文件未加密，请妥善保管。Mirra 不会自动上传；保存位置由你选择，所选服务可能联网。",
                color = MirraTheme.colors.textSecondary, style = MaterialTheme.typography.bodyLarge)
        }
        item("scope") {
            Text("完整备份保存学习内容、记录、笔记、主题、图片与设置。恢复采用完整替换。请先结束学习，取消未完成的启动，再进行数据管理。",
                color = MirraTheme.colors.textSecondary)
        }
        if (!state.available && !state.maintenanceBlocked) item("unavailable") {
            Text("本地数据管理暂不可用。请重新打开应用后再试。", color = MirraTheme.colors.textSecondary)
        }
        item("backup-action") {
            MirraPrimaryButton(viewModel::createBackup, Modifier.fillMaxWidth().testTag("data-create-backup"),
                enabled = state.canStartOperation) { Text("创建完整备份") }
        }
        item("import-action") {
            MirraSecondaryButton(viewModel::requestImport, Modifier.fillMaxWidth().testTag("data-inspect-backup"),
                enabled = state.canStartOperation) { Text("选择并验证备份") }
        }
        item("export-scope") {
            Text("JSON 与 CSV 是可读数据导出，不能用于完整恢复，也不包含 JPEG 图片。完整恢复请使用上面的完整备份。",
                color = MirraTheme.colors.textSecondary)
        }
        item("json-action") {
            MirraSecondaryButton({ viewModel.requestDataExport(DataExportFormat.JSON) },
                Modifier.fillMaxWidth().testTag("data-export-json"),
                enabled = state.canStartOperation && state.dataExportAvailable) { Text("导出 JSON") }
        }
        item("csv-action") {
            MirraSecondaryButton({ viewModel.requestDataExport(DataExportFormat.CSV_ZIP) },
                Modifier.fillMaxWidth().testTag("data-export-csv"),
                enabled = state.canStartOperation && state.dataExportAvailable) { Text("导出 CSV") }
        }
        if (state.isBusy) {
            item("progress") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.operation.message, color = MirraTheme.colors.textPrimary)
                    if (state.operation !in setOf(DataManagementOperation.AWAITING_EXPORT_LOCATION,
                            DataManagementOperation.AWAITING_DATA_EXPORT_LOCATION,
                            DataManagementOperation.AWAITING_IMPORT_LOCATION)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = MirraTheme.colors.accentStrong,
                            trackColor = MirraTheme.colors.accentSoft)
                    }
                    if (state.operation == DataManagementOperation.RESTORING) {
                        Text("请保持应用打开。完成验证前不会开放混合的数据状态。", color = MirraTheme.colors.textSecondary)
                    }
                }
            }
            if (state.canCancel) item("cancel") {
                MirraTextAction(viewModel::cancelOperation, Modifier.fillMaxWidth()) { Text("取消操作") }
            }
        }
        state.restoreCandidate?.let { candidate ->
            item("validated-summary") { ValidatedBackupSummary(candidate) }
            item("restore-action") {
                MirraPrimaryButton(viewModel::requestRestoreConfirmation,
                    Modifier.fillMaxWidth().testTag("data-restore-backup"), enabled = state.available && !state.isBusy) { Text("恢复这份备份") }
            }
            item("discard-candidate") {
                MirraTextAction(viewModel::cancelRestoreCandidate, Modifier.fillMaxWidth(),
                    enabled = !state.isBusy && !state.maintenanceBlocked) {
                    Text("取消这份备份")
                }
            }
        }
        state.error?.let { error -> item("error") {
            Text(error, color = MirraTheme.colors.danger, modifier = Modifier.testTag("data-management-error"))
        } }
        state.result?.let { result -> item("result") {
            Text(result, color = MirraTheme.colors.textPrimary, modifier = Modifier.testTag("data-management-result"))
        } }
        item("back") {
            MirraSecondaryButton({ viewModel.leave(onBack) }, Modifier.fillMaxWidth(),
                enabled = !state.isBusy && !state.maintenanceBlocked) { Text("返回") }
        }
    }
    if (state.showRestoreConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRestoreConfirmation,
            title = { Text("替换当前全部数据？") },
            text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text("当前学习内容、记录、笔记、图片与设置将被这份备份完整替换，不会合并。恢复前会保存当前数据的安全快照。")
            } },
            confirmButton = {
                MirraTextAction(viewModel::confirmRestore, Modifier.testTag("data-confirm-replace")) { Text("确认完整替换") }
            },
            dismissButton = { MirraTextAction(viewModel::dismissRestoreConfirmation) { Text("取消") } },
        )
    }
    if (state.pendingDataExportFormat != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDataExportConfirmation,
            title = { Text("导出可读数据？") },
            text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("导出包含私人笔记、学习与历史记录，以及历史风险 App 的名称和包名。文件未加密，可能暴露个人信息，请妥善保管。")
                Text("JSON 与 CSV 仅供查看和分析，不包含 JPEG 图片，也不能用于完整恢复。Mirra 不会自动上传；保存位置由你选择，所选服务可能联网。")
            } },
            confirmButton = { MirraTextAction(viewModel::confirmDataExport,
                Modifier.testTag("data-confirm-export")) { Text("确认导出") } },
            dismissButton = { MirraTextAction(viewModel::dismissDataExportConfirmation) { Text("取消") } },
        )
    }
}

@Composable
private fun DataExportDocumentPicker(
    request: ExportDocumentRequest,
    launchRequested: Boolean,
    onLaunched: (Long) -> Unit,
    onSelected: (Long, android.net.Uri?) -> Unit,
    onFailure: (Long) -> Unit,
) {
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(request.mimeType)) {
        onSelected(request.id, it)
    }
    LaunchedEffect(request.id, launchRequested) {
        if (launchRequested) {
            onLaunched(request.id)
            try { createDocument.launch(request.fileName) }
            catch (_: Exception) { onFailure(request.id) }
        }
    }
}

@Composable
private fun ValidatedBackupSummary(candidate: RestoreCandidate) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = MirraTheme.colors.divider)
        Text("备份已通过验证", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold, color = MirraTheme.colors.textPrimary)
        Text("创建时间：${formatBackupTime(candidate.createdAt)}", color = MirraTheme.colors.textPrimary)
        if (candidate.appVersion.isNotEmpty()) Text("应用版本：${candidate.appVersion}", color = MirraTheme.colors.textSecondary)
        Text("备份格式：${candidate.backupFormatVersion} · 数据版本：${candidate.schemaVersion}", color = MirraTheme.colors.textSecondary)
        Text("学习内容：${candidate.itemCount} 项", color = MirraTheme.colors.textPrimary)
        Text("笔记：${candidate.noteCount} 条", color = MirraTheme.colors.textPrimary)
        Text("图片：${candidate.imageCount} 张", color = MirraTheme.colors.textPrimary)
        Text("验证通过尚未改变当前数据。", color = MirraTheme.colors.textSecondary)
    }
}

private val DataManagementOperation.message: String get() = when (this) {
    DataManagementOperation.IDLE -> ""
    DataManagementOperation.PREPARING_BACKUP -> "正在创建本地完整备份…"
    DataManagementOperation.AWAITING_EXPORT_LOCATION -> "请选择备份保存位置。"
    DataManagementOperation.SAVING_BACKUP -> "正在保存备份文件…"
    DataManagementOperation.AWAITING_IMPORT_LOCATION -> "请选择要验证的完整备份。"
    DataManagementOperation.VERIFYING_BACKUP -> "正在验证备份…"
    DataManagementOperation.RESTORING -> "正在恢复完整备份…"
    DataManagementOperation.CANCELLING -> "正在取消并清理临时文件…"
    DataManagementOperation.PREPARING_DATA_EXPORT -> "正在准备可读数据导出…"
    DataManagementOperation.AWAITING_DATA_EXPORT_LOCATION -> "请选择导出文件保存位置。"
    DataManagementOperation.SAVING_DATA_EXPORT -> "正在保存导出文件…"
    DataManagementOperation.CLEANING_DATA_EXPORT -> "正在清理导出临时文件…"
}

private fun formatBackupTime(createdAt: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()))
