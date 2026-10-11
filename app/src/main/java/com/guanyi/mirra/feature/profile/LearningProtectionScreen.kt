package com.guanyi.mirra.feature.profile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.repository.FocusRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.platform.focus.AndroidNotificationGateway
import com.guanyi.mirra.platform.focus.CapabilityStatus
import com.guanyi.mirra.platform.focus.MonitoringPlatformRuntime
import com.guanyi.mirra.platform.focus.isMirraDebuggable
import com.guanyi.mirra.platform.intervention.NotificationInterventionPresenter
import com.guanyi.mirra.platform.intervention.OverlayInterventionPresenter
import com.guanyi.mirra.ui.components.MirraTextAction
import com.guanyi.mirra.ui.components.MirraToggle
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private data class CurrentLearningProtectionFacts(
    val session: StudySessionEntity? = null,
    val context: SessionFocusContextEntity? = null,
    val error: String? = null,
)

@Composable
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun LearningProtectionScreen(
    runtime: MonitoringPlatformRuntime?,
    workflow: StudyWorkflowRepository,
    focusRepository: FocusRepository,
    dndActions: DndUserActions,
    crossAppActions: CrossAppInterventionUserActions?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val dnd by dndActions.state.collectAsStateWithLifecycle()
    val crossApp = crossAppActions?.state?.collectAsStateWithLifecycle()?.value
    val capabilities = runtime?.capabilities?.state?.collectAsStateWithLifecycle()?.value
    var refreshGeneration by remember { mutableIntStateOf(0) }
    val factsFlow = remember(workflow, focusRepository, refreshGeneration) {
        workflow.observeActiveSession().flatMapLatest { current ->
            if (current == null) flowOf(CurrentLearningProtectionFacts())
            else focusRepository.observeContext(current.id).map { CurrentLearningProtectionFacts(current, it) }
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(CurrentLearningProtectionFacts(error = "暂时无法读取本次学习状态"))
        }
    }
    val facts by factsFlow.collectAsStateWithLifecycle(initialValue = CurrentLearningProtectionFacts())
    var elapsedNowMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val binding = runtime?.binding
    val monitoring = remember(capabilities, binding, facts, elapsedNowMillis) {
        // A new query can arrive between display ticks; sample now rather than compare it with an older tick.
        projectLearningProtection(capabilities, binding, facts.session, facts.context, SystemClock.elapsedRealtime())
    }
    var notificationGranted by remember(runtime) { mutableStateOf<Boolean?>(null) }
    var monitoringChannelUsable by remember(runtime) { mutableStateOf<Boolean?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }

    suspend fun refresh() {
        try {
            val notifications = withContext(Dispatchers.IO) {
                // AppOp/unlock only. No UsageEvents query or Service start on this page.
                runtime?.recheckUsageWithoutQuery()
                dndActions.refresh()
                crossAppActions?.refresh()
                val granted = runtime?.notificationGateway?.runtimePermissionGranted()
                    ?: crossAppActions?.state?.value?.capabilities?.notificationGranted
                val channel = runtime?.notificationGateway?.let { granted == true && it.channelEnabled() }
                granted to channel
            }
            notificationGranted = notifications.first
            monitoringChannelUsable = notifications.second
            refreshError = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            notificationGranted = null
            monitoringChannelUsable = null
            refreshError = "暂时无法读取系统设置，请重试"
        }
    }
    fun perform(block: suspend () -> Unit) {
        scope.launch {
            actionError = null
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { actionError = "设置未能完成，请重试" }
        }
    }
    fun open(intent: Intent) {
        try { context.startActivity(intent); actionError = null }
        catch (_: RuntimeException) { actionError = "系统设置暂不可用，请稍后重试" }
    }
    fun notificationSettings(channel: String): Intent = if (Build.VERSION.SDK_INT >= 26) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
    } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { refresh() }
    }
    LaunchedEffect(runtime, dndActions, crossAppActions) { refresh() }
    LaunchedEffect(owner, runtime) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                elapsedNowMillis = SystemClock.elapsedRealtime()
                delay(1_000)
            }
        }
    }
    DisposableEffect(owner, runtime, dndActions, crossAppActions) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                elapsedNowMillis = SystemClock.elapsedRealtime()
                scope.launch { refresh() }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val permissions = LearningProtectionPermissionsUiState(
        usage = capabilities?.usage,
        dndGranted = dnd.policyAccessGranted,
        overlayGranted = crossApp?.capabilities?.overlayGranted,
        overlaySupported = Build.VERSION.SDK_INT >= 26,
        notificationGranted = notificationGranted,
        monitoringNotificationUsable = monitoringChannelUsable,
        interventionNotificationUsable = crossApp?.capabilities?.notificationAvailable,
    )
    Column(modifier.fillMaxSize().background(MirraTheme.colors.background)
        .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MirraTextAction(onBack) { Text("返回") }
        Text("学习保护", style = MaterialTheme.typography.headlineLarge)
        Text("只在本次学习期间监测手机使用。系统权限、服务状态和本次监测记录分别显示。",
            color = MirraTheme.colors.textSecondary)
        LearningProtectionStatusContent(monitoring, permissions,
            onUsageSettings = { runtime?.let { open(it.usageSettingsIntent()) }
                ?: open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            onDndSettings = { open(dndActions.settingsIntent()) },
            onOverlaySettings = { open(OverlayInterventionPresenter.settingsIntent(context)) },
            onNotificationPermission = {
                if (Build.VERSION.SDK_INT >= 33 && notificationGranted != true) {
                    try { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    catch (_: RuntimeException) { actionError = "通知权限申请暂不可用，请稍后重试" }
                } else open(notificationSettings(AndroidNotificationGateway.CHANNEL_ID))
            },
            onMonitoringNotificationSettings = { open(notificationSettings(AndroidNotificationGateway.CHANNEL_ID)) },
            onInterventionNotificationSettings = { open(notificationSettings(NotificationInterventionPresenter.CHANNEL_ID)) },
        )
        (facts.error ?: refreshError)?.let {
            Text(it, color = MirraTheme.colors.danger)
            MirraTextAction({ refreshGeneration++; scope.launch { refresh() } }) { Text("重新检查") }
        }
        HorizontalDivider(color = MirraTheme.colors.divider)
        Text("勿扰", style = MaterialTheme.typography.titleLarge)
        DndSettingsContent(dnd, { enabled -> perform { dndActions.setEnabled(enabled) } },
            { open(dndActions.settingsIntent()) }, { perform { dndActions.retryApply() } },
            { perform { dndActions.retryRelease() } }, showHeading = false)
        crossAppActions?.let { actions ->
            HorizontalDivider(color = MirraTheme.colors.divider)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("跨应用提醒", style = MaterialTheme.typography.titleLarge)
                    Text(crossApp?.status ?: "暂不可确认", color = MirraTheme.colors.textSecondary)
                }
                MirraToggle(crossApp?.enabled == true, { enabled -> perform { actions.setEnabled(enabled) } },
                    Modifier.testTag("cross-app-toggle"))
            }
            Text("打开风险 App 时，优先使用悬浮提醒；不可用时尝试通知。",
                color = MirraTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text("修改影响下一次学习，本次仍使用开始时的设置。",
                color = MirraTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
            crossApp?.error?.let { Text(it, color = MirraTheme.colors.danger) }
        }
        HorizontalDivider(color = MirraTheme.colors.divider)
        RiskAppsSettings(focusRepository)
        actionError?.let { Text(it, color = MirraTheme.colors.danger) }
        if (runtime != null && context.isMirraDebuggable()) {
            MirraTextAction({ showDiagnostics = true }) { Text("监测诊断（开发版）") }
        }
    }
    if (showDiagnostics && runtime != null && context.isMirraDebuggable()) AlertDialog(
        onDismissRequest = { showDiagnostics = false }, title = { Text("本地监测诊断") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Usage: ${capabilities?.usage}")
                Text("Service: ${capabilities?.service?.phase} / READY: ${capabilities?.monitoringReady}")
                Text("Service generation: ${capabilities?.service?.generation ?: "—"}")
                Text("Bound Session: ${runtime.binding?.sessionId ?: "—"}")
                Text("Bound generation: ${runtime.binding?.generation ?: "—"}")
                Text("Current Session: ${monitoring.activeSessionId ?: "—"} / Coverage: ${monitoring.coverage ?: "—"}")
                Text("DND: ${dnd.activeLifecycle ?: "—"} / pending release: ${dnd.pendingRelease}")
            }
        },
        confirmButton = { MirraTextAction({ showDiagnostics = false }) { Text("关闭") } },
    )
}

