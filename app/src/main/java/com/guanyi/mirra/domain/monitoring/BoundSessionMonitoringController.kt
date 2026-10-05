package com.guanyi.mirra.domain.monitoring

import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.repository.FocusRepository
import com.guanyi.mirra.data.repository.RiskConfirmation
import com.guanyi.mirra.data.repository.RuntimeFocusFacts
import com.guanyi.mirra.data.repository.BehaviorCommand
import com.guanyi.mirra.data.repository.BehaviorAction
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.repository.SegmentTransitionCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    suspend fun closeoutState(sessionId: String): FocusCloseoutState? = FocusCloseoutState.ACTIVE
    suspend fun behavior(command: BehaviorCommand): SessionSegmentEntity? = null
    suspend fun milestone(sessionId: String, at: Long, evidence: StableEvidence): Boolean = false
    suspend fun demoteDeep(sessionId: String, at: Long): Boolean = false
    suspend fun read(sessionId: String): RuntimeFocusFacts?
    suspend fun heartbeat(sessionId: String, at: Long)
    suspend fun lose(sessionId: String, trustedAt: Long, detectedAt: Long)
    suspend fun confirm(command: RiskConfirmation): Boolean
    suspend fun brief(sessionId: String, packageName: String, at: Long): Boolean
    suspend fun exit(sessionId: String, packageName: String, at: Long): Boolean
}

class RepositoryRuntimeFactsPort(private val focus: FocusRepository) : RuntimeFactsPort {
    override suspend fun closeoutState(sessionId: String) = focus.observeContext(sessionId).first()?.closeoutState
    override suspend fun behavior(command: BehaviorCommand) = focus.applyBehavior(command)
    override suspend fun milestone(sessionId: String, at: Long, evidence: StableEvidence): Boolean {
        when (evidence.milestone) {
            EvidenceMilestone.RECOVERY -> focus.completeRecovery(sessionId, at, evidence)
            EvidenceMilestone.STABLE -> focus.markStableStarted(sessionId, at, evidence)
            EvidenceMilestone.DEEP -> focus.promoteDeepFocus(sessionId, at, evidence)
        }
        return true
    }
    override suspend fun demoteDeep(sessionId: String, at: Long): Boolean {
        focus.transition(SegmentTransitionCommand(sessionId, SessionSegmentType.FOCUS, at))
        return true
    }
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
class BoundSessionMonitoringController(private val factsPort: RuntimeFactsPort) : FocusSessionActions {
    constructor(focus: FocusRepository) : this(RepositoryRuntimeFactsPort(focus))
    private val mutex = Mutex()
    private var activeBinding: MonitoringBinding? = null
    private var candidate: CandidateRiskAppMachine? = null
    private var lastSuccessfulQueryElapsed: Long? = null
    private var lastTrustedWall: Long? = null
    private var lastRawQueryClock: ClockSample? = null
    private var backwardCloseoutEvidence: BackwardClockCloseoutEvidence? = null
    private data class ObservedBackwardClock(val binding: MonitoringBinding, val trusted: ClockSample,
        val regression: ClockSample, val lossBoundary: Long)
    private var observedBackwardClock: ObservedBackwardClock? = null
    private var lastHeartbeatElapsed: Long? = null
    private var lost = false
    private var unresolvedLoss = false
    private val mutableIntervention = MutableStateFlow<InterventionUiModel?>(null)
    override val intervention: StateFlow<InterventionUiModel?> = mutableIntervention
    private val mutableStatus = MutableStateFlow(FocusStatusUiModel())
    override val focusStatus: StateFlow<FocusStatusUiModel> = mutableStatus
    private val evidenceTracker = StableEvidenceTracker()
    private var wait: FrictionWait? = null
    private var timedSegment: String? = null
    private var deadlineElapsed: Long? = null
    private var deadlineWall: Long? = null
    private var lastActionClock: ClockSample? = null
    private var lastEvidenceClock: ClockSample? = null
    var diagnostics = RuntimeFactDiagnostics()
        private set

