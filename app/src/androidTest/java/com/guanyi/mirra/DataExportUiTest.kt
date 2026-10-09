package com.guanyi.mirra

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.domain.backup.DataExportFormat
import com.guanyi.mirra.domain.backup.DataExportService
import com.guanyi.mirra.domain.backup.FullBackupService
import com.guanyi.mirra.domain.backup.FullBackupErrorCode
import com.guanyi.mirra.domain.backup.FullBackupOperationException
import com.guanyi.mirra.domain.backup.PreparedBackup
import com.guanyi.mirra.domain.backup.PreparedDataExport
import com.guanyi.mirra.domain.backup.RestoreCandidate
import com.guanyi.mirra.feature.datamanagement.DataManagementOperation
import com.guanyi.mirra.feature.datamanagement.DataManagementScreen
import com.guanyi.mirra.feature.datamanagement.DataManagementViewModel
import com.guanyi.mirra.ui.theme.MirraTheme
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DataExportUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun flatExportButtonsRequirePrivateDataConfirmationBeforePreparing() {
        var preparations = 0
        val vm = vm(prepare = { preparations++; prepared(it) })
        try {
            rule.setContent { ExportTestScope { DataManagementScreen(vm, {}, {}) } }
            for (label in listOf("导出 JSON", "导出 CSV")) {
                scrollTo(label)
                rule.onNodeWithText(label).assertIsDisplayed().assertIsEnabled().performClick()
                rule.onNodeWithText("导出可读数据？").assertIsDisplayed()
                rule.onNodeWithText("导出包含私人笔记、学习与历史记录，以及历史风险 App 的名称和包名。文件未加密，可能暴露个人信息，请妥善保管。").assertExists()
                rule.onNodeWithText("JSON 与 CSV 仅供查看和分析，不包含 JPEG 图片，也不能用于完整恢复。Mirra 不会自动上传；保存位置由你选择，所选服务可能联网。").assertExists()
                assertEquals(0, preparations)
                rule.onNodeWithText("取消").performClick()
                rule.waitForIdle()
                assertEquals(0, preparations)
                assertNull(vm.uiState.value.preparedDataExport)
                assertNull(vm.uiState.value.dataExportRequest)
            }
        } finally { close(vm) }
    }

    @Test fun confirmedJsonAndCsvUseFormatSpecificSafDocumentsAndCancellationCleansFiles() {
        val intents = mutableListOf<Pair<Int, Intent>>()
        val discarded = mutableListOf<PreparedDataExport>()
        val registry = registry(intents)
        val vm = vm(discard = { discarded += it })
        try {
            rule.setContent { ExportTestScope(registry) { DataManagementScreen(vm, {}, {}) } }
            for ((index, label, mime, suffix) in listOf(
                PickerCase(0, "导出 JSON", "application/json", ".json"),
                PickerCase(1, "导出 CSV", "application/zip", ".zip"),
            )) {
                scrollTo(label); rule.onNodeWithText(label).performClick()
                assertEquals(index, intents.size)
                rule.onNodeWithText("确认导出").performClick()
                await { intents.size == index + 1 }
                val (code, intent) = intents[index]
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                assertEquals(mime, intent.type)
                assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(suffix))
                rule.runOnIdle { registry.dispatchResult(code, Activity.RESULT_CANCELED, null) }
                await { vm.uiState.value.operation == DataManagementOperation.IDLE }
                assertEquals(index + 1, discarded.size)
                assertNull(vm.uiState.value.preparedDataExport)
                assertNull(vm.uiState.value.activeDataExportRequest)
                assertFalse(vm.uiState.value.restoreCompleted)
                assertTrue(vm.uiState.value.result!!.contains("当前数据未改变"))
            }
        } finally { close(vm) }
    }

    @Test fun systemPickerWaitsForExportSnapshotAndBusyStateDisablesBackupAndOtherExports() {
        val response = CompletableDeferred<PreparedDataExport>()
        val intents = mutableListOf<Pair<Int, Intent>>()
        val registry = registry(intents)
        val vm = vm(prepare = { response.await() })
        try {
            rule.setContent { ExportTestScope(registry) { DataManagementScreen(vm, {}, {}) } }
            scrollTo("导出 JSON"); rule.onNodeWithText("导出 JSON").performClick()
            rule.onNodeWithText("确认导出").performClick()
            await { vm.uiState.value.operation == DataManagementOperation.PREPARING_DATA_EXPORT }
            assertTrue(intents.isEmpty())
            for (label in listOf("创建完整备份", "选择并验证备份", "导出 JSON", "导出 CSV")) {
                scrollTo(label); rule.onNodeWithText(label).assertIsNotEnabled()
            }
            rule.runOnIdle { response.complete(prepared(DataExportFormat.JSON)) }
            await { intents.size == 1 }
            assertEquals("application/json", intents.single().second.type)
        } finally { response.cancel(); close(vm) }
    }

    @Test fun exportActionsAndConfirmationRemainReachableAt320DpDoubleFontWith48DpTargets() {
        val density = rule.activity.resources.displayMetrics.density
        val vm = vm()
        try {
            rule.setContent { CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                ExportTestScope { Box(Modifier.width(320.dp).height(560.dp)) { DataManagementScreen(vm, {}, {}) } }
            } }
            for (label in listOf("导出 JSON", "导出 CSV", "返回")) {
                scrollTo(label)
                val node = rule.onNode(hasText(label) and hasClickAction())
                node.assertIsDisplayed()
                assertTrue(node.fetchSemanticsNode().boundsInRoot.height / density >= 47.9f)
            }
            scrollTo("导出 CSV"); rule.onNodeWithText("导出 CSV").performClick()
            rule.onNodeWithText("确认导出").assertIsDisplayed()
            assertTrue(rule.onNode(hasText("确认导出") and hasClickAction()).fetchSemanticsNode().boundsInRoot.height / density >= 47.9f)
            rule.onNodeWithText("取消").assertIsDisplayed().performClick()
            scrollTo("导出 JSON"); rule.onNodeWithText("导出 JSON").assertIsDisplayed()
        } finally { close(vm) }
    }

    @Test fun saveErrorDoesNotExposePrivateCauseOrShowRestoreSuccess() {
        val intents = mutableListOf<Pair<Int, Intent>>()
        val registry = registry(intents)
        val vm = vm(save = { _, _ -> throw FullBackupOperationException(
            FullBackupErrorCode.WRITE_OR_READ_FAILED, IllegalStateException("secret-note-provider-token")) })
        try {
            rule.setContent { ExportTestScope(registry) { DataManagementScreen(vm, {}, {}) } }
            scrollTo("导出 JSON"); rule.onNodeWithText("导出 JSON").performClick()
            rule.onNodeWithText("确认导出").performClick()
            await { intents.size == 1 }
            rule.runOnIdle { registry.dispatchResult(intents.single().first, Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://export/failed.json"))) }
            await { vm.uiState.value.error != null }
            rule.onNodeWithText("secret-note-provider-token").assertDoesNotExist()
            assertTrue(vm.uiState.value.error!!.contains("可能留有不完整文件"))
            assertTrue(vm.uiState.value.error!!.contains("当前数据未改变"))
            assertNull(vm.uiState.value.result)
            assertFalse(vm.uiState.value.restoreCompleted)
            assertNull(vm.uiState.value.preparedDataExport)
        } finally { close(vm) }
    }

    @Test fun duplicateAndOldPickerResultsCannotSaveNewExport() {
        val saved = mutableListOf<Pair<PreparedDataExport, Uri>>()
        val discarded = mutableListOf<PreparedDataExport>()
        val destination = Uri.parse("content://export/current.json")
        val vm = vm(save = { p, u -> saved += p to u }, discard = { discarded += it })
        try {
            rule.setContent { ExportTestScope { DataManagementScreen(vm, {}, {}) } }
            requestExport(vm)
            val old = vm.uiState.value.activeDataExportRequest!!
            rule.runOnIdle { vm.dataExportDestinationSelected(old.id, null) }
            await { vm.uiState.value.operation == DataManagementOperation.IDLE }
            requestExport(vm)
            val current = vm.uiState.value.activeDataExportRequest!!
            rule.runOnIdle { vm.dataExportDestinationSelected(old.id, destination) }
            rule.waitForIdle()
            assertTrue(saved.isEmpty())
            assertEquals(DataManagementOperation.AWAITING_DATA_EXPORT_LOCATION, vm.uiState.value.operation)
            rule.runOnIdle {
                vm.dataExportDestinationSelected(current.id, destination)
                vm.dataExportDestinationSelected(current.id, destination)
            }
            await { vm.uiState.value.operation == DataManagementOperation.IDLE }
            assertEquals(listOf(prepared(DataExportFormat.JSON) to destination), saved)
            assertEquals(2, discarded.size)
            assertFalse(vm.uiState.value.restoreCompleted)
        } finally { close(vm) }
    }

    @Test fun cancellingSaveWaitsForCleanupBeforeEnablingActions() {
        val save = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val discarded = mutableListOf<PreparedDataExport>()
        val vm = vm(save = { _, _ -> save.await() }, discard = { discarded += it; cleanup.await() })
        try {
            rule.setContent { ExportTestScope { DataManagementScreen(vm, {}, {}) } }
            requestExport(vm)
            val request = vm.uiState.value.activeDataExportRequest!!
            rule.runOnIdle { vm.dataExportDestinationSelected(request.id, Uri.parse("content://export/cancel.json")) }
            await { vm.uiState.value.operation == DataManagementOperation.SAVING_DATA_EXPORT }
            scrollTo("取消操作"); rule.onNodeWithText("取消操作").performClick()
            await { discarded.size == 1 }
            assertEquals(DataManagementOperation.CANCELLING, vm.uiState.value.operation)
            scrollTo("导出 JSON"); rule.onNodeWithText("导出 JSON").assertIsNotEnabled()
            rule.runOnIdle { cleanup.complete(Unit) }
            await { vm.uiState.value.operation == DataManagementOperation.IDLE }
            assertNull(vm.uiState.value.preparedDataExport)
            assertTrue(vm.uiState.value.result!!.contains("已取消"))
        } finally { save.cancel(); cleanup.complete(Unit); close(vm) }
    }

    @Test fun cleanupFailureAfterSaveDoesNotPresentCompletedExport() {
        val vm = vm(discard = { error("private-cleanup-path") })
        try {
            rule.setContent { ExportTestScope { DataManagementScreen(vm, {}, {}) } }
            requestExport(vm)
            val request = vm.uiState.value.activeDataExportRequest!!
            rule.runOnIdle { vm.dataExportDestinationSelected(request.id, Uri.parse("content://export/saved.json")) }
            await { vm.uiState.value.error != null }
            assertNull(vm.uiState.value.result)
            assertNull(vm.uiState.value.preparedDataExport)
            rule.onNodeWithText("private-cleanup-path").assertDoesNotExist()
        } finally { close(vm) }
    }

    private fun prepared(format: DataExportFormat) = PreparedDataExport(
        File(rule.activity.cacheDir, "private-export.${format.extension}"), 1_791_425_600_000L, format)
    private fun vm(
        prepare: suspend (DataExportFormat) -> PreparedDataExport = { prepared(it) },
        save: suspend (PreparedDataExport, Uri) -> Unit = { _, _ -> },
        discard: suspend (PreparedDataExport) -> Unit = {},
    ) = DataManagementViewModel(object : FullBackupService {
        override suspend fun prepareBackup(): PreparedBackup = error("No backup expected")
        override suspend fun saveBackup(prepared: PreparedBackup, destination: Uri) = error("No backup expected")
        override suspend fun inspectBackup(source: Uri): RestoreCandidate = error("No restore expected")
        override suspend fun restore(candidate: RestoreCandidate) = error("No restore expected")
    }, object : DataExportService {
        override suspend fun prepareExport(format: DataExportFormat) = prepare(format)
        override suspend fun saveExport(prepared: PreparedDataExport, destination: Uri) = save(prepared, destination)
        override suspend fun discardPreparedExport(prepared: PreparedDataExport) = discard(prepared)
    })
    private fun registry(intents: MutableList<Pair<Int, Intent>>) = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
            input: I, options: ActivityOptionsCompat?) {
            intents += requestCode to contract.createIntent(rule.activity, input)
        }
    }
    private fun await(condition: () -> Boolean) = rule.waitUntil(5_000, condition)
    private fun requestExport(vm: DataManagementViewModel) {
        rule.runOnIdle { vm.requestDataExport(DataExportFormat.JSON); vm.confirmDataExport() }
        await { vm.uiState.value.activeDataExportRequest != null }
    }
    private fun scrollTo(text: String) {
        rule.onNodeWithTag("data-management-list").performScrollToNode(hasText(text))
    }
    private fun close(vm: DataManagementViewModel) { rule.activityRule.scenario.close(); vm.viewModelScope.cancel() }
    private data class PickerCase(val index: Int, val label: String, val mime: String, val suffix: String)
}

@Composable
private fun ExportTestScope(registry: ActivityResultRegistry? = null, content: @Composable () -> Unit) {
    val owner = remember(registry) { object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry ?: object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) = Unit
        }
    } }
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { MirraTheme(content = content) }
}
