package com.guanyi.mirra.platform.focus

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guanyi.mirra.feature.profile.DndSettingsUiState
import kotlinx.coroutines.flow.StateFlow

fun Context.isMirraDebuggable(): Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

/** Never exposed in release builds and never persists a user's foreground-app trail. */
@Composable
fun MonitoringDiagnosticsPanel(
    runtime: MonitoringPlatformRuntime,
    dndState: StateFlow<DndSettingsUiState>? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by runtime.capabilities.state.collectAsStateWithLifecycle()
    val dnd by dndState?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf<DndSettingsUiState?>(null)
    }
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("监测诊断（仅开发版）")
        Text("Service: ${state.service.phase} / generation: ${state.service.generation ?: "—"}")
        Text("Foreground ACK: ${state.service.foregroundAck}")
        Text("Usage: ${state.usage} / Notifications visible: ${state.notification.drawerVisible} / DND: ${state.dnd}")
        Text("UsageMonitor running: ${state.monitor.running} / READY: ${state.monitoringReady}")
        Text("Last query elapsed: ${state.monitor.lastSuccessfulQueryElapsed ?: "—"}")
        Text("Last foreground evidence elapsed: ${state.monitor.lastForegroundEvidenceElapsed ?: "—"}")
        Text("Cursor wall: ${state.monitor.cursorWallMillis ?: "—"} / query generation: ${state.monitor.queryGeneration}")
        runtime.factDiagnostics?.let { facts ->
            Text("Bound Session: ${facts.sessionId ?: "—"} / generation: ${facts.generation ?: "—"}")
            Text("Segment: ${facts.segment ?: "—"} / Coverage: ${facts.coverage ?: "—"}")
            Text("Risk snapshot: ${facts.riskSnapshotCount} / candidate: ${facts.candidatePackage ?: "—"}")
            Text("Candidate token: ${facts.candidateToken ?: "—"} / elapsed start: ${facts.candidateFirstSeenElapsed ?: "—"}")
            Text("Room heartbeat: ${facts.lastHeartbeatAt ?: "—"} / gap: ${facts.gapAt ?: "—"} ${facts.gapReason ?: ""}")
        }
        Text("Observation: ${state.monitor.observation}")
        Text("Device signal: ${state.monitor.lastDeviceSignal ?: "—"}")
        Text("Last error: ${state.monitor.lastPlatformError ?: state.service.lastError ?: "—"}")
        dnd?.let { snapshot ->
            Text("DND preference: ${if (snapshot.enabled) "ON" else "OFF"}")
            Text("DND policy access: ${snapshot.policyAccessGranted}")
            Text("Active Session ID: ${snapshot.activeSessionId ?: "—"}")
            Text("Active DND lifecycle: ${snapshot.activeLifecycle ?: "—"}")
            Text("Prior filter: ${snapshot.priorFilterPresent}")
            Text("Mirra rule ID: ${snapshot.mirraRuleId ?: "—"}")
            Text("Pending release: ${snapshot.pendingRelease}")
        }
        runtime.interventionChannels?.let { channels ->
            val delivery by channels.presenter.diagnostics.collectAsStateWithLifecycle()
            val visible by channels.appVisible.collectAsStateWithLifecycle()
            val presentationError by channels.error.collectAsStateWithLifecycle()
            val prompt by runtime.focusSessionActions.intervention.collectAsStateWithLifecycle()
            val preference = (context.applicationContext as? com.guanyi.mirra.MirraApplication)?.container
                ?.appPreferencesRepository?.crossAppInterventionEnabled
                ?.collectAsStateWithLifecycle(initialValue = false)?.value ?: false
            val caps = channels.capabilities()
            Text("Cross-app preference: $preference / Session snapshot: ${channels.snapshotEnabled()}")
            Text("App visible: $visible / Prompt present: ${prompt?.let { !it.dismissed } == true}")
            Text("Delivery channel: ${delivery.channel} / Receipt: ${delivery.receipt ?: "—"}")
            Text("Overlay access: ${caps.overlayAvailable} / Notification access: ${caps.notificationGranted} / Channel enabled: ${caps.notificationChannelEnabled}")
            Text("Presentation error: ${delivery.error ?: presentationError ?: "—"}")
        }
        error?.let { Text("启动失败：$it") }
        Button(onClick = {
            error = runCatching { runtime.startFromUserAction() }.exceptionOrNull()?.javaClass?.simpleName
        }) { Text("启动监测测试") }
        OutlinedButton(onClick = runtime::stopFromUserAction) { Text("停止监测测试") }
        OutlinedButton(onClick = { context.startActivity(runtime.usageSettingsIntent()) }) { Text("打开使用情况权限设置") }
        OutlinedButton(onClick = { context.startActivity(runtime.dndSettingsIntent()) }) { Text("打开勿扰权限设置") }
    }
}
