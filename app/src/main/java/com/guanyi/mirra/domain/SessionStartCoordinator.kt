package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.repository.FocusRepository
import com.guanyi.mirra.data.repository.MonitoredSessionStartResult
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.platform.focus.MonitoringReadyLease
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

enum class CoordinatorPhase { IDLE, PREPARING, READY_LEASE, COMMITTING, COMMITTED, BOUND }
data class CoordinatorState(val phase: CoordinatorPhase = CoordinatorPhase.IDLE, val generation: String? = null, val sessionId: String? = null)
enum class StartMonitoringResult { MONITORED, UNMONITORED, DEGRADED, EXISTING }
data class SessionStartOutcome(val session: StudySessionEntity, val monitoring: StartMonitoringResult)

/** Only this boundary coordinates the non-atomic Android monitor and Room transaction. */
interface MonitoredStartPort {
    suspend fun preflight(): Boolean
    fun start(): String
    suspend fun awaitReady(generation: String, timeoutMillis: Long): MonitoringReadyLease?
    fun verify(lease: MonitoringReadyLease): Boolean
    suspend fun verifyAfterCommit(lease: MonitoringReadyLease): Boolean
    fun bind(sessionId: String, generation: String): Boolean
    fun stopUnbound(generation: String)
    fun nowWall(): Long
}

interface MonitoredStartStore {
    suspend fun findActiveForIntent(intentId: String): StudySessionEntity?
    suspend fun findCommittedMonitoredForIntent(
        intentId: String, proposedSessionId: String, lease: MonitoringReadyLease,
    ): StudySessionEntity?
    suspend fun startUnmonitored(intentId: String, page: Int): StudySessionEntity
    suspend fun startMonitored(
        intentId: String, page: Int, lease: MonitoringReadyLease, proposedSessionId: String,
    ): MonitoredSessionStartResult
    suspend fun markMonitoringLost(sessionId: String, lastTrustedAt: Long, detectedAt: Long)
}

class RepositoryMonitoredStartStore(
    private val workflow: StudyWorkflowRepository,
    private val focus: FocusRepository,
) : MonitoredStartStore {
    override suspend fun findActiveForIntent(intentId: String) = workflow.findActiveSessionForIntent(intentId)
    override suspend fun findCommittedMonitoredForIntent(
        intentId: String, proposedSessionId: String, lease: MonitoringReadyLease,
    ): StudySessionEntity? {
        val session = workflow.findActiveSessionForIntent(intentId)?.takeIf {
            it.id == proposedSessionId && it.startedAt == lease.readyAtWall
        } ?: return null
        val context = focus.observeContext(session.id).first() ?: return null
        val segment = focus.observeActiveSegment(session.id).first() ?: return null
        return session.takeIf {
            context.monitoringStatus == MonitoringCoverage.FULL && context.usageAccessAtStart &&
                segment.type == SessionSegmentType.FOCUS && segment.startedAt == session.startedAt
        }
    }
    override suspend fun startUnmonitored(intentId: String, page: Int) = workflow.startSession(intentId, page)
    override suspend fun startMonitored(
        intentId: String, page: Int, lease: MonitoringReadyLease, proposedSessionId: String,
    ) = workflow.startMonitoredSession(intentId, page, lease, proposedSessionId)
    override suspend fun markMonitoringLost(sessionId: String, lastTrustedAt: Long, detectedAt: Long) =
        focus.markMonitoringLost(sessionId, lastTrustedAt, detectedAt)
}

class SessionStartCoordinator(
    private val store: MonitoredStartStore,
    private val monitor: MonitoredStartPort,
    private val readyTimeoutMillis: Long = 5_000,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(CoordinatorState())
    val state: StateFlow<CoordinatorState> = mutableState

    suspend fun start(intentId: String, startPage: Int): SessionStartOutcome {
        mutex.lock()
        var generation: String? = null
        var readyLease: MonitoringReadyLease? = null
        var proposedSessionId: String? = null
        try {
            store.findActiveForIntent(intentId)?.let { return SessionStartOutcome(it, StartMonitoringResult.EXISTING) }
            mutableState.value = CoordinatorState(CoordinatorPhase.PREPARING)
            val available = try { monitor.preflight() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { false }
            if (!available) return fallback(intentId, startPage, null)
            generation = try { monitor.start() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { return fallback(intentId, startPage, null) }
            val activeGeneration = generation
            readyLease = try { monitor.awaitReady(activeGeneration, readyTimeoutMillis) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            val lease = readyLease
            if (lease == null || lease.generation != activeGeneration || !monitor.verify(lease)) {
                return fallback(intentId, startPage, activeGeneration)
            }
            mutableState.value = CoordinatorState(CoordinatorPhase.READY_LEASE, activeGeneration)
            yield()
            if (!monitor.verify(lease)) return fallback(intentId, startPage, activeGeneration)
            proposedSessionId = newSessionId()
            mutableState.value = CoordinatorState(CoordinatorPhase.COMMITTING, activeGeneration)
            val started = store.startMonitored(intentId, startPage, lease, proposedSessionId)
            if (!started.created) {
                monitor.stopUnbound(activeGeneration)
                mutableState.value = CoordinatorState()
                return SessionStartOutcome(started.session, StartMonitoringResult.EXISTING)
            }
            val session = started.session
            mutableState.value = CoordinatorState(CoordinatorPhase.COMMITTED, activeGeneration, session.id)
            if (!monitor.verifyAfterCommit(lease) || !monitor.bind(session.id, activeGeneration)) {
                store.markMonitoringLost(session.id, lease.readyAtWall, maxOf(lease.readyAtWall, monitor.nowWall()))
                monitor.stopUnbound(activeGeneration)
                mutableState.value = CoordinatorState()
                return SessionStartOutcome(session, StartMonitoringResult.DEGRADED)
            }
            mutableState.value = CoordinatorState(CoordinatorPhase.BOUND, activeGeneration, session.id)
            return SessionStartOutcome(session, StartMonitoringResult.MONITORED)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { reconcileUnknownCommit(intentId, generation, readyLease, proposedSessionId) }
            throw cancelled
        } catch (failure: Throwable) {
            withContext(NonCancellable) { reconcileUnknownCommit(intentId, generation, readyLease, proposedSessionId) }
            throw failure
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun fallback(intentId: String, page: Int, generation: String?): SessionStartOutcome {
        if (generation != null) monitor.stopUnbound(generation)
        mutableState.value = CoordinatorState()
        return SessionStartOutcome(store.startUnmonitored(intentId, page), StartMonitoringResult.UNMONITORED)
    }

    private suspend fun reconcileUnknownCommit(
        intentId: String, generation: String?, lease: MonitoringReadyLease?, proposedSessionId: String?,
    ) {
        if (generation == null) { mutableState.value = CoordinatorState(); return }
        val committed = if (lease != null && proposedSessionId != null &&
            mutableState.value.phase >= CoordinatorPhase.COMMITTING) {
            store.findCommittedMonitoredForIntent(intentId, proposedSessionId, lease)
        } else null
        if (committed == null) {
            monitor.stopUnbound(generation)
            mutableState.value = CoordinatorState()
            return
        }
        if (lease != null && monitor.verifyAfterCommit(lease) && monitor.bind(committed.id, generation)) {
            mutableState.value = CoordinatorState(CoordinatorPhase.BOUND, generation, committed.id)
            return
        }
        store.markMonitoringLost(committed.id, lease?.readyAtWall ?: committed.startedAt,
            maxOf(committed.startedAt, monitor.nowWall()))
        monitor.stopUnbound(generation)
        mutableState.value = CoordinatorState()
    }
}
