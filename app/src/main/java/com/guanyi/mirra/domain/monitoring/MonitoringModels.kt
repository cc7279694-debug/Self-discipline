package com.guanyi.mirra.domain.monitoring

/** Wall time is for UsageEvent queries; elapsed time is for durations within one boot. */
data class ClockSample(val wallNowMillis: Long, val elapsedNowMillis: Long)

enum class UsageEventKind { ACTIVITY_RESUMED, ACTIVITY_PAUSED, ACTIVITY_STOPPED, SCREEN_OFF, DEVICE_LOCKED }

data class UsageEventFact(
    val eventWallMillis: Long,
    val kind: UsageEventKind,
    val packageName: String? = null,
    val className: String? = null,
)

sealed interface QueryResult {
    val clock: ClockSample

    data class Success(
        val windowStartWallMillis: Long,
        val windowEndWallMillis: Long,
        override val clock: ClockSample,
        val events: List<UsageEventFact>,
    ) : QueryResult

    data class Failure(override val clock: ClockSample, val reason: QueryFailureReason) : QueryResult
}

enum class QueryFailureReason { QUERY_FAILED, PERMISSION_LOST, DIRECT_BOOT_LOCKED }

sealed interface ForegroundObservation {
    data class Package(
        val name: String,
        val observedAtElapsedMillis: Long,
        val sourceEventAtWallMillis: Long,
    ) : ForegroundObservation

    data class ScreenOff(val observedAtElapsedMillis: Long) : ForegroundObservation
    data class DeviceLocked(val observedAtElapsedMillis: Long) : ForegroundObservation
    data class Unknown(val reason: String) : ForegroundObservation
    data class MonitoringUnavailable(val reason: String) : ForegroundObservation
}

enum class MonitoringSignal { GAP, WALL_CLOCK_JUMP, RESET_WALL_CURSOR }

data class ObservationState(
    val observation: ForegroundObservation = ForegroundObservation.Unknown("no foreground evidence"),
    val lastSuccessfulQueryElapsed: Long? = null,
    val lastForegroundEvidenceElapsed: Long? = null,
    val cursorWallMillis: Long? = null,
    val lastClock: ClockSample? = null,
    val lastEventWallMillis: Long? = null,
    val lastEventKeys: Set<UsageEventFact> = emptySet(),
    val cursorNeedsRebaseline: Boolean = false,
    val rebaselineAtWallMillis: Long? = null,
)

data class ObservationReduction(val state: ObservationState, val signals: Set<MonitoringSignal> = emptySet())

enum class PackageClass { RISK, NORMAL, SYSTEM }

data class CandidateInput(
    val observation: ForegroundObservation,
    val elapsedNowMillis: Long,
    /** Advances only after a successful, continuous query; ticks must reuse the last value. */
    val successfulQueryGeneration: Long,
    val sourceFocusSegmentId: String?,
    val queryContinuous: Boolean,
    val foregroundFresh: Boolean,
)

sealed interface RiskCandidateState {
    data object Idle : RiskCandidateState
    data class Candidate(
        val token: Long,
        val packageName: String,
        val firstSeenElapsed: Long,
        val firstQueryGeneration: Long,
        val sourceFocusSegmentId: String,
    ) : RiskCandidateState
    data class Confirmed(val candidate: Candidate) : RiskCandidateState
}

sealed interface RiskDecision {
    data class Confirmed(
        val token: Long,
        val packageName: String,
        val firstSeenElapsed: Long,
        val sourceFocusSegmentId: String,
    ) : RiskDecision
}
