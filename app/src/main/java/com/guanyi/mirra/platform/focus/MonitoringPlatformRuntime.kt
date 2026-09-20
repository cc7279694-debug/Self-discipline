package com.guanyi.mirra.platform.focus

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.guanyi.mirra.domain.MonitoredStartPort
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class MonitoringBinding(val sessionId: String, val generation: String, val boundAtElapsed: Long)

/** Process-local platform facts. No Session, Room, or risk state is owned here. */
class MonitoringPlatformRuntime(private val context: Context) : MonitoredStartPort {
    val lifecycle = MonitoringLifecycle()
    val capabilities = MonitoringCapabilityManager()
    val notificationGateway = AndroidNotificationGateway(context)
    private val usageGateway = AndroidUsageAccessGateway(context)
    private val dndGateway = AndroidDndGateway(context)
    private val leasePolicy = ReadyLeasePolicy()
    @Volatile private var requestedGeneration: String? = null
    @Volatile var binding: MonitoringBinding? = null
        private set

    fun refreshCapabilities() {
        capabilities.updateUsage(UsageAccessCapability(usageGateway).check())
        capabilities.updateNotification(NotificationCapability(notificationGateway).check())
        capabilities.updateDnd(DndCapability(dndGateway).check())
        capabilities.updateService(lifecycle.state.value)
    }

    fun recheckUsageWithoutQuery() {
        capabilities.updateUsage(UsageAccessCapability(usageGateway).checkLightweight())
    }

    fun usageSettingsIntent(): Intent = usageGateway.settingsIntent()
    fun dndSettingsIntent(): Intent = dndGateway.settingsIntent()

    /** Called only after a visible user action, including the Preparation start handshake. */
    @Synchronized fun startFromUserAction(): String? {
        val state = lifecycle.state.value
        if (state.phase != ServicePhase.STOPPED || requestedGeneration != null) return null
        val generation = UUID.randomUUID().toString()
        requestedGeneration = generation
        val intent = FocusMonitoringService.startIntent(context, generation)
        try { ContextCompat.startForegroundService(context, intent) }
        catch (failure: RuntimeException) {
            requestedGeneration = null
            throw failure
        }
        return generation
    }

    fun stopFromUserAction() {
        val generation = lifecycle.state.value.generation ?: return
        stopUnbound(generation)
    }

    @Synchronized fun acceptsGeneration(generation: String): Boolean = requestedGeneration == generation

    @Synchronized fun onServiceDestroyed(generation: String?) {
        if (requestedGeneration == generation) requestedGeneration = null
        if (binding?.generation == generation) binding = null
    }

    override suspend fun preflight(): Boolean = withContext(Dispatchers.IO) {
        refreshCapabilities()
        capabilities.state.value.usage == CapabilityStatus.AVAILABLE
    }

    override fun start(): String = startFromUserAction() ?: error("已有监测正在运行")

    override suspend fun awaitReady(generation: String, timeoutMillis: Long): MonitoringReadyLease? {
        val ready = withTimeoutOrNull(timeoutMillis) {
            capabilities.state.first {
                (it.service.generation == generation && it.monitoringReady) ||
                    (it.service.generation == generation && it.service.phase == ServicePhase.FAILED)
            }
        } ?: return null
        return leasePolicy.capture(ready, SystemClock.elapsedRealtime())?.takeIf { it.generation == generation }
    }

    override fun verify(lease: MonitoringReadyLease): Boolean =
        requestedGeneration == lease.generation &&
            leasePolicy.isValid(lease, capabilities.state.value, SystemClock.elapsedRealtime())

    override suspend fun verifyAfterCommit(lease: MonitoringReadyLease): Boolean {
        val subsequent = withTimeoutOrNull(2_500) {
            capabilities.state.first {
                it.service.generation != lease.generation ||
                    it.service.phase != ServicePhase.MONITOR_READY ||
                    it.monitor.continuityEpoch != lease.continuityEpoch ||
                    it.monitor.queryGeneration > lease.successfulQueryGeneration
            }
        } ?: return false
        return subsequent.monitor.queryGeneration > lease.successfulQueryGeneration && verify(lease)
    }

    @Synchronized override fun bind(sessionId: String, generation: String): Boolean {
        if (requestedGeneration != generation || !capabilities.state.value.monitoringReady ||
            capabilities.state.value.service.generation != generation) return false
        val current = binding
        if (current != null) return current.sessionId == sessionId && current.generation == generation
        binding = MonitoringBinding(sessionId, generation, SystemClock.elapsedRealtime())
        return true
    }

    @Synchronized override fun stopUnbound(generation: String) {
        if (binding?.generation == generation || requestedGeneration != generation) return
        requestedGeneration = null
        context.stopService(Intent(context, FocusMonitoringService::class.java))
    }

    @Synchronized fun releaseSession(sessionId: String) {
        val current = binding?.takeIf { it.sessionId == sessionId } ?: return
        binding = null
        if (requestedGeneration == current.generation) requestedGeneration = null
        runCatching { context.stopService(Intent(context, FocusMonitoringService::class.java)) }
            .onFailure {
                lifecycle.failed(current.generation, "monitor stop failed: ${it.javaClass.simpleName}")
                capabilities.updateService(lifecycle.state.value)
            }
    }

    override fun nowWall(): Long = System.currentTimeMillis()

    fun createMonitor(): UsageMonitor = UsageMonitor(AndroidUsageEventSource(
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager,
    ))
}
