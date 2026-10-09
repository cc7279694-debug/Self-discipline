package com.guanyi.mirra.data.maintenance

import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.local.model.CloseoutSnapshot
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.domain.DefaultSessionManager
import com.guanyi.mirra.domain.SessionFinishResult
import com.guanyi.mirra.domain.SessionManager
import com.guanyi.mirra.domain.maintenance.MaintenancePhase
import com.guanyi.mirra.domain.maintenance.MaintenanceUnavailableException
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import com.guanyi.mirra.domain.monitoring.BackwardClockCloseoutEvidence
import com.guanyi.mirra.domain.monitoring.ClockSample
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GateSessionManagerTest {
    @Test fun `durable closeout cleanup remains registered until complete transaction finishes`() = runBlocking {
        val gate = StorageMaintenanceGate(leaseScope = this)
        val enteredCleanup = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val workflow = Workflow()
        val manager = GateSessionManager(DefaultSessionManager(GateStudyWorkflowRepository(workflow, gate),
            cleanupClosedSession = {
                enteredCleanup.complete(Unit)
                finishCleanup.await()
            }), gate)
        val finishing = async { manager.finish("session", 2, ClockSample(2_000, 2_000)) }
        enteredCleanup.await()
        assertEquals(FocusCloseoutState.PENDING, workflow.state)
        val snapshot = async { gate.coordinator.withExclusive(5_000) { workflow.state } }
        gate.coordinator.state.first { it.phase == MaintenancePhase.DRAINING }
        assertFalse(snapshot.isCompleted)
        finishCleanup.complete(Unit)
        val finished = finishing.await() as SessionFinishResult.Completed
        assertEquals(2_000L, finished.session.endedAt)
        assertEquals(FocusCloseoutState.COMPLETED, snapshot.await())
    }

    @Test fun `every session manager entry rejects maintenance before starting effects`() = runTest {
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val delegate = Proxy.newProxyInstance(SessionManager::class.java.classLoader,
            arrayOf(SessionManager::class.java)) { _, method, _ -> error("Effect in ${method.name}") } as SessionManager
        val manager = GateSessionManager(delegate, gate)
        val writers: List<suspend () -> Unit> = listOf(
            { manager.start("intent", 1) },
            { manager.updatePage("session", 2) },
            { manager.finish("session", 2, ClockSample(2_000, 2_000)) },
            { manager.retryPendingFinish("session") },
            { manager.recoverInterruptedSession() },
        )
        gate.coordinator.withExclusive(1_000) {
            writers.forEach { writer ->
                assertTrue(runCatching { writer() }.exceptionOrNull() is MaintenanceUnavailableException)
            }
        }
    }

    private class Workflow : StudyWorkflowRepository by unsupported() {
        var state = FocusCloseoutState.ACTIVE
        private var boundary = 0L
        override suspend fun getCloseoutState(sessionId: String) = state
        override suspend fun beginCloseout(sessionId: String, requestedEndPage: Int, closeoutStartedAt: Long,
            backwardClockEvidence: BackwardClockCloseoutEvidence?): CloseoutSnapshot {
            state = FocusCloseoutState.PENDING
            boundary = closeoutStartedAt
            return CloseoutSnapshot(sessionId, closeoutStartedAt, requestedEndPage)
        }
        override suspend fun completeCloseout(sessionId: String): StudySessionEntity {
            state = FocusCloseoutState.COMPLETED
            return StudySessionEntity(sessionId, "item", "intent", 1_000, null, boundary, 1, 2, 2,
                SessionEndType.NORMAL, "summary", null)
        }
    }

    private companion object {
        fun unsupported(): StudyWorkflowRepository = Proxy.newProxyInstance(StudyWorkflowRepository::class.java.classLoader,
            arrayOf(StudyWorkflowRepository::class.java)) { _, method, _ -> error("Unexpected ${method.name}") } as StudyWorkflowRepository
    }
}