    /** A / memory invalidation / B share the existing facts boundary; platform cleanup is outside it. */
    suspend fun <T> closeoutWithFacts(sessionId: String, sample: ClockSample,
        block: suspend (BackwardClockCloseoutEvidence?, () -> Unit) -> T): T = mutex.withLock {
        var primary: Throwable? = null
        try {
            check(!unresolvedLoss) { "Monitoring loss has not been saved" }
            settleMonitoring(sessionId, sample)
            if (activeBinding?.sessionId == sessionId) validateBackwardCloseoutEvidence()
            val evidence = backwardCloseoutEvidence?.takeIf { activeBinding?.sessionId == sessionId && it.sessionId == sessionId }
            block(evidence) { invalidateCloseoutRuntime(sessionId) }
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try {
                withContext(NonCancellable) { withContext(Dispatchers.IO) {
                    val state = withTimeout(5_000) { factsPort.closeoutState(sessionId) }
                    if (state == FocusCloseoutState.PENDING || state == FocusCloseoutState.COMPLETED) invalidateCloseoutRuntime(sessionId)
                } }
            } catch (failure: Throwable) {
                if (primary != null) { if (failure !== primary) primary.addSuppressed(failure) } else throw failure
            }
        }
    }

    private fun invalidateCloseoutRuntime(sessionId: String) {
        if (activeBinding?.sessionId != sessionId && mutableStatus.value.sessionId != sessionId &&
            mutableIntervention.value?.sessionId != sessionId) return
        candidate?.clear(); candidate = null; clearBehaviorRuntime()
        backwardCloseoutEvidence = null; lastRawQueryClock = null; observedBackwardClock = null
    }

