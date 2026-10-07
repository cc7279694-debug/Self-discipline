package com.guanyi.mirra.domain.insights

import java.time.Instant

enum class ReadingPaceInsightDirection { SLOWER, FASTER }

enum class ReadingPaceInsightUnavailableReason {
    INSUFFICIENT_RECENT_DATA, STALE_RECENT_DATA, INSUFFICIENT_BASELINE,
    NO_NOTABLE_CHANGE, INVALID_ASSOCIATION, INVALID_TIMESTAMP, ARITHMETIC_OUT_OF_RANGE,
}

data class ReadingPaceWindowEvidence(
    val sessionIds: List<String>,
    val totalPagesRead: Long,
    val totalEffectiveFocusMillis: Long,
    val effectivePagesPerHour: Double,
) {
    val sampleCount: Int get() = sessionIds.size
}

data class ReadingPaceInsight(
    val direction: ReadingPaceInsightDirection,
    val ratio: Double,
    val recent: ReadingPaceWindowEvidence,
    val baseline: ReadingPaceWindowEvidence,
    val roundedChangePercent: Int,
    val changeExceeds100Percent: Boolean,
)

data class ReadingPaceInsightResult(
    val insight: ReadingPaceInsight?,
    val unavailableReason: ReadingPaceInsightUnavailableReason?,
)

/** Timestamp companion of frozen effective qualification; never a persisted fact. */
internal data class ReadingPaceSample(
    val sessionId: String,
    val learningItemId: String,
    val endedAt: Instant,
    val pagesRead: Long,
    val effectiveFocusMillis: Long,
)
