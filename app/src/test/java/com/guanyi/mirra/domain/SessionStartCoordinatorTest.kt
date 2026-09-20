package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.repository.MonitoredSessionStartResult
import com.guanyi.mirra.platform.focus.MonitoringReadyLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStartCoordinatorTest {
    @Test fun `ready evidence commits one monitored session and binds its generation`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor()
        val coordinator = SessionStartCoordinator(store, monitor)
        val result = coordinator.start("intent", 10)
        assertEquals(StartMonitoringResult.MONITORED, result.monitoring)
        assertEquals(1_005_000L, result.session.startedAt)
        assertEquals(1, store.monitoredStarts)
        assertEquals(0, store.unmonitoredStarts)
        assertEquals(result.session.id, monitor.boundSession)
        assertEquals(CoordinatorPhase.BOUND, coordinator.state.value.phase)
    }

    @Test fun `missing access or ready timeout uses only conservative unmonitored branch`() = runTest {
        val deniedStore = FakeStore()
        val deniedMonitor = FakeMonitor().apply { available = false }
        val denied = SessionStartCoordinator(deniedStore, deniedMonitor).start("intent", 10)
        assertEquals(StartMonitoringResult.UNMONITORED, denied.monitoring)
        assertEquals(0, deniedStore.monitoredStarts)
        assertEquals(1, deniedStore.unmonitoredStarts)
        assertEquals(0, deniedMonitor.startCalls)

        val timeoutStore = FakeStore()
        val timeoutMonitor = FakeMonitor().apply { ready = null }
        val timeout = SessionStartCoordinator(timeoutStore, timeoutMonitor).start("intent", 10)
        assertEquals(StartMonitoringResult.UNMONITORED, timeout.monitoring)
        assertEquals(1, timeoutMonitor.stopCalls)
        assertEquals(0, timeoutStore.monitoredStarts)
    }

    @Test fun `expired lease and changed generation cannot enter monitored transaction`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor().apply { valid = false }
        val result = SessionStartCoordinator(store, monitor).start("intent", 10)
        assertEquals(StartMonitoringResult.UNMONITORED, result.monitoring)
        assertEquals(0, store.monitoredStarts)
        assertEquals(1, monitor.stopCalls)

        val changed = FakeMonitor().apply { ready = ready?.copy(generation = "other") }
        val changedStore = FakeStore()
        assertEquals(StartMonitoringResult.UNMONITORED,
            SessionStartCoordinator(changedStore, changed).start("intent", 10).monitoring)
        assertEquals(0, changedStore.monitoredStarts)
    }

    @Test fun `foreground service start failure falls back without a monitored transaction`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor().apply { startFailure = true }
        assertEquals(StartMonitoringResult.UNMONITORED,
            SessionStartCoordinator(store, monitor).start("intent", 10).monitoring)
        assertEquals(0, store.monitoredStarts)
        assertEquals(1, store.unmonitoredStarts)
    }

    @Test fun `cancel while preparing leaves no service or session`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor().apply { preflightGate = CompletableDeferred() }
        val coordinator = SessionStartCoordinator(store, monitor)
        val attempt = async { coordinator.start("intent", 10) }
        runCurrent()
        assertEquals(CoordinatorPhase.PREPARING, coordinator.state.value.phase)
        attempt.cancel()
        runCurrent()
        assertEquals(0, monitor.startCalls)
        assertNull(store.existing)
        assertEquals(CoordinatorPhase.IDLE, coordinator.state.value.phase)
    }

    @Test fun `cancel while waiting for ready stops orphan service`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor().apply { readyGate = CompletableDeferred() }
        val coordinator = SessionStartCoordinator(store, monitor)
        val attempt = async { coordinator.start("intent", 10) }
        runCurrent()
        assertEquals(CoordinatorPhase.PREPARING, coordinator.state.value.phase)
        attempt.cancel()
        runCurrent()
        assertNull(store.existing)
        assertEquals(1, monitor.stopCalls)
    }

    @Test fun `cancel at ready lease stops orphan before commit`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor()
        val coordinator = SessionStartCoordinator(store, monitor)
        val attempt = async { coordinator.start("intent", 10) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.state.collect { if (it.phase == CoordinatorPhase.READY_LEASE) attempt.cancel() }
        }
        runCurrent()
        assertTrue(attempt.isCancelled)
        assertEquals(0, store.monitoredStarts)
        assertEquals(1, monitor.stopCalls)
    }

    @Test fun `second click returns existing session without a second monitor`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor()
        val coordinator = SessionStartCoordinator(store, monitor)
        val first = coordinator.start("intent", 10)
        val second = coordinator.start("intent", 10)
        assertEquals(first.session.id, second.session.id)
        assertEquals(1, store.monitoredStarts)
        assertEquals(1, monitor.startCalls)
    }

    @Test fun `simultaneous clicks wait behind the same generation and transaction`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = FakeStore().apply { beforeCommitGate = gate }
        val monitor = FakeMonitor()
        val coordinator = SessionStartCoordinator(store, monitor)
        val first = async { coordinator.start("intent", 10) }
        val second = async { coordinator.start("intent", 10) }
        runCurrent()
        assertEquals(CoordinatorPhase.COMMITTING, coordinator.state.value.phase)
        assertEquals(1, monitor.startCalls)
        gate.complete(Unit)
        runCurrent()
        assertEquals(first.await().session.id, second.await().session.id)
        assertEquals(1, store.monitoredStarts)
        assertEquals(1, monitor.startCalls)
    }

    @Test fun `business rejection never falls back to a second session`() = runTest {
        val store = FakeStore().apply { rejectBusiness = true }
        val monitor = FakeMonitor()
        try { SessionStartCoordinator(store, monitor).start("intent", 10); fail("Expected business rejection") }
        catch (_: IllegalStateException) { }
        assertEquals(0, store.unmonitoredStarts)
        assertEquals(1, monitor.stopCalls)
    }

    @Test fun `post commit binding failure keeps session and degrades its facts`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor().apply { bindResult = false }
        val result = SessionStartCoordinator(store, monitor).start("intent", 10)
        assertEquals(StartMonitoringResult.DEGRADED, result.monitoring)
        assertEquals(result.session.id, store.existing?.id)
        assertEquals(1, store.lossCalls)
        assertEquals(1_005_000L, store.lastTrustedAt)
        assertEquals(0, store.unmonitoredStarts)
    }

    @Test fun `cancellation during committing checks Room before stopping monitor`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor()
        val gate = CompletableDeferred<Unit>()
        store.afterCommitGate = gate
        val coordinator = SessionStartCoordinator(store, monitor)
        val attempt = async { coordinator.start("intent", 10) }
        runCurrent()
        assertEquals(CoordinatorPhase.COMMITTING, coordinator.state.value.phase)
        attempt.cancel()
        runCurrent()
        assertEquals(store.existing?.id, monitor.boundSession)
        assertEquals(0, monitor.stopCalls)
        assertEquals(store.existing?.id, monitor.boundSession)
    }

    @Test fun `unknown commit cannot bind a different unmonitored session for same intent`() = runTest {
        val store = FakeStore().apply { monitoredCommitted = false }
        val monitor = FakeMonitor()
        store.afterCommitGate = CompletableDeferred()
        val coordinator = SessionStartCoordinator(store, monitor)
        val attempt = async { coordinator.start("intent", 10) }
        runCurrent()
        attempt.cancel()
        runCurrent()
        assertNull(monitor.boundSession)
        assertEquals(1, monitor.stopCalls)
    }

    @Test fun `unknown commit cannot steal a different full session with identical ready timestamp`() = runTest {
        val store = FakeStore().apply {
            afterCommitGate = CompletableDeferred()
            replaceCommittedWithOtherFull = true
        }
        val monitor = FakeMonitor()
        val coordinator = SessionStartCoordinator(store, monitor)
        val attempt = async { coordinator.start("intent", 10) }
        runCurrent()
        attempt.cancel()
        runCurrent()
        assertNull(monitor.boundSession)
        assertEquals(1, monitor.stopCalls)
    }

    @Test fun `ordinary session racing the monitored transaction is returned without false binding`() = runTest {
        val store = FakeStore().apply { raceWithOrdinarySession = true }
        val monitor = FakeMonitor()
        val result = SessionStartCoordinator(store, monitor).start("intent", 10)
        assertEquals(StartMonitoringResult.EXISTING, result.monitoring)
        assertEquals(1_005_100L, result.session.startedAt)
        assertNull(monitor.boundSession)
        assertEquals(1, monitor.stopCalls)
    }

    @Test fun `cancellation before transaction commit stops only orphan monitor`() = runTest {
        val store = FakeStore().apply { beforeCommitGate = CompletableDeferred() }
        val monitor = FakeMonitor()
        val coordinator = SessionStartCoordinator(store, monitor)
        val attempt = async { coordinator.start("intent", 10) }
        runCurrent()
        assertEquals(CoordinatorPhase.COMMITTING, coordinator.state.value.phase)
        attempt.cancel()
        runCurrent()
        assertNull(store.existing)
        assertEquals(1, monitor.stopCalls)
        assertNull(monitor.boundSession)
    }

    private class FakeStore : MonitoredStartStore {
        var existing: StudySessionEntity? = null
        var monitoredStarts = 0
        var unmonitoredStarts = 0
        var lossCalls = 0
        var lastTrustedAt: Long? = null
        var rejectBusiness = false
        var afterCommitGate: CompletableDeferred<Unit>? = null
        var beforeCommitGate: CompletableDeferred<Unit>? = null
        var monitoredCommitted = true
        var raceWithOrdinarySession = false
        var replaceCommittedWithOtherFull = false

        override suspend fun findActiveForIntent(intentId: String) = existing
        override suspend fun findCommittedMonitoredForIntent(
            intentId: String, proposedSessionId: String, lease: MonitoringReadyLease,
        ): StudySessionEntity? = existing?.takeIf {
            monitoredCommitted && it.id == proposedSessionId && it.startedAt == lease.readyAtWall
        }
        override suspend fun startUnmonitored(intentId: String, page: Int): StudySessionEntity {
            unmonitoredStarts++
            return session(1_005_100).also { existing = it }
        }
        override suspend fun startMonitored(
            intentId: String, page: Int, lease: MonitoringReadyLease, proposedSessionId: String,
        ): MonitoredSessionStartResult {
            monitoredStarts++
            if (rejectBusiness) error("Intent expired")
            if (raceWithOrdinarySession) return MonitoredSessionStartResult(session(1_005_100).also {
                existing = it
                monitoredCommitted = false
            }, created = false)
            beforeCommitGate?.await()
            val result = session(lease.readyAtWall, proposedSessionId).also { existing = it }
            if (replaceCommittedWithOtherFull) existing = result.copy(id = "other-full-session")
            afterCommitGate?.await()
            return MonitoredSessionStartResult(result, created = true)
        }
        override suspend fun markMonitoringLost(sessionId: String, lastTrustedAt: Long, detectedAt: Long) {
            lossCalls++
            this.lastTrustedAt = lastTrustedAt
        }
        private fun session(at: Long, id: String = "session") =
            StudySessionEntity(id, "item", "intent", at, null, null, 10, 10, null, null, null, 1)
    }

    private class FakeMonitor : MonitoredStartPort {
        var available = true
        var ready: MonitoringReadyLease? = MonitoringReadyLease("generation", 1_005_000, 5_000, 1, 1_005_000, 5_000, 0, false, false)
        var valid = true
        var bindResult = true
        var startCalls = 0
        var stopCalls = 0
        var boundSession: String? = null
        var startFailure = false
        var preflightGate: CompletableDeferred<Unit>? = null
        var readyGate: CompletableDeferred<Unit>? = null
        override suspend fun preflight(): Boolean { preflightGate?.await(); return available }
        override fun start(): String { startCalls++; if (startFailure) error("FGS start denied"); return "generation" }
        override suspend fun awaitReady(generation: String, timeoutMillis: Long): MonitoringReadyLease? {
            readyGate?.await()
            return ready
        }
        override fun verify(lease: MonitoringReadyLease) = valid
        override suspend fun verifyAfterCommit(lease: MonitoringReadyLease) = valid
        override fun bind(sessionId: String, generation: String): Boolean {
            if (bindResult) boundSession = sessionId
            return bindResult
        }
        override fun stopUnbound(generation: String) { if (boundSession == null) stopCalls++ }
        override fun nowWall() = 1_005_100L
    }
}