@Composable
fun LearningProtectionStatusContent(
    monitoring: LearningProtectionMonitoringUiState,
    permissions: LearningProtectionPermissionsUiState,
    onUsageSettings: () -> Unit,
    onDndSettings: () -> Unit,
    onOverlaySettings: () -> Unit,
    onNotificationPermission: () -> Unit,
    onMonitoringNotificationSettings: () -> Unit,
    onInterventionNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("本次监测", style = MaterialTheme.typography.titleLarge)
        Text(when (monitoring.coverage) {
            MonitoringCoverage.FULL -> "本次监测：完整"
            MonitoringCoverage.PARTIAL -> "本次监测：部分记录，已有中断或限制"
            MonitoringCoverage.NONE -> "本次未开启分心监测"
            null -> "当前没有可确认的活动学习"
        }, modifier = Modifier.testTag("learning-monitoring-coverage"), color = MirraTheme.colors.textPrimary)
        Text(when (monitoring.serviceStatus) {
            LearningProtectionServiceStatus.INACTIVE -> "监测服务未用于活动学习"
            LearningProtectionServiceStatus.STOPPED -> "本次监测服务未运行"
            LearningProtectionServiceStatus.PREPARING -> "本次监测服务正在准备"
            LearningProtectionServiceStatus.READY -> "本次监测服务已就绪"
            LearningProtectionServiceStatus.UNAVAILABLE -> "本次监测服务暂不可用"
            LearningProtectionServiceStatus.NOT_CONFIRMED -> "尚未确认本次监测服务"
        }, modifier = Modifier.testTag("learning-monitoring-service"), color = MirraTheme.colors.textSecondary)
        if (monitoring.coverage == MonitoringCoverage.NONE || monitoring.coverage == MonitoringCoverage.PARTIAL) {
            Text("可以继续阅读和记录；系统设置修复后不改写本次已有监测记录，下一次学习会重新检查。",
                color = MirraTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Text("系统权限", style = MaterialTheme.typography.titleLarge)
        PermissionItem("使用情况访问", when (permissions.usage) {
            CapabilityStatus.AVAILABLE -> "已授权"
            CapabilityStatus.NEEDS_USER_ACTION -> "未授权"
            CapabilityStatus.TEMPORARILY_UNAVAILABLE -> "暂不可确认"
            CapabilityStatus.UNAVAILABLE -> "当前不可用"
            null -> "暂不可确认"
        }, "打开使用情况设置", onUsageSettings, "permission-usage")
        PermissionItem("勿扰访问", authorizationText(permissions.dndGranted),
            "打开勿扰设置", onDndSettings, "permission-dnd")
        PermissionItem("悬浮显示", if (permissions.overlaySupported) authorizationText(permissions.overlayGranted)
            else "当前系统不支持悬浮提醒", "打开悬浮设置", onOverlaySettings, "permission-overlay",
            enabled = permissions.overlaySupported)
        PermissionItem("通知权限", authorizationText(permissions.notificationGranted),
            "检查通知权限", onNotificationPermission, "permission-notification")
        Text("监测状态通知：${channelText(permissions.monitoringNotificationUsable)}",
            modifier = Modifier.testTag("monitoring-notification-channel"), color = MirraTheme.colors.textSecondary)
        MirraTextAction(onMonitoringNotificationSettings) { Text("监测通知设置") }
        Text("学习提醒通知：${channelText(permissions.interventionNotificationUsable)}",
            modifier = Modifier.testTag("intervention-notification-channel"), color = MirraTheme.colors.textSecondary)
        MirraTextAction(onInterventionNotificationSettings) { Text("学习提醒通知设置") }
        Text("授权与通知渠道可用性分别检查。通知权限不会决定本次分心监测是否完整。",
            color = MirraTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PermissionItem(title: String, status: String, action: String, onAction: () -> Unit,
    tag: String, enabled: Boolean = true) {
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(status, color = MirraTheme.colors.textSecondary)
        MirraTextAction(onAction, enabled = enabled) { Text(action) }
    }
}

private fun authorizationText(granted: Boolean?): String = when (granted) {
    true -> "已授权"
    false -> "未授权"
    null -> "暂不可确认"
}

private fun channelText(usable: Boolean?): String = when (usable) {
    true -> "可用"
    false -> "尚不可用，请检查通知设置"
    null -> "暂不可确认"
}
