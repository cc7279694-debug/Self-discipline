package com.guanyi.mirra.domain.trends

import com.guanyi.mirra.data.local.entity.FocusEventEntity
import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.domain.AnalyticsTimeContext
import java.time.LocalDate

enum class TrendsRange(val days: Int?) {
    SEVEN_DAYS(7), THIRTY_DAYS(30), NINETY_DAYS(90), ALL(null),
}

/** Current includes snapshot.now; previous excludes the current window's first instant. */
data class TrendsWindow(
    val startInclusive: Long?,
    val endInclusive: Long,
    val endExclusive: Long? = null,
)

data class TrendsWindows(val current: TrendsWindow, val previous: TrendsWindow?)

data class StartTrendSource(
    val intent: StudyIntentEntity,
    val linkedSessions: List<StudySessionEntity>,
)

data class SessionTrendSource(
    val session: StudySessionEntity,
    val context: SessionFocusContextEntity?,
    val segments: List<SessionSegmentEntity>,
    val events: List<FocusEventEntity>,
)

data class TrendFraction(val numerator: Long, val denominator: Long, val value: Double?)

data class StartTrends(
    val convertedCount: Long,
    val abandonedCount: Long,
    val timeoutCount: Long,
    val openCount: Long,
    val conversion: TrendFraction,
    val startLatencySampleCount: Long,
    val medianStartLatencyMillis: Double?,
    val startDataIssueCount: Long,
    val stableConfirmedCount: Long,
    val stableUnconfirmedCount: Long,
    val stableDataIssueCount: Long,
    val medianStableLatencyMillis: Double?,
)

data class MaintainTrends(
    val endedSessionCount: Long,
    val trustedSessionCount: Long,
    val unavailableSessionCount: Long,
    val effectiveFocusMillis: Long?,
    val deepFocusMillis: Long?,
    val distractionCount: Long,
    val distractionMillis: Long?,
    val durationOverflow: Boolean,
)

data class RecoverTrends(
    val attemptCount: Long,
    val successCount: Long,
    val interruptedCount: Long,
    val unknownCount: Long,
    val plannedReturnCount: Long,
    val invalidOriginCount: Long,
    val knownOutcomeSuccess: TrendFraction,
    val medianSuccessfulRecoveryMillis: Double?,
)

data class TrendsPeriod(val start: StartTrends, val maintain: MaintainTrends, val recover: RecoverTrends)

/**
 * Whole duration of qualified normal reading sessions, separate from trusted effective focus.
 * Empty periods are 0; only damaged normal samples or arithmetic overflow are unavailable (null).
 * When valid and damaged samples coexist, the duration is the qualified subtotal and issues remain explicit.
 */
data class ReadingDurationTrends(
    val sessionCount: Long = 0,
    val totalDurationMillis: Long? = 0,
    val dataIssueCount: Long = 0,
    val durationOverflow: Boolean = false,
)

/** Start uses Intent.createdAt; reading/Maintain/Recover use Session.endedAt in the snapshot timezone. */
data class DailyTrendsPoint(
    val date: LocalDate,
    val normalReading: ReadingDurationTrends,
    val period: TrendsPeriod,
)

/** Rate changes are percentage points; counts and durations are absolute changes. */
data class TrendsComparison(
    val convertedCountDelta: Long,
    val resolvedIntentCountDelta: Long,
    val conversionPercentagePointDelta: Double?,
    val stableConfirmedCountDelta: Long,
    val trustedSessionCountDelta: Long,
    val unavailableSessionCountDelta: Long,
    val effectiveFocusMillisDelta: Long?,
    val deepFocusMillisDelta: Long?,
    val distractionCountDelta: Long,
    val recoveryAttemptCountDelta: Long,
    val recoverySuccessCountDelta: Long,
    val recoveryUnknownCountDelta: Long,
)

data class TrendsSnapshot(
    val range: TrendsRange,
    val time: AnalyticsTimeContext,
    val windows: TrendsWindows,
    val current: TrendsPeriod,
    val previous: TrendsPeriod?,
    val comparison: TrendsComparison?,
    val normalReading: ReadingDurationTrends = ReadingDurationTrends(),
    val previousNormalReading: ReadingDurationTrends? = null,
    /** Fixed ranges include every local day; ALL includes only recorded dates, sorted ascending. */
    val daily: List<DailyTrendsPoint> = emptyList(),
)
