package com.guanyi.mirra.domain.monitoring

/** Domain-only risk classification; Android package discovery belongs to a later task. */
class CandidateRiskAppMachine(private val classify: (String) -> PackageClass) {
    var state: RiskCandidateState = RiskCandidateState.Idle
        private set
    private var nextToken = 1L

    fun accept(input: CandidateInput): List<RiskDecision> {
        val packageObservation = input.observation as? ForegroundObservation.Package
        val riskPackage = packageObservation?.name?.takeIf { classify(it) == PackageClass.RISK }
        if (!input.queryContinuous || !input.foregroundFresh || riskPackage == null || input.sourceFocusSegmentId == null) {
            state = RiskCandidateState.Idle
            return emptyList()
        }
        val current = when (val old = state) {
            is RiskCandidateState.Candidate -> old
            is RiskCandidateState.Confirmed -> old.candidate
            RiskCandidateState.Idle -> null
        }
        if (current != null && current.sourceFocusSegmentId != input.sourceFocusSegmentId) {
            state = RiskCandidateState.Idle
            return emptyList()
        }
        if (current == null || current.packageName != riskPackage || input.elapsedNowMillis < current.firstSeenElapsed) {
            state = RiskCandidateState.Candidate(nextToken++, riskPackage, input.elapsedNowMillis,
                input.successfulQueryGeneration, input.sourceFocusSegmentId)
            return emptyList()
        }
        if (state is RiskCandidateState.Confirmed) return emptyList()
        if (input.elapsedNowMillis - current.firstSeenElapsed >= 10_000 &&
            input.successfulQueryGeneration > current.firstQueryGeneration
        ) {
            state = RiskCandidateState.Confirmed(current)
            return listOf(RiskDecision.Confirmed(current.token, riskPackage, current.firstSeenElapsed,
                current.sourceFocusSegmentId))
        }
        return emptyList()
    }
}
