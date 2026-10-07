package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.domain.monitoring.ClockSample
import com.guanyi.mirra.domain.monitoring.BackwardClockCloseoutEvidence
import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface SessionManager {
    suspend fun start(intentId: String, startPage: Int): StudySessionEntity
    suspend fun updatePage(sessionId: String, page: Int)
    suspend fun finish(sessionId: String, endPage: Int, sample: ClockSample): SessionFinishResult
    suspend fun retryPendingFinish(sessionId: String): SessionFinishResult
    suspend fun recoverInterruptedSession(): SessionRecoveryResult
}

class DefaultSessionManager(
    private val workflowRepository: StudyWorkflowRepository,
    private val onSessionCreated: suspend (StudySessionEntity) -> Unit = {},
    private val closeoutWithMonitoringFacts: suspend (String, ClockSample, suspend (BackwardClockCloseoutEvidence?, () -> Unit) -> SessionFinishResult) -> SessionFinishResult = { _, _, block -> block(null, {}) },
    private val cleanupClosedSession: suspend (String) -> Unit = {},
) : SessionManager {
    override suspend fun start(intentId: String, startPage: Int): StudySessionEntity {
        val session = workflowRepository.startSession(intentId, startPage)
        onSessionCreated(session)
        return session
    }

    override suspend fun updatePage(sessionId: String, page: Int) =
        workflowRepository.updateCurrentPage(sessionId, page)

    override suspend fun finish(sessionId: String, endPage: Int, sample: ClockSample): SessionFinishResult =
        withDurableCleanup(sessionId) { cleanup ->
            closeoutWithMonitoringFacts(sessionId, sample) { evidence, invalidate ->
                workflowRepository.beginCloseout(sessionId, endPage, sample.wallNowMillis, evidence)
                invalidate() // Memory only. PENDING durably rejects all late learning facts.
                SessionFinishResult.PendingRetry(sessionId) // Internal A result, not presented as a failure.
            }
            // Release the existing facts mutex before calling any Android capability.
            cleanup()
            currentCoroutineContext().ensureActive()
            complete(sessionId)
        }

    override suspend fun retryPendingFinish(sessionId: String): SessionFinishResult =
        withDurableCleanup(sessionId) { cleanup ->
            cleanup()
            currentCoroutineContext().ensureActive()
            complete(sessionId)
        }

    private suspend fun complete(sessionId: String): SessionFinishResult = try {
        SessionFinishResult.Completed(workflowRepository.completeCloseout(sessionId))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        if (workflowRepository.getCloseoutState(sessionId) == FocusCloseoutState.PENDING) SessionFinishResult.PendingRetry(sessionId)
        else throw failure
    }

    private suspend fun withDurableCleanup(sessionId: String,
        block: suspend (suspend () -> Unit) -> SessionFinishResult): SessionFinishResult {
        var cleanupAttempted = false
        val cleanup: suspend () -> Unit = {
            if (!cleanupAttempted) {
                try {
                    withContext(NonCancellable) { withContext(Dispatchers.IO) {
                        // Room may have committed even when cancellation hides its return value.
                        val state = withTimeout(5_000) { workflowRepository.getCloseoutState(sessionId) }
                        if (state == FocusCloseoutState.PENDING || state == FocusCloseoutState.COMPLETED) {
                            cleanupAttempted = true
                            withTimeout(10_000) { cleanupClosedSession(sessionId) }
                        }
                    } }
                } catch (_: Exception) {
                    // Capability owners retain release-failure metadata. Cleanup cannot mask A/B facts.
                    // Caller cancellation is checked separately, after this finite compensation.
                }
            }
        }
        try { return block(cleanup) }
        finally { cleanup() }
    }

    override suspend fun recoverInterruptedSession() =
        workflowRepository.recoverInterruptedSession()
}

/** Finite independent cleanup steps; a failed channel cannot skip monitor or DND release. */
internal suspend fun cleanupCloseoutSteps(vararg steps: suspend () -> Unit) {
    for (step in steps) {
        try { withTimeout(2_500) { step() } }
        catch (_: TimeoutCancellationException) { /* This step timed out; try the next owned resource. */ }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Owners retain release-failure metadata / retry eligibility. */ }
    }
}
