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

interface SessionManager {
    suspend fun start(intentId: String, startPage: Int): StudySessionEntity
    suspend fun updatePage(sessionId: String, page: Int)
    suspend fun finish(sessionId: String, endPage: Int, sample: ClockSample): SessionFinishResult
    suspend fun retryPendingFinish(sessionId: String): SessionFinishResult
    suspend fun recoverInterruptedSession()
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
        withDurableCleanup(sessionId) {
            closeoutWithMonitoringFacts(sessionId, sample) { evidence, invalidate ->
                workflowRepository.beginCloseout(sessionId, endPage, sample.wallNowMillis, evidence)
                invalidate() // Memory only, after A, before B. No Android effect under the facts lock.
                complete(sessionId)
            }
        }

    override suspend fun retryPendingFinish(sessionId: String): SessionFinishResult =
        withDurableCleanup(sessionId) { complete(sessionId) }

    private suspend fun complete(sessionId: String): SessionFinishResult = try {
        SessionFinishResult.Completed(workflowRepository.completeCloseout(sessionId))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        if (workflowRepository.getCloseoutState(sessionId) == FocusCloseoutState.PENDING) SessionFinishResult.PendingRetry(sessionId)
        else throw failure
    }

    private suspend fun withDurableCleanup(sessionId: String, block: suspend () -> SessionFinishResult): SessionFinishResult {
        var primary: Throwable? = null
        try { return block() }
        catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try {
                withContext(NonCancellable) { withContext(Dispatchers.IO) {
                    // Room may have committed even when cancellation hides its return value.
                    val state = withTimeout(5_000) { workflowRepository.getCloseoutState(sessionId) }
                    if (state == FocusCloseoutState.PENDING || state == FocusCloseoutState.COMPLETED) {
                        withTimeout(10_000) { cleanupClosedSession(sessionId) }
                    }
                } }
            } catch (failure: Throwable) {
                if (primary != null) { if (failure !== primary) primary.addSuppressed(failure) } else throw failure
            }
        }
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
