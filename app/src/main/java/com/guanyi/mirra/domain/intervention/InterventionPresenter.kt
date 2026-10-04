package com.guanyi.mirra.domain.intervention

import com.guanyi.mirra.domain.monitoring.InterventionUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class InterventionDeliveryReceipt { IN_APP_PRESENTED, OVERLAY_ATTACHED, NOTIFICATION_POSTED, UNAVAILABLE }
enum class PresentationChannel { NONE, OVERLAY, NOTIFICATION }
data class DeliveryCapabilities(val api: Int, val overlayGranted: Boolean,
    val notificationGranted: Boolean, val notificationChannelEnabled: Boolean) {
    val overlayAvailable get() = api >= 26 && overlayGranted
    val notificationAvailable get() = notificationGranted && notificationChannelEnabled
}
data class InterventionPresentationState(val promptToken: String? = null,
    val channel: PresentationChannel = PresentationChannel.NONE,
    val receipt: InterventionDeliveryReceipt? = null, val error: String? = null)
interface ExternalInterventionChannel {
    suspend fun show(prompt: InterventionUiModel): Boolean
    suspend fun clear()
    fun isAttached(): Boolean = true
}
interface InterventionPresenter {
    val diagnostics: StateFlow<InterventionPresentationState>
    suspend fun reconcile(prompt: InterventionUiModel?, appVisible: Boolean, sessionEnabled: Boolean)
    suspend fun dismissAll()
}

class DefaultInterventionPresenter(
    private val overlay: ExternalInterventionChannel,
    private val notification: ExternalInterventionChannel,
    private val capabilities: () -> DeliveryCapabilities,
    private val record: suspend (InterventionUiModel, InterventionDeliveryReceipt) -> Unit,
) : InterventionPresenter {
    private val mutableDiagnostics = MutableStateFlow(InterventionPresentationState())
    override val diagnostics: StateFlow<InterventionPresentationState> = mutableDiagnostics
    private val operations = Mutex()
    private val desiredLock = Any()
    private var desired: InterventionUiModel? = null
    private var generation = 0L
    private var owner: String? = null
    private var channel = PresentationChannel.NONE
    private val posted = LinkedHashSet<String>()
    private val recorded = LinkedHashSet<String>()

    override suspend fun reconcile(prompt: InterventionUiModel?, appVisible: Boolean, sessionEnabled: Boolean) {
        val next = prompt?.takeIf { !it.dismissed && !appVisible && sessionEnabled }
        val operation = synchronized(desiredLock) {
            // Publish invalidation BEFORE waiting for an in-flight platform operation.
            if (desired?.identity != next?.identity) generation++
            desired = next
            generation
        }
        // Runtime launches UNDISPATCHED only to publish invalidation; do not block its poll with Binder/UI work.
        yield()
        operations.withLock {
            if (!current(operation, next)) return@withLock
            try {
                if (next == null) { clear(); return@withLock }
                if (owner != next.identity && !clear()) return@withLock
                val caps = capabilities()
                if (channel == PresentationChannel.OVERLAY && (!caps.overlayAvailable || !overlay.isAttached()) && !clear()) return@withLock
                if (channel == PresentationChannel.NOTIFICATION && !caps.notificationAvailable && !clear()) return@withLock
                if (channel != PresentationChannel.NONE) return@withLock
                mutableDiagnostics.value = InterventionPresentationState(next.promptToken)
                if (caps.overlayAvailable && safelyShow(overlay, next)) {
                    if (!current(operation, next)) { safelyClear(overlay); return@withLock }
                    owner = next.identity; channel = PresentationChannel.OVERLAY
                    mutableDiagnostics.value = InterventionPresentationState(next.promptToken, channel,
                        InterventionDeliveryReceipt.OVERLAY_ATTACHED)
                    recordOnce(next, InterventionDeliveryReceipt.OVERLAY_ATTACHED)
                    return@withLock
                }
                if (!safelyClear(overlay)) return@withLock
                if (!current(operation, next)) return@withLock
                if (caps.notificationAvailable) {
                    // One post per episode, even after a foreground visit cancelled it.
                    if (next.identity in posted) return@withLock
                    if (safelyShow(notification, next)) {
                        if (!current(operation, next)) { safelyClear(notification); return@withLock }
                        remember(posted, next.identity)
                        owner = next.identity; channel = PresentationChannel.NOTIFICATION
                        mutableDiagnostics.value = InterventionPresentationState(next.promptToken, channel,
                            InterventionDeliveryReceipt.NOTIFICATION_POSTED)
                        return@withLock // Never write INTERVENTION_SHOWN for notify().
                    }
                }
                safelyClear(notification)
                if (current(operation, next)) {
                    mutableDiagnostics.value = mutableDiagnostics.value.copy(receipt = InterventionDeliveryReceipt.UNAVAILABLE)
                    recordOnce(next, InterventionDeliveryReceipt.UNAVAILABLE)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { clear() }
                throw cancelled
            }
        }
    }

    override suspend fun dismissAll() = reconcile(null, appVisible = true, sessionEnabled = false)

    private val InterventionUiModel.identity get() = "$sessionId:$promptToken"
    private fun current(operation: Long, prompt: InterventionUiModel?) = synchronized(desiredLock) {
        generation == operation && desired?.identity == prompt?.identity
    }
    private suspend fun safelyShow(port: ExternalInterventionChannel, prompt: InterventionUiModel): Boolean = try {
        port.show(prompt)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RuntimeException) {
        mutableDiagnostics.value = mutableDiagnostics.value.copy(error = failure.javaClass.simpleName); false
    }
    private suspend fun safelyClear(port: ExternalInterventionChannel): Boolean {
        try { port.clear(); return true } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: RuntimeException) {
            mutableDiagnostics.value = mutableDiagnostics.value.copy(error = failure.javaClass.simpleName)
            return false
        }
    }
    private suspend fun clear(): Boolean {
        val overlayRemoved = safelyClear(overlay)
        val notificationRemoved = safelyClear(notification)
        if (!overlayRemoved || !notificationRemoved) return false
        owner = null; channel = PresentationChannel.NONE
        mutableDiagnostics.value = InterventionPresentationState()
        return true
    }
    private suspend fun recordOnce(prompt: InterventionUiModel, receipt: InterventionDeliveryReceipt) {
        val key = "${prompt.identity}:$receipt"
        if (key in recorded) return
        try { record(prompt, receipt); remember(recorded, key) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: RuntimeException) {
            mutableDiagnostics.value = mutableDiagnostics.value.copy(error = "receipt: ${failure.javaClass.simpleName}")
        }
    }
    private fun remember(set: LinkedHashSet<String>, key: String) {
        set += key
        if (set.size > 128) set.remove(set.first())
    }
}
