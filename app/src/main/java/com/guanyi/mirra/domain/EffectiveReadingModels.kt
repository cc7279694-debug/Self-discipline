package com.guanyi.mirra.domain

import java.time.Duration
import java.time.LocalDate

data class QualifiedEffectiveSession(
    val sessionId: String,
    val learningItemId: String,
    val endedDate: LocalDate,
    val pagesRead: Long,
    val effectiveFocusMillis: Long,
)

data class SelectedEffectiveAnalyticsWindow(
    val days: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val sessions: List<QualifiedEffectiveSession>,
    val totalPagesRead: Long,
    val totalEffectiveFocusMillis: Long,
    val effectivePagesPerHour: Double,
)

enum class EffectiveEstimateUnavailableReason { INSUFFICIENT_WINDOW, ARITHMETIC_OUT_OF_RANGE }

data class EffectiveReadingEstimate(
    val window: SelectedEffectiveAnalyticsWindow?,
    val remainingPages: Long,
    val remainingEffectiveReadingTime: Duration?,
    val unavailableReason: EffectiveEstimateUnavailableReason?,
)
