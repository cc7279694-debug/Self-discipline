package com.guanyi.mirra.domain.monitoring

import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.repository.FocusRepository
import com.guanyi.mirra.data.repository.RiskConfirmation
import com.guanyi.mirra.data.repository.RuntimeFocusFacts
import com.guanyi.mirra.platform.focus.MonitorSnapshot
import com.guanyi.mirra.platform.focus.MonitoringBinding
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RuntimeFactDiagnostics(
    val sessionId: String? = null,
    val generation: String? = null,
    val lastHeartbeatAt: Long? = null,
    val segment: SessionSegmentType? = null,
    val coverage: MonitoringCoverage? = null,
    val riskSnapshotCount: Int = 0,
    val candidatePackage: String? = null,
    val candidateToken: Long? = null,
    val candidateFirstSeenElapsed: Long? = null,
    val gapAt: Long? = null,
    val gapReason: String? = null,
)

interface RuntimeFactsPort {
    suspend fun read(sessionId: String): RuntimeFocusFacts?
    suspend fun heartbeat(sessionId: String, at: Long)
    suspend fun lose(sessionId: String, trustedAt: Long, detectedAt: Long)
    suspend fun confirm(command: RiskConfirmation): Boolean
    suspend fun brief(sessionId: String, packageName: String, at: Long): Boolean
    suspend fun exit(sessionId: String, packageName: String, at: Long): Boolean
}

class RepositoryRuntimeFactsPort(private val focus: FocusRepository) : RuntimeFactsPort {
    override suspend fun read(sessionId: String) = focus.runtimeFacts(sessionId)
    override suspend fun heartbeat(sessionId: String, at: Long) = focus.updateHeartbeat(sessionId, at)
    override suspend fun lose(sessionId: String, trustedAt: Long, detectedAt: Long) =
        focus.markMonitoringLost(sessionId, trustedAt, detectedAt)
    override suspend fun confirm(command: RiskConfirmation) = focus.confirmRisk(command)
    override suspend fun brief(sessionId: String, packageName: String, at: Long) =
        focus.recordBriefRiskVisit(sessionId, packageName, at)
    override suspend fun exit(sessionId: String, packageName: String, at: Long) = focus.exitRisk(sessionId, packageName, at)
}

/** Serialized application boundary between platform observations and durable Session facts. */
class BoundSessionMonitoringController(private val factsPort: RuntimeFactsPort) {
    constructor(focus: FocusRepository) : this(RepositoryRuntimeFactsPort(focus))
    private val mutex = Mutex()
    private var activeBinding: MonitoringBinding? = null
    private var candidate: CandidateRiskAppMachine? = null
    private var lastSuccessfulQueryElapsed: Long? = null
    private var lastTrustedWall: Long? = null
    private var lastHeartbeatElapsed: Long? = null
    private var lost = false
    private var unresolvedLoss = false
    var diagnostics = RuntimeFactDiagnostics()
        private set

    /** Finish and durable monitoring-loss transitions share one ordering boundary. */
    suspend fun <T> finishWithFacts(block: suspend () -> T): T = mutex.withLock {
        check(!unresolvedLoss) { "Monitoring loss has not been saved" }
        block()
    }

