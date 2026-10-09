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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.core.app.ActivityOptionsCompat
import com.guanyi.mirra.domain.backup.FullBackupService
import com.guanyi.mirra.domain.backup.PreparedBackup
import com.guanyi.mirra.domain.backup.RestoreCandidate
import com.guanyi.mirra.domain.backup.FullBackupErrorCode
import com.guanyi.mirra.domain.backup.FullBackupOperationException
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

class DataManagementUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val candidate = RestoreCandidate("private-token", 1_791_425_600_000L, 2, 3, 4,
        appVersion = "0.1.0", schemaVersion = 4, backupFormatVersion = 1)

    @Test fun validatedSummaryRequiresSeparateFullReplacementConfirmation() {
        var restoreCount = 0
        var completions = 0
        val vm = DataManagementViewModel(service(restore = { restoreCount++ }))
        try {
            rule.setContent { DataManagementTestScope { DataManagementScreen(vm, {}, { completions++ }) } }
            chooseCandidate(vm)
            scrollTo("学习内容：2 项")
            rule.onNodeWithText("学习内容：2 项").assertIsDisplayed()
            scrollTo("笔记：3 条")
            rule.onNodeWithText("笔记：3 条").assertIsDisplayed()
            assertEquals(0, restoreCount)
            rule.runOnIdle { vm.confirmRestore() }
            assertEquals(0, restoreCount)
            scrollTo("恢复这份备份")
            rule.onNodeWithText("恢复这份备份").performClick()
            rule.onNodeWithText("替换当前全部数据？").assertIsDisplayed()
            rule.onNodeWithText("当前学习内容、记录、笔记、图片与设置将被这份备份完整替换，不会合并。恢复前会保存当前数据的安全快照。").assertExists()
            assertEquals(0, restoreCount)
            rule.onNodeWithText("取消").performClick()
            assertEquals(0, restoreCount)
            scrollTo("恢复这份备份")
            rule.onNodeWithText("恢复这份备份").performClick()
            rule.onNodeWithText("确认完整替换").performClick()
            await { completions == 1 }
            rule.waitForIdle()
            assertEquals(1, restoreCount)
            assertEquals(1, completions)
        } finally { close(vm) }
    }

    @Test fun restoreFailureKeepsValidatedCandidateAndRequiresConfirmationAgain() {
        val vm = DataManagementViewModel(service(restore = { error("private/db/path") }))
        try {
            rule.setContent { DataManagementTestScope { DataManagementScreen(vm, {}, {}) } }
            chooseCandidate(vm)
            scrollTo("恢复这份备份")
            rule.onNodeWithText("恢复这份备份").performClick()
            rule.onNodeWithText("确认完整替换").performClick()
            await { vm.uiState.value.error != null }
            assertEquals(candidate, vm.uiState.value.restoreCandidate)
            assertFalse(vm.uiState.value.showRestoreConfirmation)
            assertFalse(vm.uiState.value.restoreCompleted)
            rule.onNodeWithText("private/db/path").assertDoesNotExist()
            scrollTo("恢复这份备份")
            rule.onNodeWithText("恢复这份备份").performClick()
            rule.onNodeWithText("替换当前全部数据？").assertIsDisplayed()
        } finally { close(vm) }
    }

    @Test fun inspectionFailureDoesNotOfferRestoreOrExposePrivateFailure() {
        val vm = DataManagementViewModel(service(inspect = { error("private-note.jpg") }))
        try {
            rule.setContent { DataManagementTestScope { DataManagementScreen(vm, {}, {}) } }
            rule.runOnIdle { vm.requestImport(); vm.importPickerLaunched(vm.uiState.value.importRequest!!); vm.importSourceSelected(Uri.parse("content://backup/invalid")) }
            await { vm.uiState.value.error != null }
            assertNull(vm.uiState.value.restoreCandidate)
            assertEquals(DataManagementOperation.IDLE, vm.uiState.value.operation)
            rule.onNodeWithText("恢复这份备份").assertDoesNotExist()
            rule.onNodeWithText("private-note.jpg").assertDoesNotExist()
        } finally { close(vm) }
    }

    @Test fun unresolvedRestoreKeepsCandidateAndDisablesAllBusinessExitsWithoutClaimingRollback() {
        var restores = 0
        val vm = DataManagementViewModel(service(restore = {
            restores++
            throw FullBackupOperationException(FullBackupErrorCode.RESTORE_BLOCKED, IllegalStateException("/private/rollback"))
        }))
        try {
            rule.setContent { DataManagementTestScope { DataManagementScreen(vm, {}, {}) } }
            chooseCandidate(vm)
            scrollTo("恢复这份备份")
            rule.onNodeWithText("恢复这份备份").performClick()
            rule.onNodeWithText("确认完整替换").performClick()
            await { vm.uiState.value.maintenanceBlocked }
            assertEquals(candidate, vm.uiState.value.restoreCandidate)
            assertFalse(vm.uiState.value.restoreCompleted)
            assertNull(vm.uiState.value.result)
            rule.onNodeWithText("/private/rollback").assertDoesNotExist()
            for (text in listOf("恢复这份备份", "取消这份备份", "返回")) {
                scrollTo(text); rule.onNodeWithText(text).assertIsNotEnabled()
            }
            rule.runOnIdle { vm.requestRestoreConfirmation(); vm.confirmRestore(); vm.cancelRestoreCandidate() }
            assertEquals(1, restores)
            assertEquals(candidate, vm.uiState.value.restoreCandidate)
        } finally { close(vm) }
    }

    @Test fun verifyingShowsProgressAndCancelThenClearsCandidate() {
        val response = CompletableDeferred<RestoreCandidate>()
        val vm = DataManagementViewModel(service(inspect = { response.await() }))
        try {
            rule.setContent { DataManagementTestScope { DataManagementScreen(vm, {}, {}) } }
            rule.runOnIdle { vm.requestImport(); vm.importPickerLaunched(vm.uiState.value.importRequest!!); vm.importSourceSelected(Uri.parse("content://backup/slow")) }
            await { vm.uiState.value.operation == DataManagementOperation.VERIFYING_BACKUP }
            scrollTo("正在验证备份…")
            rule.onNodeWithText("正在验证备份…").assertIsDisplayed()
            scrollTo("取消操作")
            rule.onNodeWithText("取消操作").performClick()
            await { vm.uiState.value.operation == DataManagementOperation.IDLE }
            assertNull(vm.uiState.value.restoreCandidate)
            assertFalse(vm.uiState.value.restoreCompleted)
        } finally { response.cancel(); close(vm) }
    }

    @Test fun successfulExportUsesOnlyThePreparedPackageAndDoesNotRestore() {
        val prepared = PreparedBackup(File(rule.activity.cacheDir, "validated.zip"), 1_791_425_600_000L, 2, 3, 4)
        val destination = Uri.parse("content://backup/new.zip")
        val vm = DataManagementViewModel(service(prepare = { prepared }, save = { source, uri ->
            assertEquals(prepared, source); assertEquals(destination, uri)
        }))
        try {
            rule.setContent { DataManagementTestScope { DataManagementScreen(vm, {}, {}) } }
            rule.runOnIdle { vm.createBackup() }
            await { vm.uiState.value.preparedBackup != null }
            rule.runOnIdle { vm.exportDestinationSelected(destination) }
            await { vm.uiState.value.result != null }
            assertEquals("完整备份已保存。请妥善保管这份文件。", vm.uiState.value.result)
            assertEquals(DataManagementOperation.IDLE, vm.uiState.value.operation)
            assertFalse(vm.uiState.value.restoreCompleted)
            assertNull(vm.uiState.value.preparedBackup)
        } finally { close(vm) }
    }

    @Test fun exportFailureDoesNotPresentSuccessOrChangeRestoreState() {
        val prepared = PreparedBackup(File(rule.activity.cacheDir, "validated.zip"), 1_791_425_600_000L, 2, 3, 4)
        val vm = DataManagementViewModel(service(prepare = { prepared }, save = { _, _ -> error("private-provider-token") }))
        try {
            rule.setContent { DataManagementTestScope { DataManagementScreen(vm, {}, {}) } }
            rule.runOnIdle { vm.createBackup() }
            await { vm.uiState.value.preparedBackup != null }
            rule.runOnIdle { vm.exportDestinationSelected(Uri.parse("content://backup/failure.zip")) }
            await { vm.uiState.value.error != null }
            assertNull(vm.uiState.value.result)
            assertFalse(vm.uiState.value.restoreCompleted)
            rule.onNodeWithText("private-provider-token").assertDoesNotExist()
        } finally { close(vm) }
    }

    @Test fun actionsAndSummaryRemainReachableAt320DpDoubleFont() {
        var exits = 0
        val density = rule.activity.resources.displayMetrics.density
        val vm = DataManagementViewModel(service())
        try {
            rule.setContent { CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                DataManagementTestScope { Box(Modifier.width(320.dp).height(560.dp).testTag("data-test-container")) {
                    DataManagementScreen(vm, { exits++ }, {})
                } }
            } }
            for (text in listOf("创建完整备份", "选择并验证备份", "返回")) {
                scrollTo(text)
                val node = rule.onNode(hasText(text) and hasClickAction())
                node.assertIsDisplayed()
                assertTrue(node.fetchSemanticsNode().boundsInRoot.height / density >= 47.9f)
            }
            rule.onNodeWithText("返回").performClick()
            assertEquals(1, exits)
            chooseCandidate(vm)
            for (text in listOf("图片：4 张", "恢复这份备份", "取消这份备份", "返回")) {
                scrollTo(text); rule.onNodeWithText(text).assertIsDisplayed()
            }
        } finally { close(vm) }
    }

    @Test fun systemDocumentContractsWaitForPreparationAndCancellationNeverRestores() {
        val response = CompletableDeferred<PreparedBackup>()
        val intents = mutableListOf<Pair<Int, Intent>>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) {
                intents += requestCode to contract.createIntent(rule.activity, input)
            }
        }
        val vm = DataManagementViewModel(service(prepare = { response.await() }))
        try {
            rule.setContent { DataManagementTestScope(registry) { DataManagementScreen(vm, {}, {}) } }
            scrollTo("创建完整备份")
            rule.onNodeWithText("创建完整备份").performClick()
            await { vm.uiState.value.operation == DataManagementOperation.PREPARING_BACKUP }
            rule.runOnIdle { assertTrue(intents.isEmpty()); response.complete(PreparedBackup(
                File(rule.activity.cacheDir, "prepared.zip"), 1_791_425_600_000L, 2, 3, 4)) }
            await { intents.size == 1 }
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intents[0].second.action)
            assertEquals("application/zip", intents[0].second.type)
            assertTrue(intents[0].second.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".zip"))
            rule.runOnIdle { registry.dispatchResult(intents[0].first, Activity.RESULT_CANCELED, null) }
            await { vm.uiState.value.operation == DataManagementOperation.IDLE }
            assertNull(vm.uiState.value.preparedBackup)
            scrollTo("选择并验证备份")
            rule.onNodeWithText("选择并验证备份").performClick()
            await { intents.size == 2 }
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, intents[1].second.action)
            assertTrue(intents[1].second.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.contains("application/zip"))
            rule.runOnIdle { registry.dispatchResult(intents[1].first, Activity.RESULT_CANCELED, null) }
            await { vm.uiState.value.operation == DataManagementOperation.IDLE }
            assertNull(vm.uiState.value.restoreCandidate)
            assertFalse(vm.uiState.value.restoreCompleted)
        } finally { response.cancel(); close(vm) }
    }

    private fun chooseCandidate(vm: DataManagementViewModel) {
        rule.runOnIdle { vm.requestImport(); vm.importPickerLaunched(vm.uiState.value.importRequest!!); vm.importSourceSelected(Uri.parse("content://backup/validated.zip")) }
        await { vm.uiState.value.restoreCandidate != null }
    }
    private fun await(condition: () -> Boolean) = rule.waitUntil(5_000, condition)
    private fun scrollTo(text: String) { rule.onNode(hasScrollAction()).performScrollToNode(hasText(text)) }
    private fun close(vm: DataManagementViewModel) { rule.activityRule.scenario.close(); vm.viewModelScope.cancel() }
    private fun service(
        prepare: suspend () -> PreparedBackup = { error("No preparation expected") },
        inspect: suspend () -> RestoreCandidate = { candidate },
        restore: suspend () -> Unit = { error("No restore expected") },
        save: suspend (PreparedBackup, Uri) -> Unit = { _, _ -> error("No save expected") },
    ) = object : FullBackupService {
        override suspend fun prepareBackup() = prepare()
        override suspend fun saveBackup(prepared: PreparedBackup, destination: Uri) = save(prepared, destination)
        override suspend fun inspectBackup(source: Uri) = inspect()
        override suspend fun restore(candidate: RestoreCandidate) = restore()
    }
}

/** The OS document provider is tested separately; these tests supply its result to the real UI model. */
@Composable
private fun DataManagementTestScope(registry: ActivityResultRegistry? = null, content: @Composable () -> Unit) {
    val owner = remember(registry) { object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry ?: object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) = Unit
        }
    } }
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { MirraTheme(content = content) }
}
