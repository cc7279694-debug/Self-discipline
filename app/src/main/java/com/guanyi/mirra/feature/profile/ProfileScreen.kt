package com.guanyi.mirra.feature.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.guanyi.mirra.ui.theme.MirraTheme
import com.guanyi.mirra.platform.focus.MonitoringDiagnosticsPanel
import com.guanyi.mirra.platform.focus.MonitoringPlatformRuntime
import com.guanyi.mirra.platform.focus.isMirraDebuggable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.guanyi.mirra.data.repository.FocusRepository
import com.guanyi.mirra.platform.focus.RiskAppCatalog
import com.guanyi.mirra.platform.focus.LaunchableRiskApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun ProfileScreen(viewModel: ProfileViewModel, modifier: Modifier = Modifier,
    debugMonitoring: MonitoringPlatformRuntime? = null, riskRepository: FocusRepository? = null,
    crossAppActions: CrossAppInterventionUserActions? = null,
    onOpenReadingHistory: () -> Unit = {}, onOpenTrends: () -> Unit = {},
    onOpenDataManagement: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dndState = viewModel.dndState?.collectAsStateWithLifecycle()?.value
    val lifecycleOwner = LocalLifecycleOwner.current
    var showDiagnostics by remember { mutableStateOf(false) }
    var showRiskApps by remember { mutableStateOf(false) }
    var launchableApps by remember { mutableStateOf<List<LaunchableRiskApp>>(emptyList()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val showDebug = debugMonitoring != null && context.isMirraDebuggable()
    val scope = rememberCoroutineScope()
    val riskAppsFlow = remember(riskRepository) { riskRepository?.observeRiskApps() }
    val selectedRiskApps = riskAppsFlow?.collectAsStateWithLifecycle(initialValue = emptyList())
        ?.value.orEmpty().map { it.packageName }.toSet()
    LaunchedEffect(showRiskApps) {
        if (showRiskApps) launchableApps = withContext(Dispatchers.IO) { RiskAppCatalog(context).list() }
    }
    LaunchedEffect(viewModel) {
        viewModel.refreshTimeContext()
        viewModel.refreshDnd()
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshTimeContext()
                viewModel.refreshDnd()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = "我的",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "你的学习数据只保存在这台设备上。",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MirraTheme.colors.textSecondary,
        )
        ProfileSummaryContent(state, Modifier.padding(top = 28.dp), onOpenTrends)
        com.guanyi.mirra.ui.components.MirraTextAction(onOpenReadingHistory,
            Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("阅读记录") }
        com.guanyi.mirra.ui.components.MirraTextAction(onOpenDataManagement,
            Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("数据管理") }
        dndState?.let { dnd ->
            DndSettingsContent(
                state = dnd,
                onEnabledChange = viewModel::setDndEnabled,
                onOpenSettings = {
                    viewModel.dndSettingsIntent()?.let { intent -> context.startActivity(intent) }
                },
                onRetryApply = viewModel::retryDndApply,
                onRetryRelease = viewModel::retryDndRelease,
                modifier = Modifier.padding(top = 28.dp),
            )
        }
        crossAppActions?.let { CrossAppInterventionSettings(it, Modifier.padding(top = 20.dp)) }
        if (riskRepository != null) {
            TextButton(onClick = { showRiskApps = true }, modifier = Modifier.padding(top = 20.dp)) {
                Text("风险 App")
            }
        }
        if (showDebug) {
            TextButton(onClick = { showDiagnostics = true }, modifier = Modifier.padding(top = 20.dp)) {
                Text("监测诊断（开发版）")
            }
        }
    }
    if (showDiagnostics && debugMonitoring != null && showDebug) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text("本地监测诊断") },
            text = { MonitoringDiagnosticsPanel(debugMonitoring, viewModel.dndState) },
            confirmButton = { TextButton(onClick = { showDiagnostics = false }) { Text("关闭") } },
        )
    }
    if (showRiskApps && riskRepository != null) {
        AlertDialog(
            onDismissRequest = { showRiskApps = false },
            title = { Text("选择风险 App") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("仅影响下一次学习；当前学习使用开始时的选择。",
                        color = MirraTheme.colors.textSecondary)
                    if (launchableApps.isEmpty()) Text("没有可选的 App")
                    launchableApps.forEach { app ->
                        Row(Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                if (app.packageName in selectedRiskApps) riskRepository.removeRiskApp(app.packageName)
                                else riskRepository.replaceRiskApp(app.packageName, app.label)
                            }
                        }.padding(vertical = 6.dp)) {
                            Checkbox(checked = app.packageName in selectedRiskApps, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(app.label, color = MirraTheme.colors.textPrimary)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                                    color = MirraTheme.colors.textTertiary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRiskApps = false }) { Text("完成") } },
        )
    }
}

