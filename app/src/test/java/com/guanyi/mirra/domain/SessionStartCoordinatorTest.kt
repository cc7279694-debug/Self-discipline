package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.repository.MonitoredSessionStartResult
import com.guanyi.mirra.platform.focus.MonitoringReadyLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStartCoordinatorTest {
    @Test fun `wrist revoked control admission cannot start monitoring or a session`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor()
        try {
            SessionStartCoordinator(store, monitor).startConfirmedMonitored(confirmation(), stillAuthorized = { false })
            fail("Expected revoked control admission")
        } catch (_: StalePreparationException) { }
        assertNull(store.existing)
        assertEquals(0, monitor.startCalls)
    }

    @Test fun `wrist control revoked while waiting for ready cannot commit a session`() = runTest {
        val store = FakeStore()
        var authorized = true
        val monitor = FakeMonitor().apply { whenReady = { authorized = false } }
        try {
            SessionStartCoordinator(store, monitor).startConfirmedMonitored(confirmation(), stillAuthorized = { authorized })
            fail("Expected revoked control admission after READY")
        } catch (_: StalePreparationException) { }
        assertNull(store.existing)
        assertEquals(0, store.monitoredStarts)
        assertEquals(0, store.unmonitoredStarts)
        assertEquals(1, monitor.stopCalls)
    }
    @Test fun `wrist denied monitoring leaves intent and creates no unmonitored session`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor().apply { available = false }
        assertRequiresPhone { SessionStartCoordinator(store, monitor).startConfirmedMonitored(confirmation()) }
        assertNull(store.existing)
        assertEquals(0, store.monitoredStarts)
        assertEquals(0, store.unmonitoredStarts)
        assertEquals(0, monitor.startCalls)
    }

    @Test fun `wrist background FGS rejection never falls back to a session`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor().apply { startFailure = true }
        assertRequiresPhone { SessionStartCoordinator(store, monitor).startConfirmedMonitored(confirmation()) }
        assertNull(store.existing)
        assertEquals(0, store.monitoredStarts)
        assertEquals(0, store.unmonitoredStarts)
    }

    @Test fun `wrist ready timeout stops unbound monitor without creating session`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor().apply { ready = null }
        assertRequiresPhone { SessionStartCoordinator(store, monitor).startConfirmedMonitored(confirmation()) }
        assertNull(store.existing)
        assertEquals(0, store.unmonitoredStarts)
        assertEquals(0, store.monitoredStarts)
        assertEquals(1, monitor.stopCalls)
    }

    @Test fun `wrist stale or foreign ready lease cannot enter transaction`() = runTest {
        for (foreign in listOf(false, true)) {
            val store = FakeStore()
            val monitor = FakeMonitor().apply {
                if (foreign) ready = ready?.copy(generation = "other") else valid = false
            }
            assertRequiresPhone { SessionStartCoordinator(store, monitor).startConfirmedMonitored(confirmation()) }
            assertNull(store.existing)
            assertEquals(0, store.unmonitoredStarts)
            assertEquals(0, store.monitoredStarts)
            assertEquals(1, monitor.stopCalls)
        }
    }

    @Test fun `wrist stale preparation is rejected before foreground service start`() = runTest {
        val store = FakeStore().apply { stalePreparation = true }
        val monitor = FakeMonitor()
        try {
            SessionStartCoordinator(store, monitor).startConfirmedMonitored(confirmation())
            fail("Expected stale preparation")
        } catch (_: StalePreparationException) { }
        assertNull(store.existing)
        assertEquals(0, monitor.startCalls)
        assertEquals(0, store.unmonitoredStarts)
    }

    @Test fun `wrist confirmation is forwarded intact to monitored transaction`() = runTest {
        val store = FakeStore()
        val expected = confirmation()
        val result = SessionStartCoordinator(store, FakeMonitor()).startConfirmedMonitored(expected)
        assertEquals(StartMonitoringResult.MONITORED, result.monitoring)
        assertEquals(expected, store.validatedPreparation)
        assertEquals(expected, store.committedPreparation)
        assertEquals(0, store.unmonitoredStarts)
    }

    @Test fun `wrist committed binding failure returns real degraded session`() = runTest {
        val store = FakeStore()
        val result = SessionStartCoordinator(store, FakeMonitor().apply { bindResult = false })
            .startConfirmedMonitored(confirmation())
        assertEquals(StartMonitoringResult.DEGRADED, result.monitoring)
        assertEquals(store.existing?.id, result.session.id)
        assertEquals(1, store.lossCalls)
        assertEquals(0, store.unmonitoredStarts)
    }

    @Test fun `wrist committed verification exception settles real monitoring degradation`() = runTest {
        val store = FakeStore()
        val result = SessionStartCoordinator(store, FakeMonitor().apply { postCommitFailure = true })
            .startConfirmedMonitored(confirmation())
        assertEquals(StartMonitoringResult.DEGRADED, result.monitoring)
        assertEquals(store.existing?.id, result.session.id)
        assertEquals(1, store.lossCalls)
        assertEquals(0, store.unmonitoredStarts)
    }

    @Test fun `cancelled wrist committed start settles loss even when verification throws`() = runTest {
        val store = FakeStore().apply { afterCommitGate = CompletableDeferred() }
        val monitor = FakeMonitor().apply { postCommitFailure = true }
        val coordinator = SessionStartCoordinator(store, monitor)
        val attempt = async { coordinator.startConfirmedMonitored(confirmation()) }
        runCurrent()
        assertNotNull(store.existing)
        attempt.cancel()
        runCurrent()
        assertTrue(attempt.isCancelled)
        assertEquals(1, store.lossCalls)
        assertEquals(1, monitor.stopCalls)
        assertEquals(0, store.unmonitoredStarts)
        assertEquals(CoordinatorPhase.IDLE, coordinator.state.value.phase)
    }

    @Test fun `simultaneous wrist confirmations cannot create a second session`() = runTest {
        val store = FakeStore().apply { beforeCommitGate = CompletableDeferred() }
        val monitor = FakeMonitor()
        val coordinator = SessionStartCoordinator(store, monitor)
        val first = async { coordinator.startConfirmedMonitored(confirmation()) }
        val second = async { coordinator.startConfirmedMonitored(confirmation()) }
        runCurrent()
        store.beforeCommitGate?.complete(Unit)
        runCurrent()
        assertEquals(first.await().session.id, second.await().session.id)
        assertEquals(1, store.monitoredStarts)
        assertEquals(1, monitor.startCalls)
        assertEquals(0, store.unmonitoredStarts)
    }

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

    @Test fun `slow post start side effect cannot expire a healthy monitoring lease`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor()
        var callbackSawBinding = false
        val coordinator = SessionStartCoordinator(store, monitor, onSessionCreated = { session ->
            delay(6_000)
            callbackSawBinding = monitor.boundSession == session.id
            monitor.valid = false // Models a lease that would be stale if checked after this delay.
        })
        val result = coordinator.start("intent", 10)
        assertEquals(StartMonitoringResult.MONITORED, result.monitoring)
        assertEquals(result.session.id, monitor.boundSession)
        assertTrue(callbackSawBinding)
        assertEquals(0, store.lossCalls)
    }

    @Test fun `post start side effect failure cannot degrade an established binding`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor()
        val result = SessionStartCoordinator(store, monitor, onSessionCreated = {
            throw SecurityException("DND denied")
        }).start("intent", 10)
        assertEquals(StartMonitoringResult.MONITORED, result.monitoring)
        assertEquals(result.session.id, monitor.boundSession)
        assertEquals(0, store.lossCalls)
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

    @Test fun `unmonitored fallback runs post start side effect after session creation`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor().apply { available = false }
        var callbackSession: String? = null
        val result = SessionStartCoordinator(store, monitor, onSessionCreated = { session ->
            assertEquals(1, store.unmonitoredStarts)
            callbackSession = session.id
        }).start("intent", 10)
        assertEquals(StartMonitoringResult.UNMONITORED, result.monitoring)
        assertEquals(result.session.id, callbackSession)
        assertNull(monitor.boundSession)
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
        var callbackSawSettledMonitoring = false
        val result = SessionStartCoordinator(store, monitor, onSessionCreated = {
            callbackSawSettledMonitoring = store.lossCalls == 1 && monitor.stopCalls == 1
        }).start("intent", 10)
        assertEquals(StartMonitoringResult.DEGRADED, result.monitoring)
        assertEquals(result.session.id, store.existing?.id)
        assertEquals(1, store.lossCalls)
        assertEquals(1_005_000L, store.lastTrustedAt)
        assertEquals(0, store.unmonitoredStarts)
        assertTrue(callbackSawSettledMonitoring)
    }

    @Test fun `cancellation during committing checks Room before stopping monitor`() = runTest {
        val store = FakeStore()
        val monitor = FakeMonitor()
        val gate = CompletableDeferred<Unit>()
        store.afterCommitGate = gate
        var callbackCalls = 0
        var callbackSawBinding = false
        val coordinator = SessionStartCoordinator(store, monitor, onSessionCreated = { session ->
            callbackCalls++
            callbackSawBinding = monitor.boundSession == session.id
        })
        val attempt = async { coordinator.start("intent", 10) }
        runCurrent()
        assertEquals(CoordinatorPhase.COMMITTING, coordinator.state.value.phase)
        attempt.cancel()
        runCurrent()
        assertEquals(store.existing?.id, monitor.boundSession)
        assertEquals(0, monitor.stopCalls)
        assertEquals(store.existing?.id, monitor.boundSession)
        assertEquals(1, callbackCalls)
        assertTrue(callbackSawBinding)
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

    @Test fun `cancelled committed start settles degradation before post start side effect`() = runTest {
        val store = FakeStore().apply { afterCommitGate = CompletableDeferred() }
        val monitor = FakeMonitor().apply { bindResult = false }
        var callbackSawSettlement = false
        val coordinator = SessionStartCoordinator(store, monitor, onSessionCreated = {
            callbackSawSettlement = store.lossCalls == 1 && monitor.stopCalls == 1
        })
        val attempt = async { coordinator.start("intent", 10) }
        runCurrent()
        attempt.cancel()
        runCurrent()
        assertTrue(attempt.isCancelled)
        assertEquals(1, store.lossCalls)
        assertTrue(callbackSawSettlement)
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
        var stalePreparation = false
        var validatedPreparation: ConfirmedSessionStart? = null
        var committedPreparation: ConfirmedSessionStart? = null
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
        override suspend fun validateConfirmedPreparation(expected: ConfirmedSessionStart) {
            if (stalePreparation) throw StalePreparationException("第一步已变化")
            validatedPreparation = expected
        }
        override suspend fun startConfirmedMonitored(
            expected: ConfirmedSessionStart, lease: MonitoringReadyLease, proposedSessionId: String,
            stillAuthorized: () -> Boolean,
        ): MonitoredSessionStartResult {
            committedPreparation = expected
            return startMonitored(expected.intentId, expected.startPage, lease, proposedSessionId)
        }
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
        var postCommitFailure = false
        var preflightGate: CompletableDeferred<Unit>? = null
        var readyGate: CompletableDeferred<Unit>? = null
        var whenReady: () -> Unit = {}
        override suspend fun preflight(): Boolean { preflightGate?.await(); return available }
        override fun start(): String { startCalls++; if (startFailure) error("FGS start denied"); return "generation" }
        override suspend fun awaitReady(generation: String, timeoutMillis: Long): MonitoringReadyLease? {
            readyGate?.await()
            whenReady()
            return ready
        }
        override fun verify(lease: MonitoringReadyLease) = valid
        override suspend fun verifyAfterCommit(lease: MonitoringReadyLease): Boolean {
            if (postCommitFailure) error("Monitor verification unavailable after commit")
            return valid
        }
        override fun bind(sessionId: String, generation: String): Boolean {
            if (bindResult) boundSession = sessionId
            return bindResult
        }
        override fun stopUnbound(generation: String) { if (boundSession == null) stopCalls++ }
        override fun nowWall() = 1_005_100L
    }

    private fun confirmation() = ConfirmedSessionStart("intent", "item", 10, "翻到第 10 页，读第一段。")

    private suspend fun assertRequiresPhone(action: suspend () -> Unit) {
        try { action(); fail("Wrist start must stop before any session commit") }
        catch (_: SessionStartRequiresPhoneException) { }
    }
}
