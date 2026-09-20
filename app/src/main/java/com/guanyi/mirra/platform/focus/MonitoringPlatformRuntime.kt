package com.guanyi.mirra.platform.focus

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID

/** Process-local platform facts. No Session, Room, or risk state is owned here. */
class MonitoringPlatformRuntime(private val context: Context) {
    val lifecycle = MonitoringLifecycle()
    val capabilities = MonitoringCapabilityManager()
    val notificationGateway = AndroidNotificationGateway(context)
    private val usageGateway = AndroidUsageAccessGateway(context)
    private val dndGateway = AndroidDndGateway(context)

    fun refreshCapabilities() {
        capabilities.updateUsage(UsageAccessCapability(usageGateway).check())
        capabilities.updateNotification(NotificationCapability(notificationGateway).check())
        capabilities.updateDnd(DndCapability(dndGateway).check())
        capabilities.updateService(lifecycle.state.value)
    }

    fun usageSettingsIntent(): Intent = usageGateway.settingsIntent()
    fun dndSettingsIntent(): Intent = dndGateway.settingsIntent()

    /** Called only from a visible, explicit debug action in Task 2. */
    fun startFromUserAction(): String? {
        val state = lifecycle.state.value
        if (state.phase != ServicePhase.STOPPED) return null
        val generation = UUID.randomUUID().toString()
        val intent = FocusMonitoringService.startIntent(context, generation)
        ContextCompat.startForegroundService(context, intent)
        return generation
    }

    fun stopFromUserAction() {
        val generation = lifecycle.state.value.generation ?: return
        context.startService(FocusMonitoringService.stopIntent(context, generation))
    }

    fun createMonitor(): UsageMonitor = UsageMonitor(AndroidUsageEventSource(
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager,
    ))
}
