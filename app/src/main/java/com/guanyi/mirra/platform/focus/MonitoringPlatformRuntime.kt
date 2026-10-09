package com.guanyi.mirra.platform.focus

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.guanyi.mirra.domain.MonitoredStartPort
import com.guanyi.mirra.domain.monitoring.BoundSessionMonitoringController
import com.guanyi.mirra.domain.monitoring.ClockSample
import com.guanyi.mirra.domain.monitoring.BackwardClockCloseoutEvidence
import com.guanyi.mirra.domain.monitoring.FocusSessionActions
import com.guanyi.mirra.data.repository.FocusRepository
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import com.guanyi.mirra.domain.maintenance.MaintenancePhase

data class MonitoringBinding(val sessionId: String, val generation: String, val boundAtElapsed: Long)

/** Process-local platform facts. No Session, Room, or risk state is owned here. */
class MonitoringPlatformRuntime(private val context: Context) : MonitoredStartPort {
    private val factsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var maintenance: StorageMaintenanceGate? = null
    private val serviceOwners = mutableSetOf<Job>()
    @Volatile private var retired = false
    fun attachMaintenance(gate: StorageMaintenanceGate) { check(maintenance == null); maintenance = gate }
    private suspend fun <T> registered(block: suspend () -> T): T = maintenance?.writerOperation(block) ?: block()
    internal suspend fun <T> registeredPlatformOperation(block: suspend () -> T): T = registered(block)
    @Synchronized internal fun registerServiceOwner(job: Job) {
        check(!retired) { "Service belongs to a retired resource epoch" }
        serviceOwners += job
        job.invokeOnCompletion { synchronized(this) { serviceOwners.remove(job) } }
    }
    private var factController: BoundSessionMonitoringController? = null
    var interventionChannels: com.guanyi.mirra.platform.intervention.RuntimeInterventionChannels? = null
        private set
    fun attachInterventionChannels(receipts: com.guanyi.mirra.data.repository.InterventionReceiptStore,
        titleProvider: suspend (String) -> String) {
        check(interventionChannels == null)
        interventionChannels = com.guanyi.mirra.platform.intervention.RuntimeInterventionChannels(context, this, receipts, titleProvider)
    }
    fun reconcileInterventionPresentation() { interventionChannels?.reconcile() }
    fun setAppVisible(visible: Boolean) { interventionChannels?.setAppVisible(visible) }
    val factDiagnostics get() = factController?.diagnostics
    val focusSessionActions: FocusSessionActions
        get() = checkNotNull(factController) { "Runtime facts not attached" }

    fun attachFacts(repository: FocusRepository) {
        check(factController == null) { "Runtime facts already attached" }
        factController = BoundSessionMonitoringController(repository)
    }

    suspend fun <T> closeoutWithMonitoringFacts(sessionId: String, sample: ClockSample,
        block: suspend (BackwardClockCloseoutEvidence?, () -> Unit) -> T): T =
        checkNotNull(factController) { "Runtime facts not attached" }.closeoutWithFacts(sessionId, sample, block)

    suspend fun onMonitorSample(generation: String, snapshot: MonitorSnapshot, sample: ClockSample) {
        registered { factController?.onSample(binding?.takeIf { it.generation == generation }, snapshot, sample) }
    }

    suspend fun onMonitoringDeadline(generation: String, sample: ClockSample, reason: String = "query deadline") {
        registered {
            checkNotNull(factController) { "Runtime facts not attached" }
                .onServiceLost(binding?.takeIf { it.generation == generation }, sample, reason)
            reconcileInterventionPresentation()
        }
    }
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
        // Diagnostics has no outer SessionStart permit. This monitor is also used by quiescence.
        if (retired || maintenance?.coordinator?.state?.value?.phase?.let { it != MaintenancePhase.OPEN } == true) return null
        return startRegisteredRuntime()
    }

    @Synchronized private fun startRegisteredRuntime(): String? {
        if (retired) return null
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

    @Synchronized fun stopFromUserAction() {
        val generation = lifecycle.state.value.generation ?: return
        if (binding?.generation != generation) {
            stopUnbound(generation)
            return
        }
        // Reserve the controller's facts/finish mutex before returning to the UI.
        factsScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = runCatching {
                onMonitoringDeadline(generation,
                    ClockSample(System.currentTimeMillis(), SystemClock.elapsedRealtime()), "user stopped monitoring")
            }
            if (result.isSuccess && binding?.generation == generation &&
                !context.stopService(Intent(context, FocusMonitoringService::class.java))) {
                onServiceDestroyed(generation)
            } else if (result.isFailure) {
                lifecycle.failed(generation, "monitoring loss not saved")
                capabilities.updateService(lifecycle.state.value)
            }
        }
    }

    @Synchronized fun acceptsGeneration(generation: String): Boolean = requestedGeneration == generation

    @Synchronized fun onServiceDestroyed(generation: String?) {
        val lostBinding = binding?.takeIf { it.generation == generation }
        if (lostBinding != null) factsScope.launch {
            runCatching { registered { factController?.onServiceLost(lostBinding,
                ClockSample(System.currentTimeMillis(), SystemClock.elapsedRealtime()), "service stopped") } }
        }
        if (requestedGeneration == generation) requestedGeneration = null
        if (binding?.generation == generation) binding = null
        interventionChannels?.serviceStopped()
    }

    override suspend fun preflight(): Boolean = withContext(Dispatchers.IO) {
        refreshCapabilities()
        capabilities.state.value.usage == CapabilityStatus.AVAILABLE
    }

    override fun start(): String = startRegisteredRuntime() ?: error("已有监测正在运行")

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
        interventionChannels?.release(sessionId)
        val current = binding?.takeIf { it.sessionId == sessionId } ?: return
        factController?.onNormalRelease(sessionId)
        binding = null
        if (requestedGeneration == current.generation) requestedGeneration = null
        runCatching { context.stopService(Intent(context, FocusMonitoringService::class.java)) }
            .onFailure {
                lifecycle.failed(current.generation, "monitor stop failed: ${it.javaClass.simpleName}")
                capabilities.updateService(lifecycle.state.value)
            }
    }

    override fun nowWall(): Long = System.currentTimeMillis()

    @Synchronized private fun noServiceOwner(): Boolean = requestedGeneration == null &&
        binding == null && lifecycle.state.value.phase == ServicePhase.STOPPED && serviceOwners.all { it.isCompleted }

    suspend fun assertMaintenanceQuiescent() {
        check(noServiceOwner()) { "学习监测尚未停止，请稍后重试" }
        interventionChannels?.assertMaintenanceQuiescent()
    }

    suspend fun closeMaintenanceOwners() {
        if (!retired) {
            assertMaintenanceQuiescent()
            synchronized(this) { check(noServiceOwner()); retired = true }
        }
        interventionChannels?.closeMaintenanceOwner()
        factsScope.coroutineContext[Job]?.cancelAndJoin()
    }

    fun createMonitor(): UsageMonitor = UsageMonitor(AndroidUsageEventSource(
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager,
    ))
}
