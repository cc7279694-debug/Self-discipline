package com.guanyi.mirra.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class QualifiedSession(
    val sessionId: String,
    val learningItemId: String,
    val endedDate: LocalDate,
    val startedAt: Instant,
    val endedAt: Instant,
    val startPage: Int,
    val endPage: Int,
    val duration: Duration,
    val pagesRead: Long,
    val noteCount: Int,
)

data class SelectedAnalyticsWindow(
    val days: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val sessions: List<QualifiedSession>,
    val totalDuration: Duration,
    val totalPagesRead: Long,
    val readingDays: Int,
    val inclusiveDataSpanDays: Int,
    val overallPagesPerHour: Double,
)

data class ReadingPeriodSummary(
    val sessionCount: Int,
    val totalDuration: Duration,
    val totalPagesRead: Long,
    val noteCount: Int,
)

data class SevenDayComparison(
    val current: ReadingPeriodSummary,
    val previous: ReadingPeriodSummary,
)

enum class PredictionConfidence { HIGH, MEDIUM, LOW }

enum class PredictionUnavailableReason {
    ITEM_NOT_IN_PROGRESS,
    INVALID_TOTAL_PAGES,
    ALREADY_AT_LAST_PAGE,
    INSUFFICIENT_WINDOW,
    ZERO_READING_SPEED,
    INSUFFICIENT_SESSIONS,
    INSUFFICIENT_READING_DAYS,
    INSUFFICIENT_SPAN,
    NO_RECENT_READING,
    EXCESSIVE_VARIABILITY,
    LOW_CONFIDENCE,
    ARITHMETIC_OUT_OF_RANGE,
}

data class CompletionDateRange(
    val earliest: LocalDate,
    val latest: LocalDate,
)

data class CompletionPrediction(
    val remainingPages: Long,
    val overallPagesPerHour: Double?,
    val estimatedRemainingReadingTime: Duration?,
    val calendarPagesPerDay: Double?,
    val naturalCompletionRange: CompletionDateRange?,
    val confidence: PredictionConfidence?,
    val unavailableReasons: Set<PredictionUnavailableReason>,
    val sourceWindowDays: Int?,
    val sourceSessionCount: Int,
)