    suspend fun onSample(binding: MonitoringBinding?, snapshot: MonitorSnapshot, sample: ClockSample) = mutex.withLock {
        if (binding == null) return@withLock
        if (activeBinding != binding) {
            activeBinding = binding
            candidate = null
            lastSuccessfulQueryElapsed = null
            lastRawQueryClock = null
            backwardCloseoutEvidence = null
            observedBackwardClock = null
            lastHeartbeatElapsed = null
            lastTrustedWall = factsPort.read(binding.sessionId)?.lastHeartbeatAt
            lost = false
            clearBehaviorRuntime()
            diagnostics = RuntimeFactDiagnostics(binding.sessionId, binding.generation)
        }
        if (lost) return@withLock
        val previousQuery = lastSuccessfulQueryElapsed
        val hardFailure = !snapshot.running || MonitoringSignal.GAP in snapshot.signals ||
            MonitoringSignal.WALL_CLOCK_JUMP in snapshot.signals
        val timedOut = previousQuery != null && sample.elapsedNowMillis - previousQuery >= 6_000
        if (hardFailure || timedOut) {
            lose(binding, sample, if (timedOut) "query deadline" else snapshot.lastPlatformError ?: snapshot.signals.toString(),
                lastRawQueryClock.takeIf { MonitoringSignal.WALL_CLOCK_JUMP in snapshot.signals })
            return@withLock
        }
        if (snapshot.lastSuccessfulQueryElapsed != sample.elapsedNowMillis) return@withLock
        var facts = factsPort.read(binding.sessionId) ?: return@withLock
        if (facts.coverage == MonitoringCoverage.NONE) return@withLock
        diagnostics = diagnostics.copy(segment = facts.activeSegment.type, coverage = facts.coverage,
            riskSnapshotCount = facts.riskPackages.size)
        lastSuccessfulQueryElapsed = sample.elapsedNowMillis
        lastRawQueryClock = snapshot.lastSuccessfulQueryClock?.takeIf { it == sample }
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
        if (expireIfDue(facts, sample)) facts = factsPort.read(binding.sessionId) ?: return@withLock
        publish(facts, sample)
        val riskMachine = candidate ?: CandidateRiskAppMachine { packageName ->
            if (packageName in facts.riskPackages) PackageClass.RISK else PackageClass.NORMAL
        }.also { candidate = it }
        val observation = snapshot.observation
        if (observation is ForegroundObservation.Package && observation.name in facts.riskPackages) {
            evidenceTracker.clear()
        }
        if (facts.activeSegment.type == SessionSegmentType.BREAK || facts.activeSegment.type == SessionSegmentType.UNMONITORED) {
            riskMachine.clear(); evidenceTracker.clear(); return@withLock
        }
        if (facts.activeSegment.type == SessionSegmentType.DISTRACTION) {
            val trustedExit = observation is ForegroundObservation.ScreenOff ||
                observation is ForegroundObservation.DeviceLocked ||
                (observation is ForegroundObservation.Package && observation.name != facts.activeSegment.packageName)
            if (trustedExit) {
                val at = snapshot.lastEventWallMillis ?: sample.wallNowMillis
                factsPort.exit(binding.sessionId, facts.activeSegment.packageName.orEmpty(), at)
                factsPort.read(binding.sessionId)?.let { publish(it, sample) }
            }
            if (observation !is ForegroundObservation.Package || observation.name == facts.activeSegment.packageName) {
                riskMachine.clear(); return@withLock
            }
            // A different risk App gets a new candidate; never retain the old package episode.
            facts = factsPort.read(binding.sessionId) ?: return@withLock
        }
        if (facts.activeSegment.type == SessionSegmentType.TEMPORARY_ALLOWANCE) {
            if (observation is ForegroundObservation.ScreenOff || observation is ForegroundObservation.DeviceLocked) {
                factsPort.behavior(BehaviorCommand(binding.sessionId, facts.activeSegment.id,
                    BehaviorAction.FINISH_ALLOWANCE, sample.wallNowMillis, "screen:${facts.activeSegment.id}"))
                riskMachine.clear(); factsPort.read(binding.sessionId)?.let { publish(it, sample) }; return@withLock
            }
            if (observation is ForegroundObservation.Package && observation.name == facts.activeSegment.packageName) {
                val departures = riskMachine.accept(CandidateInput(observation, sample.elapsedNowMillis,
                    snapshot.queryGeneration, null, queryContinuous = true, foregroundFresh = true,
                    wallNowMillis = sample.wallNowMillis, generation = binding.generation,
                    departureEventAtWallMillis = snapshot.lastEventWallMillis))
                for (decision in departures) if (decision is RiskDecision.BriefVisit) {
                    factsPort.brief(binding.sessionId, decision.candidate.packageName, decision.exitedAtWall)
                }
                return@withLock
            }
        }
        val focusSegment = facts.activeSegment.takeIf { it.type.countsAsFocus || it.type in setOf(
            SessionSegmentType.RECOVERY, SessionSegmentType.TEMPORARY_ALLOWANCE, SessionSegmentType.DISTRACTION) }
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
                else { evidenceTracker.clear(); factsPort.read(binding.sessionId)?.let { publish(it, sample) } }
            }
        }
    }

    suspend fun onServiceLost(binding: MonitoringBinding?, sample: ClockSample, reason: String) = mutex.withLock {
        if (binding == null) return@withLock
        if (activeBinding != binding) {
            activeBinding = binding
            lastRawQueryClock = null
            backwardCloseoutEvidence = null
            observedBackwardClock = null
            lastTrustedWall = factsPort.read(binding.sessionId)?.lastHeartbeatAt
        }
        lose(binding, sample, reason)
    }

    fun onNormalRelease(sessionId: String) {
        if (mutableStatus.value.sessionId == sessionId) clearBehaviorRuntime()
        if (activeBinding?.sessionId == sessionId) {
            activeBinding = null
            candidate = null
            lastRawQueryClock = null
            backwardCloseoutEvidence = null
            observedBackwardClock = null
            diagnostics = RuntimeFactDiagnostics()
        }
    }

    private suspend fun lose(binding: MonitoringBinding, sample: ClockSample, reason: String,
        possibleBackwardClock: ClockSample? = null) {
        if (lost) return
        candidate?.clear()
        clearBehaviorRuntime()
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
        val pair = possibleBackwardClock?.takeIf {
            sample.elapsedNowMillis >= it.elapsedNowMillis && sample.wallNowMillis < it.wallNowMillis &&
                kotlin.math.abs((sample.wallNowMillis - it.wallNowMillis) - (sample.elapsedNowMillis - it.elapsedNowMillis)) > 2_000
        }
        if (pair != null) {
            // Retain the observed pair even if the following durable read fails/cancels.
            // Retry only validation, never rewrite the already committed monitoring loss.
            observedBackwardClock = ObservedBackwardClock(binding, pair, sample, trusted)
            validateBackwardCloseoutEvidence()
        }
        diagnostics = diagnostics.copy(gapAt = trusted, gapReason = reason, candidatePackage = null,
            candidateToken = null, candidateFirstSeenElapsed = null,
            segment = SessionSegmentType.UNMONITORED,
            coverage = if (facts.coverage == MonitoringCoverage.FULL) MonitoringCoverage.PARTIAL else facts.coverage)
    }

    private suspend fun validateBackwardCloseoutEvidence() {
        val observed = observedBackwardClock?.takeIf { it.binding == activeBinding } ?: return
        if (backwardCloseoutEvidence != null) return
        val durable = factsPort.read(observed.binding.sessionId)
        val unknown = durable?.activeSegment
        if (durable?.coverage == MonitoringCoverage.PARTIAL && durable.context?.monitoringLostAt == observed.lossBoundary &&
            unknown?.type == SessionSegmentType.UNMONITORED && unknown.startedAt == observed.lossBoundary) {
            backwardCloseoutEvidence = BackwardClockCloseoutEvidence(observed.binding.sessionId,
                observed.trusted, observed.regression, observed.lossBoundary, unknown.id)
        }
    }

    private fun clearBehaviorRuntime() {
        mutableIntervention.value = null; mutableStatus.value = FocusStatusUiModel()
        wait = null; evidenceTracker.clear(); timedSegment = null; deadlineElapsed = null
        deadlineWall = null; lastActionClock = null; lastEvidenceClock = null
    }

    private fun publish(facts: RuntimeFocusFacts, sample: ClockSample) {
        val segment = facts.activeSegment
        if (timedSegment != segment.id) {
            timedSegment = segment.id
            deadlineWall = segment.plannedEndAt
            deadlineElapsed = segment.plannedEndAt?.let { sample.elapsedNowMillis + it - sample.wallNowMillis }
        } else if (deadlineWall != segment.plannedEndAt) {
            // Extend the original monotonic deadline rather than re-anchoring it each poll.
            deadlineElapsed = deadlineElapsed?.plus((segment.plannedEndAt ?: 0) - (deadlineWall ?: 0))
            deadlineWall = segment.plannedEndAt
        }
        mutableStatus.value = FocusStatusUiModel(segment.sessionId, segment.id, segment.type, facts.coverage,
            deadlineElapsed?.let { (it - sample.elapsedNowMillis).coerceAtLeast(0) },
            segment.type == SessionSegmentType.TEMPORARY_ALLOWANCE && segment.extensionCount == 0)
        val event = facts.latestRiskEvent
        val relevant = facts.interventionEligible
        if (!relevant || event == null) { mutableIntervention.value = null; wait = null; return }
        val old = mutableIntervention.value
        if (old?.eventId != event.id) {
            val context = facts.context ?: return
            val delay = InterventionPolicy.waitMillis(facts.riskConfirmationCounts[event.packageName] ?: 0, context)
            mutableIntervention.value = InterventionUiModel(segment.sessionId, event.id, event.id, segment.id,
                event.packageName.orEmpty(), delay, delay,
                allowanceOptions = AllowanceReason.entries.map { AllowanceOption(it, InterventionPolicy.duration(it, context)) })
            wait = null
        } else mutableIntervention.value = old.copy(segmentId = segment.id,
            remainingWaitMillis = wait?.remaining(sample.elapsedNowMillis) ?: old.remainingWaitMillis)
    }

    private suspend fun expireIfDue(facts: RuntimeFocusFacts, sample: ClockSample): Boolean {
        publish(facts, sample)
        val deadline = deadlineElapsed ?: return false
        val wall = deadlineWall ?: return false
        if (sample.elapsedNowMillis < deadline || wall > sample.wallNowMillis) return false
        val changed = factsPort.behavior(BehaviorCommand(facts.activeSegment.sessionId, facts.activeSegment.id,
            BehaviorAction.EXPIRE, wall, "expire:${facts.activeSegment.id}")) != null
        if (changed) { candidate?.clear(); evidenceTracker.clear() }
        return changed
    }

    /** User actions cannot outrun an already due watchdog or borrow evidence across a clock jump. */
    private suspend fun settleMonitoring(sessionId: String, sample: ClockSample) {
        val binding = activeBinding?.takeIf { it.sessionId == sessionId } ?: return
        if (lost) return
        val query = lastSuccessfulQueryElapsed ?: return
        val trusted = lastTrustedWall ?: return
        // A newer healthy query may win the mutex after this action/page sampled
        // its clock. Do not infer loss or a mixed-pair wall jump backwards in time.
        // Return only from this check: the original command/evidence still runs.
        if (sample.elapsedNowMillis < query) return
        val elapsedDelta = sample.elapsedNowMillis - query
        if (elapsedDelta >= 6_000 ||
            kotlin.math.abs((sample.wallNowMillis - trusted) - elapsedDelta) > 2_000) {
            lose(binding, sample, "action observation deadline or clock jump", lastRawQueryClock)
        }
    }

    override suspend fun refresh(sessionId: String, sample: ClockSample) = mutex.withLock {
        settleMonitoring(sessionId, sample)
        val facts = factsPort.read(sessionId)
        if (facts == null) { clearBehaviorRuntime(); return@withLock }
        expireIfDue(facts, sample)
        factsPort.read(sessionId)?.let { publish(it, sample) }
        Unit
    }

    private suspend fun act(command: BehaviorCommand, sample: ClockSample): FocusActionResult = mutex.withLock {
        if (unresolvedLoss) return@withLock FocusActionResult.SAVE_FAILED
        try {
            settleMonitoring(command.sessionId, sample)
            val facts = factsPort.read(command.sessionId) ?: return@withLock FocusActionResult.EXPIRED
            val prior = lastActionClock
            if (prior != null && (sample.elapsedNowMillis < prior.elapsedNowMillis ||
                kotlin.math.abs((sample.wallNowMillis - prior.wallNowMillis) -
                    (sample.elapsedNowMillis - prior.elapsedNowMillis)) > 2_000)) return@withLock FocusActionResult.CONFLICT
            lastActionClock = sample
            expireIfDue(facts, sample)
            val updated = factsPort.read(command.sessionId) ?: return@withLock FocusActionResult.EXPIRED
            if (updated.activeSegment.id != command.expectedSegmentId) return@withLock FocusActionResult.EXPIRED
            if (command.action == BehaviorAction.GRANT_ALLOWANCE) {
                val prompt = mutableIntervention.value ?: return@withLock FocusActionResult.EXPIRED
                if (prompt.dismissed || prompt.promptToken != command.actionToken || prompt.sessionId != command.sessionId ||
                    prompt.packageName != command.packageName || prompt.reason?.name != command.reason)
                    return@withLock FocusActionResult.EXPIRED
                if ((wait?.remaining(sample.elapsedNowMillis) ?: Long.MAX_VALUE) > 0)
                    return@withLock FocusActionResult.CONFLICT
            }
            val segment = factsPort.behavior(command) ?: return@withLock FocusActionResult.CONFLICT
            candidate?.clear(); evidenceTracker.clear()
            factsPort.read(command.sessionId)?.let { publish(it, sample) }
            check(segment.sessionId == command.sessionId)
            FocusActionResult.SUCCESS
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { FocusActionResult.SAVE_FAILED }
    }

    override suspend fun startBreak(sessionId: String, expectedSegmentId: String, durationMillis: Long, sample: ClockSample) =
        act(BehaviorCommand(sessionId, expectedSegmentId, BehaviorAction.START_BREAK, sample.wallNowMillis,
            "break:$expectedSegmentId", durationMillis = durationMillis), sample)
    override suspend fun finishBreak(sessionId: String, expectedSegmentId: String, sample: ClockSample) =
        act(BehaviorCommand(sessionId, expectedSegmentId, BehaviorAction.FINISH_BREAK, sample.wallNowMillis, "finish:$expectedSegmentId"), sample)
    override suspend fun finishAllowance(sessionId: String, expectedSegmentId: String, sample: ClockSample) =
        act(BehaviorCommand(sessionId, expectedSegmentId, BehaviorAction.FINISH_ALLOWANCE, sample.wallNowMillis, "finish:$expectedSegmentId"), sample)
    override suspend fun extendAllowance(sessionId: String, expectedSegmentId: String, actionToken: String, sample: ClockSample) =
        act(BehaviorCommand(sessionId, expectedSegmentId, BehaviorAction.EXTEND_ALLOWANCE, sample.wallNowMillis, actionToken), sample)
    override suspend fun grantAllowance(sessionId: String, expectedSegmentId: String, promptToken: String,
        packageName: String, reason: AllowanceReason, durationMillis: Long?, sample: ClockSample): FocusActionResult {
        val context = factsPort.read(sessionId)?.context ?: return FocusActionResult.EXPIRED
        return act(BehaviorCommand(sessionId, expectedSegmentId, BehaviorAction.GRANT_ALLOWANCE, sample.wallNowMillis,
            promptToken, packageName, reason.name, durationMillis ?: InterventionPolicy.duration(reason, context), promptToken), sample)
    }

    override suspend fun selectAllowanceReason(sessionId: String, promptToken: String, reason: AllowanceReason,
        visible: Boolean, sample: ClockSample): FocusActionResult = mutex.withLock {
        val p = mutableIntervention.value?.takeIf { it.sessionId == sessionId && it.promptToken == promptToken && !it.dismissed }
            ?: return@withLock FocusActionResult.EXPIRED
        if (factsPort.read(sessionId) == null) return@withLock FocusActionResult.EXPIRED
        if (p.reason != reason || wait == null) wait = FrictionWait(p.waitMillis)
        wait!!.update(sample.elapsedNowMillis, visible)
        mutableIntervention.value = p.copy(reason = reason, remainingWaitMillis = wait!!.remaining(sample.elapsedNowMillis))
        FocusActionResult.SUCCESS
    }
    override suspend fun setPromptVisible(sessionId: String, promptToken: String, visible: Boolean,
        sample: ClockSample): FocusActionResult = mutex.withLock {
        val p = mutableIntervention.value?.takeIf { it.sessionId == sessionId && it.promptToken == promptToken }
            ?: return@withLock FocusActionResult.EXPIRED
        wait?.update(sample.elapsedNowMillis, visible)
        mutableIntervention.value = p.copy(remainingWaitMillis = wait?.remaining(sample.elapsedNowMillis) ?: p.waitMillis)
        FocusActionResult.SUCCESS
    }
    override suspend fun dismissPrompt(sessionId: String, promptToken: String, sample: ClockSample): FocusActionResult = mutex.withLock {
        val p = mutableIntervention.value?.takeIf { it.sessionId == sessionId && it.promptToken == promptToken }
            ?: return@withLock FocusActionResult.EXPIRED
        wait?.update(sample.elapsedNowMillis, false)
        mutableIntervention.value = p.copy(dismissed = true)
        FocusActionResult.SUCCESS
    }
    override suspend fun returnToStudy(sessionId: String, promptToken: String, sample: ClockSample) =
        dismissPrompt(sessionId, promptToken, sample) // navigation only; no recovery fact

    override suspend fun observeEvidence(sessionId: String, sample: ClockSample, screenNonInteractive: Boolean,
        sessionPageVisibleAndFocused: Boolean): FocusActionResult = mutex.withLock {
        try {
            settleMonitoring(sessionId, sample)
            val facts = factsPort.read(sessionId) ?: return@withLock FocusActionResult.EXPIRED
            val query = lastSuccessfulQueryElapsed
            val healthy = !lost && !unresolvedLoss && activeBinding?.sessionId == sessionId && query != null &&
                // A newer successful query proves continuity past a queued page
                // sample; keep that sample's own clock for the evidence window.
                (sample.elapsedNowMillis < query || sample.elapsedNowMillis - query in 0L..5_999L)
            val previous = lastEvidenceClock
            if (previous != null && (sample.elapsedNowMillis - previous.elapsedNowMillis !in 0L..5_999L ||
                kotlin.math.abs((sample.wallNowMillis - previous.wallNowMillis) -
                    (sample.elapsedNowMillis - previous.elapsedNowMillis)) > 2_000)) evidenceTracker.clear()
            lastEvidenceClock = sample
            val positive = screenNonInteractive || sessionPageVisibleAndFocused
            val context = facts.context ?: return@withLock FocusActionResult.CONFLICT
            if (facts.activeSegment.type == SessionSegmentType.DEEP_FOCUS && healthy && !screenNonInteractive) {
                factsPort.demoteDeep(sessionId, sample.wallNowMillis); evidenceTracker.clear()
            } else {
                val evidence = evidenceTracker.accept(facts.activeSegment.id, facts.activeSegment.type, facts.coverage,
                    sample.elapsedNowMillis, healthy, positive, screenNonInteractive, context.recoveryStableMillis,
                    context.stableStartMillis, context.deepFocusMillis, context.deepFocusScreenOffMillis)
                val proof = evidence.recovery ?: evidence.deep ?: evidence.stable?.takeIf { facts.stableStartedAt == null }
                if (proof != null) {
                    factsPort.milestone(sessionId, sample.wallNowMillis, proof)
                    if (proof.milestone != EvidenceMilestone.STABLE) evidenceTracker.clear()
                }
            }
            factsPort.read(sessionId)?.let { publish(it, sample) }
            FocusActionResult.SUCCESS
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { evidenceTracker.clear(); FocusActionResult.SAVE_FAILED }
    }
}
