package com.guanyi.mirra.platform.intervention

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.guanyi.mirra.data.repository.InterventionReceiptRepository
import com.guanyi.mirra.domain.intervention.*
import com.guanyi.mirra.domain.monitoring.ClockSample
import com.guanyi.mirra.domain.monitoring.InterventionUiModel
import com.guanyi.mirra.platform.focus.MonitoringPlatformRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Presentation lifetime only. Consumes the existing facts; never queries UsageStats. */
class RuntimeInterventionChannels(private val context: Context, private val runtime: MonitoringPlatformRuntime,
    private val receipts: InterventionReceiptRepository, private val titleProvider: suspend (String) -> String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshot = SessionInterventionSnapshot()
    private val releaseEpoch = AtomicLong()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableVisible = MutableStateFlow(false)
    val appVisible = mutableVisible.asStateFlow()
    @Volatile private var title = "本次学习"
    val notification = NotificationInterventionPresenter(context) { title }
    private val overlay = OverlayInterventionPresenter(context, { title }, { prompt ->
        safelyLaunch {
            val current = currentPresentationEpisode(prompt, runtime.focusSessionActions.intervention.value)
            if (current != null && isCurrent(current) && receipts.isEligible(current))
                runtime.focusSessionActions.dismissPrompt(current.sessionId, current.promptToken, clock())
            reconcile()
        }
    }, { request ->
        try { InterventionActivityIntents.sendFromOverlay(context, request) }
        catch (failure: RuntimeException) { mutableError.value = "navigation: ${failure.javaClass.simpleName}" }
        catch (failure: android.app.PendingIntent.CanceledException) { mutableError.value = "navigation: CanceledException" }
    })
    val presenter = DefaultInterventionPresenter(overlay, notification, notification::capabilities) { prompt, receipt ->
        currentPresentationEpisode(prompt, runtime.focusSessionActions.intervention.value)?.let { receipts.record(it, receipt) }
    }
    val navigation = InterventionNavigationController { request ->
        val prompt = runtime.focusSessionActions.intervention.value
        prompt != null && prompt.sessionId == request.sessionId && prompt.promptToken == request.promptToken &&
            isCurrent(prompt) && receipts.isEligible(prompt)
    }

    init {
        scope.launch { runtime.focusSessionActions.intervention.collect { reconcile() } }
    }
    fun capabilities() = notification.capabilities()
    fun snapshotEnabled() = snapshot.enabledFor(snapshot.sessionId)
    suspend fun configure(sessionId: String, enabled: Boolean) {
        // Called after COMMITTED and bind/degradation settlement, not in a Room transaction.
        val epoch = releaseEpoch.get()
        val capturedTitle = runCatching { titleProvider(sessionId) }.getOrDefault("本次学习")
        if (epoch != releaseEpoch.get()) return // A late metadata read must not revive a released snapshot.
        title = capturedTitle
        snapshot.capture(sessionId, enabled)
        reconcile()
    }
    fun setAppVisible(visible: Boolean) { mutableVisible.value = visible; reconcile() }
    fun isCurrent(prompt: InterventionUiModel): Boolean {
        val current = runtime.focusSessionActions.intervention.value
        return current != null && !current.dismissed && current.sessionId == prompt.sessionId &&
            current.promptToken == prompt.promptToken && current.segmentId == prompt.segmentId &&
            runtime.binding?.sessionId == prompt.sessionId && runtime.capabilities.state.value.monitor.running
    }
    fun reconcile() {
        // UNDISPATCHED publishes coordinator invalidation before a slow queued add/post can finish.
        // Platform operations immediately suspend to Main/IO; the monitor never awaits them.
        safelyLaunch(start = CoroutineStart.UNDISPATCHED) {
            val prompt = runtime.focusSessionActions.intervention.value?.takeIf(::isCurrent)
            val monitor = runtime.capabilities.state.value.monitor
            val externalEligible = externalPromptEligible(prompt, monitor, SystemClock.elapsedRealtime(),
                context.getSystemService(PowerManager::class.java).isInteractive,
                context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            presenter.reconcile(prompt.takeIf { externalEligible }, appVisible.value,
                snapshot.enabledFor(prompt?.sessionId))
        }
    }
    fun release(sessionId: String) { releaseEpoch.incrementAndGet(); snapshot.clear(sessionId); reconcile() }
    fun serviceStopped() { releaseEpoch.incrementAndGet(); snapshot.clear(); reconcile() }
    suspend fun startupCleanup() { releaseEpoch.incrementAndGet(); snapshot.clear(); presenter.dismissAll() }
    fun inAppComposed(prompt: InterventionUiModel) {
        safelyLaunch { if (appVisible.value && isCurrent(prompt)) receipts.record(prompt, InterventionDeliveryReceipt.IN_APP_PRESENTED) }
    }
    private fun safelyLaunch(start: CoroutineStart = CoroutineStart.DEFAULT, block: suspend () -> Unit) {
        scope.launch(start = start) {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: RuntimeException) { mutableError.value = failure.javaClass.simpleName }
        }
    }
    private fun clock() = ClockSample(System.currentTimeMillis(), SystemClock.elapsedRealtime())
}
