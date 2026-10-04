package com.guanyi.mirra.domain.intervention

import com.guanyi.mirra.domain.monitoring.InterventionUiModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InterventionPresenterTest {
    private val prompt = InterventionUiModel("s", "risk-1", "risk-1", "segment", "risk", 0, 0)
    private class Channel : ExternalInterventionChannel {
        val shown = mutableListOf<String>(); var clears = 0; var succeeds = true; var attached = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun show(prompt: InterventionUiModel): Boolean {
            shown += prompt.promptToken; gate?.await(); attached = succeeds; return succeeds
        }
        override suspend fun clear() { clears++; attached = false }
        override fun isAttached() = attached
    }
    private class Fixture {
        val overlay = Channel(); val notification = Channel()
        var caps = DeliveryCapabilities(37, true, true, true)
        val records = mutableListOf<Pair<String, InterventionDeliveryReceipt>>()
        val presenter = DefaultInterventionPresenter(overlay, notification, { caps },
            { p, r -> records += p.promptToken to r })
    }
    @Test fun foregroundNeverUsesExternalChannels() = runTest {
        val f = Fixture(); f.presenter.reconcile(prompt, true, true)
        assertTrue(f.overlay.shown.isEmpty()); assertTrue(f.notification.shown.isEmpty()); assertTrue(f.records.isEmpty())
    }
    @Test fun disabledSnapshotNeverUsesExternalChannels() = runTest {
        val f = Fixture(); f.presenter.reconcile(prompt, false, false)
        assertTrue(f.overlay.shown.isEmpty()); assertTrue(f.records.isEmpty())
    }
    @Test fun backgroundPrefersOverlayAndRecordsOnlyAttachment() = runTest {
        val f = Fixture(); f.presenter.reconcile(prompt, false, true)
        assertEquals(listOf("risk-1"), f.overlay.shown); assertTrue(f.notification.shown.isEmpty())
        assertEquals(PresentationChannel.OVERLAY, f.presenter.diagnostics.value.channel)
        assertEquals(listOf("risk-1" to InterventionDeliveryReceipt.OVERLAY_ATTACHED), f.records)
    }
    @Test fun failedOverlayFallsBackAndPostedIsNotShownEvent() = runTest {
        val f = Fixture(); f.overlay.succeeds = false; f.presenter.reconcile(prompt, false, true)
        assertEquals(listOf("risk-1"), f.notification.shown)
        assertEquals(InterventionDeliveryReceipt.NOTIFICATION_POSTED, f.presenter.diagnostics.value.receipt)
        assertTrue(f.records.isEmpty())
    }
    @Test fun deniedOverlayUsesNotification() = runTest {
        val f = Fixture(); f.caps = f.caps.copy(overlayGranted = false); f.presenter.reconcile(prompt, false, true)
        assertTrue(f.overlay.shown.isEmpty()); assertEquals(listOf("risk-1"), f.notification.shown)
    }
    @Test fun bothUnavailableRecordsOncePerEpisode() = runTest {
        val f = Fixture(); f.caps = f.caps.copy(overlayGranted = false, notificationGranted = false)
        repeat(3) { f.presenter.reconcile(prompt, false, true) }
        assertEquals(listOf("risk-1" to InterventionDeliveryReceipt.UNAVAILABLE), f.records)
    }
    @Test fun repeatedTokenDoesNotAttachTwice() = runTest {
        val f = Fixture(); repeat(3) { f.presenter.reconcile(prompt, false, true) }
        assertEquals(1, f.overlay.shown.size); assertEquals(1, f.records.size)
    }
    @Test fun newTokenClearsOldOwner() = runTest {
        val f = Fixture(); f.presenter.reconcile(prompt, false, true)
        f.presenter.reconcile(prompt.copy(promptToken = "risk-2", eventId = "risk-2"), false, true)
        assertTrue(f.overlay.clears > 0); assertEquals("risk-2", f.presenter.diagnostics.value.promptToken)
    }
    @Test fun dismissedPromptClearsAndCannotReappear() = runTest {
        val f = Fixture(); f.presenter.reconcile(prompt, false, true)
        f.presenter.reconcile(prompt.copy(dismissed = true), false, true)
        assertFalse(f.overlay.attached); assertEquals(PresentationChannel.NONE, f.presenter.diagnostics.value.channel)
    }
    @Test fun nullPromptAndForegroundClearAllChannels() = runTest {
        val f = Fixture(); f.presenter.reconcile(prompt, false, true); f.presenter.reconcile(prompt, true, true)
        assertFalse(f.overlay.attached); f.presenter.reconcile(null, false, true)
        assertFalse(f.notification.attached)
    }
    @Test fun lateOverlayCompletionIsRemovedWithoutReceipt() = runTest {
        val f = Fixture(); f.overlay.gate = CompletableDeferred()
        val show = async { f.presenter.reconcile(prompt, false, true) }; runCurrent()
        val dismiss = async { f.presenter.reconcile(null, false, true) }; runCurrent()
        f.overlay.gate!!.complete(Unit); show.await(); dismiss.await()
        assertFalse(f.overlay.attached); assertTrue(f.records.isEmpty())
    }
    @Test fun lateNotificationCompletionIsCancelled() = runTest {
        val f = Fixture(); f.caps = f.caps.copy(overlayGranted = false); f.notification.gate = CompletableDeferred()
        val show = async { f.presenter.reconcile(prompt, false, true) }; runCurrent()
        val clear = async { f.presenter.dismissAll() }; runCurrent()
        f.notification.gate!!.complete(Unit); show.await(); clear.await()
        assertFalse(f.notification.attached); assertNull(f.presenter.diagnostics.value.receipt)
    }
    @Test fun revokedOverlayPermissionClearsBeforeFallback() = runTest {
        val f = Fixture(); f.presenter.reconcile(prompt, false, true)
        f.caps = f.caps.copy(overlayGranted = false); f.presenter.reconcile(prompt, false, true)
        assertFalse(f.overlay.attached); assertTrue(f.notification.attached)
        assertEquals(PresentationChannel.NOTIFICATION, f.presenter.diagnostics.value.channel)
    }
    @Test fun api25NeverAttemptsLegacyOverlay() = runTest {
        val f = Fixture(); f.caps = f.caps.copy(api = 25); f.presenter.reconcile(prompt, false, true)
        assertTrue(f.overlay.shown.isEmpty()); assertEquals(1, f.notification.shown.size)
    }
    @Test fun disabledNotificationChannelIsUnavailable() = runTest {
        val f = Fixture(); f.caps = f.caps.copy(overlayGranted = false, notificationChannelEnabled = false)
        f.presenter.reconcile(prompt, false, true)
        assertTrue(f.notification.shown.isEmpty()); assertEquals(InterventionDeliveryReceipt.UNAVAILABLE, f.presenter.diagnostics.value.receipt)
    }
    @Test fun notificationIsNotRepostedAfterForegroundReturn() = runTest {
        val f = Fixture(); f.caps = f.caps.copy(overlayGranted = false)
        f.presenter.reconcile(prompt, false, true); f.presenter.reconcile(prompt, true, true)
        f.presenter.reconcile(prompt, false, true); assertEquals(1, f.notification.shown.size)
    }
    @Test fun platformFailureCannotEscapeIntoSessionState() = runTest {
        val broken = object : ExternalInterventionChannel {
            override suspend fun show(prompt: InterventionUiModel): Boolean = throw SecurityException("denied")
            override suspend fun clear() = Unit
        }
        val receipts = mutableListOf<InterventionDeliveryReceipt>()
        val p = DefaultInterventionPresenter(broken, broken, { DeliveryCapabilities(37, true, true, true) },
            { _, receipt -> receipts += receipt })
        p.reconcile(prompt, false, true)
        assertEquals(listOf(InterventionDeliveryReceipt.UNAVAILABLE), receipts)
    }
    @Test fun failedRemovalMustNotCreateSecondChannelOwner() = runTest {
        var attached = false
        val overlay = object : ExternalInterventionChannel {
            override suspend fun show(prompt: InterventionUiModel): Boolean { attached = true; return true }
            override suspend fun clear() { if (attached) throw IllegalStateException("remove failed") }
            override fun isAttached() = attached
        }
        val notification = Channel()
        var granted = true
        val p = DefaultInterventionPresenter(overlay, notification, { DeliveryCapabilities(37, granted, true, true) }, { _, _ -> })
        p.reconcile(prompt, false, true)
        granted = false
        p.reconcile(prompt, false, true)
        assertTrue(notification.shown.isEmpty())
        assertNotNull(p.diagnostics.value.error)
    }
}
