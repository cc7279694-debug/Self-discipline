package com.guanyi.mirra.feature.profile

import android.Manifest
import android.os.Build
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guanyi.mirra.platform.intervention.OverlayInterventionPresenter
import com.guanyi.mirra.platform.intervention.NotificationInterventionPresenter
import com.guanyi.mirra.ui.components.MirraToggle
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.launch

@Composable
fun CrossAppInterventionSettings(actions: CrossAppInterventionUserActions, modifier: Modifier = Modifier) {
    val state by actions.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var launchError by remember { mutableStateOf<String?>(null) }
    fun open(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: RuntimeException) { launchError = "系统设置暂不可用" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { actions.refresh() }
    }
    LaunchedEffect(actions) { actions.refresh() }
    DisposableEffect(owner, actions) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { actions.refresh() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    CrossAppInterventionSettingsContent(state, { scope.launch { actions.setEnabled(it) } },
        { open(OverlayInterventionPresenter.settingsIntent(context)) },
        { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) },
        {
            open(if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, NotificationInterventionPresenter.CHANNEL_ID)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }, modifier)
    launchError?.let { Text(it, color = MirraTheme.colors.danger) }
}

@Composable
fun CrossAppInterventionSettingsContent(state: CrossAppInterventionSettingsState,
    onEnabled: (Boolean) -> Unit, onOverlay: () -> Unit, onNotification: () -> Unit,
    onChannelSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("跨应用提醒", color = MirraTheme.colors.textPrimary)
                Text(state.status, color = MirraTheme.colors.textSecondary)
            }
            MirraToggle(state.enabled, onEnabled, Modifier.testTag("cross-app-toggle"))
        }
        if (state.enabled) {
            Text("打开风险 App 时，优先使用悬浮提醒；不可用时尝试通知。", style = MaterialTheme.typography.bodySmall,
                color = MirraTheme.colors.textSecondary)
            if (state.capabilities.api >= 26 && !state.capabilities.overlayAvailable)
                TextButton(onClick = onOverlay) { Text("允许悬浮显示") }
            if (state.capabilities.api >= 33 && !state.capabilities.notificationGranted)
                TextButton(onClick = onNotification) { Text("允许通知") }
            TextButton(onClick = onChannelSettings) { Text("打开通知设置") }
        }
        if (state.activeSession) Text("修改将在下一次学习时生效", style = MaterialTheme.typography.bodySmall,
            color = MirraTheme.colors.textSecondary)
        state.error?.let { Text(it, color = MirraTheme.colors.danger) }
    }
}
