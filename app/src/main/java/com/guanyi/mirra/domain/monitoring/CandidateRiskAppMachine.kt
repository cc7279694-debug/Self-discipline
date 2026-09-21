package com.guanyi.mirra.domain.monitoring

/** Pure, process-local candidate. Only trusted observations may create persisted facts. */
class CandidateRiskAppMachine(private val classify: (String) -> PackageClass) {
    var state: RiskCandidateState = RiskCandidateState.Idle
        private set
    private var nextToken = 1L

    fun clear() { state = RiskCandidateState.Idle }

    fun accept(input: CandidateInput): List<RiskDecision> {
        val packageObservation = input.observation as? ForegroundObservation.Package
        val riskPackage = packageObservation?.name?.takeIf { classify(it) == PackageClass.RISK }
        val previous = (state as? RiskCandidateState.Candidate)
            ?: (state as? RiskCandidateState.Confirmed)?.candidate
        val exitWall = input.departureEventAtWallMillis
            ?: packageObservation?.sourceEventAtWallMillis
        val trustedExit = input.queryContinuous && (input.foregroundFresh ||
            input.observation is ForegroundObservation.ScreenOff ||
            input.observation is ForegroundObservation.DeviceLocked) &&
            input.observation !is ForegroundObservation.MonitoringUnavailable &&
            input.observation !is ForegroundObservation.Unknown && exitWall != null &&
            input.wallNowMillis != null && exitWall in 0..input.wallNowMillis
        val brief = if (trustedExit && previous != null && state is RiskCandidateState.Candidate &&
            (riskPackage == null || riskPackage != previous.packageName) &&
            exitWall > previous.firstSeenWall &&
            input.elapsedNowMillis >= previous.firstSeenElapsed &&
            input.elapsedNowMillis - previous.firstSeenElapsed < 10_000
        ) listOf(RiskDecision.BriefVisit(previous, exitWall)) else emptyList()
        if (!input.queryContinuous || !input.foregroundFresh || riskPackage == null || input.sourceFocusSegmentId == null) {
            state = RiskCandidateState.Idle
            return brief
        }
        val current = when (val old = state) {
            is RiskCandidateState.Candidate -> old
            is RiskCandidateState.Confirmed -> old.candidate
            RiskCandidateState.Idle -> null
        }
        if (current != null && (current.sourceFocusSegmentId != input.sourceFocusSegmentId ||
                current.generation != input.generation)) {
            state = RiskCandidateState.Idle
            return emptyList()
        }
        if (current == null || current.packageName != riskPackage || input.elapsedNowMillis < current.firstSeenElapsed) {
            val firstWall = maxOf(packageObservation.sourceEventAtWallMillis,
                input.sourceFocusStartedAt ?: packageObservation.sourceEventAtWallMillis)
            val firstElapsed = if (input.wallNowMillis != null && firstWall <= input.wallNowMillis) {
                (input.elapsedNowMillis - (input.wallNowMillis - firstWall)).coerceIn(0, input.elapsedNowMillis)
            } else input.elapsedNowMillis
            state = RiskCandidateState.Candidate(nextToken++, riskPackage, firstElapsed,
                input.successfulQueryGeneration, input.sourceFocusSegmentId, firstWall, input.generation)
            return brief
        }
        if (state is RiskCandidateState.Confirmed) return emptyList()
        if (input.elapsedNowMillis - current.firstSeenElapsed >= 10_000 &&
            input.successfulQueryGeneration > current.firstQueryGeneration
        ) {
            state = RiskCandidateState.Confirmed(current)
            return listOf(RiskDecision.Confirmed(current.token, riskPackage, current.firstSeenElapsed,
                current.sourceFocusSegmentId, current.firstSeenWall, current.generation))
        }
        return emptyList()
    }
}