    suspend fun onSample(binding: MonitoringBinding?, snapshot: MonitorSnapshot, sample: ClockSample) = mutex.withLock {
        if (binding == null) return@withLock
        if (activeBinding != binding) {
            activeBinding = binding
            candidate = null
            lastSuccessfulQueryElapsed = null
            lastHeartbeatElapsed = null
            lastTrustedWall = factsPort.read(binding.sessionId)?.lastHeartbeatAt
            lost = false
            diagnostics = RuntimeFactDiagnostics(binding.sessionId, binding.generation)
        }
        if (lost) return@withLock
        val previousQuery = lastSuccessfulQueryElapsed
        val hardFailure = !snapshot.running || MonitoringSignal.GAP in snapshot.signals ||
            MonitoringSignal.WALL_CLOCK_JUMP in snapshot.signals
        val timedOut = previousQuery != null && sample.elapsedNowMillis - previousQuery >= 6_000
        if (hardFailure || timedOut) {
            lose(binding, sample, if (timedOut) "query deadline" else snapshot.lastPlatformError ?: snapshot.signals.toString())
            return@withLock
        }
        if (snapshot.lastSuccessfulQueryElapsed != sample.elapsedNowMillis) return@withLock
        val facts = factsPort.read(binding.sessionId) ?: return@withLock
        if (facts.coverage == MonitoringCoverage.NONE) return@withLock
        diagnostics = diagnostics.copy(segment = facts.activeSegment.type, coverage = facts.coverage,
            riskSnapshotCount = facts.riskPackages.size)
        lastSuccessfulQueryElapsed = sample.elapsedNowMillis
        lastTrustedWall = sample.wallNowMillis.coerceAtLeast(facts.sessionStartedAt)
        if (lastHeartbeatElapsed == null) {
            // Session creation writes the first heartbeat. Align cadence to that persisted
            // boundary, not to a potentially delayed Service-to-Session binding callback.
            lastHeartbeatElapsed = (sample.elapsedNowMillis -
                (sample.wallNowMillis - facts.lastHeartbeatAt)).coerceAtLeast(0)
        }
        if (sample.elapsedNowMillis - lastHeartbeatElapsed!! >= 15_000) {
            factsPort.heartbeat(binding.sessionId, lastTrustedWall!!)
            lastHeartbeatElapsed = sample.elapsedNowMillis
            diagnostics = diagnostics.copy(lastHeartbeatAt = lastTrustedWall)
        }
        val riskMachine = candidate ?: CandidateRiskAppMachine { packageName ->
            if (packageName in facts.riskPackages) PackageClass.RISK else PackageClass.NORMAL
        }.also { candidate = it }
        val observation = snapshot.observation
        if (facts.activeSegment.type == SessionSegmentType.DISTRACTION) {
            val trustedExit = observation is ForegroundObservation.ScreenOff ||
                observation is ForegroundObservation.DeviceLocked ||
                (observation is ForegroundObservation.Package && observation.name != facts.activeSegment.packageName)
            if (trustedExit) {
                val at = snapshot.lastEventWallMillis ?: sample.wallNowMillis
                factsPort.exit(binding.sessionId, facts.activeSegment.packageName.orEmpty(), at)
            }
            riskMachine.clear()
            return@withLock
        }
        val focusSegment = facts.activeSegment.takeIf { it.type.countsAsFocus }
        val decisions = riskMachine.accept(CandidateInput(observation, sample.elapsedNowMillis,
            snapshot.queryGeneration, focusSegment?.id, queryContinuous = true,
            foregroundFresh = observation is ForegroundObservation.Package,
            wallNowMillis = sample.wallNowMillis, generation = binding.generation,
            sourceFocusStartedAt = focusSegment?.startedAt,
            departureEventAtWallMillis = snapshot.lastEventWallMillis))
        val activeCandidate = (riskMachine.state as? RiskCandidateState.Candidate)
            ?: (riskMachine.state as? RiskCandidateState.Confirmed)?.candidate
        diagnostics = diagnostics.copy(candidatePackage = activeCandidate?.packageName,
            candidateToken = activeCandidate?.token,
            candidateFirstSeenElapsed = activeCandidate?.firstSeenElapsed)
        for (decision in decisions) when (decision) {
            is RiskDecision.BriefVisit -> factsPort.brief(binding.sessionId,
                decision.candidate.packageName, decision.exitedAtWall)
            is RiskDecision.Confirmed -> {
                val confirmed = factsPort.confirm(RiskConfirmation(binding.sessionId,
                    decision.sourceFocusSegmentId, decision.packageName,
                    decision.firstSeenWall, sample.wallNowMillis))
                if (!confirmed) riskMachine.clear()
            }
        }
    }

    suspend fun onServiceLost(binding: MonitoringBinding?, sample: ClockSample, reason: String) = mutex.withLock {
        if (binding == null) return@withLock
        if (activeBinding != binding) {
            activeBinding = binding
            lastTrustedWall = factsPort.read(binding.sessionId)?.lastHeartbeatAt
        }
        lose(binding, sample, reason)
    }

    fun onNormalRelease(sessionId: String) {
        if (activeBinding?.sessionId == sessionId) {
            activeBinding = null
            candidate = null
            diagnostics = RuntimeFactDiagnostics()
        }
    }

    private suspend fun lose(binding: MonitoringBinding, sample: ClockSample, reason: String) {
        if (lost) return
        candidate?.clear()
        val facts = factsPort.read(binding.sessionId) ?: return
        if (facts.coverage == MonitoringCoverage.NONE) return
        val trusted = (lastTrustedWall ?: facts.lastHeartbeatAt).coerceAtLeast(facts.activeSegment.startedAt)
        try {
            factsPort.lose(binding.sessionId, trusted, maxOf(trusted, sample.wallNowMillis))
        } catch (failure: Throwable) {
            unresolvedLoss = true
            throw failure
        }
        unresolvedLoss = false
        lost = true
        diagnostics = diagnostics.copy(gapAt = trusted, gapReason = reason, candidatePackage = null,
            candidateToken = null, candidateFirstSeenElapsed = null,
            segment = SessionSegmentType.UNMONITORED,
            coverage = if (facts.coverage == MonitoringCoverage.FULL) MonitoringCoverage.PARTIAL else facts.coverage)
    }
}
