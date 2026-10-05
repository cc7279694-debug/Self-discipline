package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.local.model.CloseoutSnapshot
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.domain.monitoring.ClockSample
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SessionManagerCloseoutTest {
    private class Workflow : StudyWorkflowRepository by unsupported() {
        var state = FocusCloseoutState.ACTIVE
        var snapshot: CloseoutSnapshot? = null
        var failBegin = false
        var failComplete = false
        var cancelAt: String? = null
        var begins = 0
        var completes = 0
        val events = mutableListOf<String>()
        val original = StudySessionEntity("s", "book", "intent", 1_000, null, null, 10, 10, null, null, null, 1)
        override suspend fun getCloseoutState(sessionId: String) = state
        override suspend fun beginCloseout(sessionId: String, requestedEndPage: Int, closeoutStartedAt: Long,
            backwardClockEvidence: com.guanyi.mirra.domain.monitoring.BackwardClockCloseoutEvidence?): CloseoutSnapshot {
            begins++; events += "A"
            if (cancelAt == "beforeA") throw CancellationException("before decision")
            if (failBegin) error("A failed")
            if (state == FocusCloseoutState.ACTIVE) { snapshot = CloseoutSnapshot(sessionId, closeoutStartedAt, requestedEndPage); state = FocusCloseoutState.PENDING }
            if (cancelAt == "afterA") throw CancellationException("commit returned cancellation")
            return snapshot!!
        }
        override suspend fun completeCloseout(sessionId: String): StudySessionEntity {
            completes++; events += "B"
            if (failComplete) error("B failed")
            check(state != FocusCloseoutState.ACTIVE)
            state = FocusCloseoutState.COMPLETED
            if (cancelAt == "afterB") throw CancellationException("completed return cancelled")
            return original.copy(endedAt = snapshot!!.closeoutStartedAt, endPage = snapshot!!.requestedEndPage,
                endType = SessionEndType.NORMAL, activeSlot = null)
        }
    }
    @Test fun finalSampleIsTheOnlyBoundaryAndInvalidationIsBetweenTransactions() = runTest {
        val w = Workflow()
        val manager = DefaultSessionManager(w, closeoutWithMonitoringFacts = { _, _, block -> block(null, { w.events += "invalidate" }) },
            cleanupClosedSession = { w.events += "cleanup" })
        val result = manager.finish("s", 20, ClockSample(2_000, 99)) as SessionFinishResult.Completed
        assertEquals(2_000L, result.session.endedAt)
        assertEquals(listOf("A", "invalidate", "B", "cleanup"), w.events)
    }
    @Test fun beginFailureKeepsReadingAndDoesNotCleanup() = runTest {
        val w = Workflow().apply { failBegin = true }
        var cleaned = false
        try { DefaultSessionManager(w, cleanupClosedSession = { cleaned = true }).finish("s", 20, ClockSample(2_000, 99)); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(FocusCloseoutState.ACTIVE, w.state)
        assertFalse(cleaned)
    }
    @Test fun beginCommittedThenCompleteFailsClearsPromptBeforeCleanupStarts() = runTest {
        val w = Workflow().apply { failComplete = true }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var invalidated = false
        val manager = DefaultSessionManager(w, closeoutWithMonitoringFacts = { _, _, block -> block(null, { invalidated = true }) },
            cleanupClosedSession = { assertTrue(invalidated); entered.complete(Unit); release.await() })
        val finishing = async { manager.finish("s", 20, ClockSample(2_000, 99)) }
        entered.await(); assertTrue(invalidated); assertEquals(FocusCloseoutState.PENDING, w.state)
        release.complete(Unit)
        assertEquals(SessionFinishResult.PendingRetry("s"), finishing.await())
        w.failComplete = false
        val retried = manager.retryPendingFinish("s") as SessionFinishResult.Completed
        assertEquals(2_000L, retried.session.endedAt)
        assertEquals(1, w.begins)
    }
    @Test fun doubleFinalConfirmUsesOneBoundary() = runTest {
        val w = Workflow(); val manager = DefaultSessionManager(w)
        val first = manager.finish("s", 20, ClockSample(2_000, 99))
        assertEquals(first, manager.finish("s", 90, ClockSample(999_000, 999)))
        assertEquals(2_000L, w.snapshot!!.closeoutStartedAt)
        assertEquals(20, w.snapshot!!.requestedEndPage)
    }
    @Test fun cancellationBeforeBeginCommitLeavesReadingActive() = runTest { cancellation("beforeA", false) }
    @Test fun cancellationAfterUnknownBeginCommitStillCleansPending() = runTest { cancellation("afterA", true) }
    @Test fun cancellationAfterCompletePreservesOriginalNormalResult() = runTest { cancellation("afterB", true) }
    @Test fun cleanupFailureDoesNotSkipMonitorAndDndRelease() = runTest {
        val calls = mutableListOf<String>()
        cleanupCloseoutSteps({ calls += "channels"; error("channel failed") }, { calls += "monitor" }, { calls += "dnd" })
        assertEquals(listOf("channels", "monitor", "dnd"), calls)
    }
    private suspend fun cancellation(stage: String, shouldClean: Boolean) {
        val w = Workflow().apply { cancelAt = stage }; var cleaned = false
        try { DefaultSessionManager(w, cleanupClosedSession = { cleaned = true }).finish("s", 20, ClockSample(2_000, 99)); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
        assertEquals(shouldClean, cleaned)
        if (shouldClean) assertEquals(2_000L, w.snapshot!!.closeoutStartedAt)
        assertEquals(if (stage == "beforeA") FocusCloseoutState.ACTIVE else if (stage == "afterA") FocusCloseoutState.PENDING else FocusCloseoutState.COMPLETED, w.state)
    }
    companion object {
        @Suppress("UNCHECKED_CAST") private fun unsupported(): StudyWorkflowRepository = Proxy.newProxyInstance(
            StudyWorkflowRepository::class.java.classLoader, arrayOf(StudyWorkflowRepository::class.java),
        ) { _, method, _ -> error("Unexpected legacy call: ${method.name}") } as StudyWorkflowRepository
    }
}