@Composable
private fun DndSettingsContent(
    state: DndSettingsUiState,
    onEnabledChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onRetryApply: () -> Unit,
    onRetryRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("学习保护", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("学习时自动开启勿扰", color = MirraTheme.colors.textPrimary)
                Text(
                    when (state.status) {
                        DndPreferenceStatus.OFF -> "关闭"
                        DndPreferenceStatus.READY -> "已就绪"
                        DndPreferenceStatus.NEEDS_ACCESS -> "需要系统授权"
                    },
                    color = MirraTheme.colors.textSecondary,
                )
            }
            Switch(checked = state.enabled, onCheckedChange = onEnabledChange,
                modifier = Modifier.testTag("dnd-toggle"))
        }
        if (state.enabled) {
            Text(state.versionExplanation, color = MirraTheme.colors.textTertiary,
                style = MaterialTheme.typography.bodySmall)
        }
        if (state.enabled && !state.policyAccessGranted) {
            Text("需要系统勿扰权限", color = MirraTheme.colors.textSecondary)
            TextButton(onClick = onOpenSettings) { Text("去授权") }
        }
        if (state.showNextSessionHint) {
            Text("修改将在下一次学习时生效", color = MirraTheme.colors.textTertiary,
                style = MaterialTheme.typography.bodySmall)
        }
        when (state.activeLifecycle) {
            com.guanyi.mirra.data.local.entity.DndLifecycle.NOT_APPLIED ->
                Text("本次未启用勿扰", color = MirraTheme.colors.textSecondary)
            com.guanyi.mirra.data.local.entity.DndLifecycle.ACTIVE ->
                Text("本次勿扰已开启", color = MirraTheme.colors.textSecondary)
            com.guanyi.mirra.data.local.entity.DndLifecycle.APPLY_FAILED -> {
                Text("本次勿扰未能开启", color = MirraTheme.colors.danger)
                TextButton(onClick = onRetryApply) { Text("重试开启勿扰") }
            }
            com.guanyi.mirra.data.local.entity.DndLifecycle.RELEASE_PENDING,
            com.guanyi.mirra.data.local.entity.DndLifecycle.RELEASE_FAILED -> {
                Text("Mirra 勿扰状态需要处理", color = MirraTheme.colors.danger)
                TextButton(onClick = onRetryRelease) { Text("重试") }
            }
            com.guanyi.mirra.data.local.entity.DndLifecycle.RELEASED,
            null -> Unit
        }
        if (state.pendingRelease) {
            Text("Mirra 勿扰状态需要处理", color = MirraTheme.colors.danger)
            TextButton(onClick = onRetryRelease) { Text("重试") }
            if (!state.policyAccessGranted) TextButton(onClick = onOpenSettings) { Text("去授权") }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun ProfileSummaryContent(state: ProfileUiState, modifier: Modifier = Modifier, onOpenTrends: () -> Unit = {}) {
    var more by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text("本周学习", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("最近 7 天", color = MirraTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            com.guanyi.mirra.ui.components.MirraTextAction(onOpenTrends) { Text("详细分析") }
        }
        if (state.isLoading) {
            Text("正在整理本地记录…", color = MirraTheme.colors.textTertiary)
            return@Column
        }
        state.error?.let { Text(it, color = MirraTheme.colors.danger); return@Column }
        val reading = state.trends?.normalReading
        val previousReading = state.trends?.previousNormalReading
        val durationText = if (reading == null) state.totalDurationText else
            reading.totalDurationMillis?.let { com.guanyi.mirra.feature.session.recordDuration(it) } ?: "暂无可确认数据"
        Text(durationText, style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("weekly-reading-duration"))
        Text("阅读时长", color = MirraTheme.colors.textSecondary)
        if (reading == null || reading.totalDurationMillis != null && reading.dataIssueCount == 0L && !reading.durationOverflow &&
            previousReading?.totalDurationMillis != null && previousReading.dataIssueCount == 0L && !previousReading.durationOverflow) {
            state.comparisonText?.let { Text(it, color = MirraTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium) }
        }
        if ((reading?.dataIssueCount ?: 0L) > 0L) Text("仅累计可确认时长，另有无法确认的记录。", color = MirraTheme.colors.textSecondary)
        if (state.isEmpty && state.sessionCount == 0 && (reading?.dataIssueCount ?: 0L) == 0L && reading?.durationOverflow != true) {
            Text("最近 7 天还没有正常结束的阅读记录。", color = MirraTheme.colors.textSecondary)
        }
        DailyReadingChart(state.trends?.daily.orEmpty(), "weekly-reading-chart")
        val start = state.trends?.current?.start
        val recover = state.trends?.current?.recover
        val maintain = state.trends?.current?.maintain
        state.trendsError?.let { Text(it, color = MirraTheme.colors.danger) }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            WeeklyAuxiliary("开始转化率", start?.conversion?.value?.takeIf { it.isFinite() }?.let {
                "${(it * 100).roundToInt()}%"
            } ?: if (state.trendsError != null) "暂无可确认数据" else "数据积累中", start?.let {
                "${it.conversion.denominator} 次已结束计划，${it.convertedCount} 次真正开始"
            } ?: "暂无可确认数据", "weekly-start")
            WeeklyAuxiliary("分心后恢复", when {
                recover == null -> "暂无可确认数据"
                recover.attemptCount == 0L && (maintain?.trustedSessionCount ?: 0L) == 0L -> "暂无可确认数据"
                else -> "${recover.successCount} 次"
            }, "根据可确认记录", "weekly-recover")
        }
        HorizontalDivider(color = MirraTheme.colors.divider)
        com.guanyi.mirra.ui.components.MirraTextAction({ more = !more }) {
            Text(if (more) "收起阅读数据" else "更多阅读数据")
        }
        if (more) {
            FactRow("正常阅读", "${state.sessionCount} 次")
            FactRow("推进页数", "${state.pagesRead} 页")
            FactRow("新增笔记", "${state.noteCount} 条")
        }
    }
}

@Composable
private fun WeeklyAuxiliary(label: String, value: String, explanation: String, tag: String) {
    Column(Modifier.widthIn(min = 100.dp, max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = MirraTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(tag), color = MirraTheme.colors.accentStrong)
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = MirraTheme.colors.textSecondary)
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MirraTheme.colors.textSecondary)
        Text(value, color = MirraTheme.colors.textPrimary, fontWeight = FontWeight.Medium)
    }
}
