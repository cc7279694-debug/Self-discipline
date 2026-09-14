package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import java.time.DateTimeException
import java.time.Duration
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.sqrt

class CompletionPredictionService {
    fun predict(
        item: LearningItemEntity,
        window: SelectedAnalyticsWindow?,
        time: AnalyticsTimeContext,
    ): CompletionPrediction {
        val reasons = linkedSetOf<PredictionUnavailableReason>()
        val remainingPages = (item.totalPages.toLong() - item.currentPage.toLong()).coerceAtLeast(0L)
        val rawSpeed = window?.overallPagesPerHour
        val speed = rawSpeed?.takeIf { it.isFinite() && it > 0.0 && (window.totalPagesRead > 0L) }
        val calendarPace = window?.let { selected ->
            (selected.totalPagesRead.toDouble() / selected.days.toDouble()).takeIf { it.isFinite() && it > 0.0 }
        }

        if (item.status != LearningItemStatus.IN_PROGRESS) reasons += PredictionUnavailableReason.ITEM_NOT_IN_PROGRESS
        if (item.totalPages <= 0) reasons += PredictionUnavailableReason.INVALID_TOTAL_PAGES
        if (remainingPages == 0L && item.totalPages > 0) reasons += PredictionUnavailableReason.ALREADY_AT_LAST_PAGE
        if (window == null) reasons += PredictionUnavailableReason.INSUFFICIENT_WINDOW
        if (window != null && speed == null) reasons += PredictionUnavailableReason.ZERO_READING_SPEED

        val canPredictFuture = item.status == LearningItemStatus.IN_PROGRESS && item.totalPages > 0 && remainingPages > 0L
        val remainingTime = if (canPredictFuture && speed != null) {
            durationFor(remainingPages, speed, reasons)
        } else null

        var confidence: PredictionConfidence? = null
        var dateRange: CompletionDateRange? = null
        if (canPredictFuture && window != null && speed != null && calendarPace != null) {
            if (window.sessions.size < 5) reasons += PredictionUnavailableReason.INSUFFICIENT_SESSIONS
            if (window.readingDays < 3) reasons += PredictionUnavailableReason.INSUFFICIENT_READING_DAYS
            if (window.inclusiveDataSpanDays < 7) reasons += PredictionUnavailableReason.INSUFFICIENT_SPAN

            val today = time.now.atZone(time.zoneId).toLocalDate()
            val latestDate = window.sessions.maxOfOrNull { it.endedDate }
            val latestAgeDays = latestDate?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today) }
            if (latestAgeDays == null || latestAgeDays > 13L) reasons += PredictionUnavailableReason.NO_RECENT_READING

            val robustCv = robustCoefficientOfVariation(window.sessions.map(::sessionSpeed))
            if (!robustCv.isFinite() || robustCv > MAX_ROBUST_CV) {
                reasons += PredictionUnavailableReason.EXCESSIVE_VARIABILITY
            }

            val hardGateReasons = setOf(
                PredictionUnavailableReason.INSUFFICIENT_SESSIONS,
                PredictionUnavailableReason.INSUFFICIENT_READING_DAYS,
                PredictionUnavailableReason.INSUFFICIENT_SPAN,
                PredictionUnavailableReason.NO_RECENT_READING,
                PredictionUnavailableReason.EXCESSIVE_VARIABILITY,
            )
            if (reasons.none { it in hardGateReasons }) {
                confidence = confidenceFor(window, robustCv, latestAgeDays ?: Long.MAX_VALUE)
                if (confidence == PredictionConfidence.LOW) {
                    reasons += PredictionUnavailableReason.LOW_CONFIDENCE
                } else {
                    dateRange = completionRange(today, remainingPages, calendarPace, confidence, reasons)
                }
            }
        }

        return CompletionPrediction(
            remainingPages = remainingPages,
            overallPagesPerHour = speed,
            estimatedRemainingReadingTime = remainingTime,
            calendarPagesPerDay = calendarPace,
            naturalCompletionRange = dateRange,
            confidence = confidence,
            unavailableReasons = reasons,
            sourceWindowDays = window?.days,
            sourceSessionCount = window?.sessions?.size ?: 0,
        )
    }

    private fun durationFor(
        remainingPages: Long,
        pagesPerHour: Double,
        reasons: MutableSet<PredictionUnavailableReason>,
    ): Duration? {
        val minutes = ceil(remainingPages.toDouble() / pagesPerHour * 60.0)
        if (!minutes.isFinite() || minutes <= 0.0 || minutes > Long.MAX_VALUE.toDouble()) {
            reasons += PredictionUnavailableReason.ARITHMETIC_OUT_OF_RANGE
            return null
        }
        return runCatching { Duration.ofMinutes(minutes.toLong()) }.getOrElse {
            reasons += PredictionUnavailableReason.ARITHMETIC_OUT_OF_RANGE
            null
        }
    }

    private fun completionRange(
        today: LocalDate,
        remainingPages: Long,
        calendarPace: Double,
        confidence: PredictionConfidence,
        reasons: MutableSet<PredictionUnavailableReason>,
    ): CompletionDateRange? {
        val center = ceil(remainingPages.toDouble() / calendarPace)
        if (!center.isFinite() || center <= 0.0 || center > Long.MAX_VALUE.toDouble()) {
            reasons += PredictionUnavailableReason.ARITHMETIC_OUT_OF_RANGE
            return null
        }
        val centerDays = center.toLong()
        val margin = when (confidence) {
            PredictionConfidence.HIGH -> ceil(center * 0.10).toLong().coerceAtLeast(1L)
            PredictionConfidence.MEDIUM -> ceil(center * 0.20).toLong().coerceAtLeast(2L)
            PredictionConfidence.LOW -> return null
        }
        return try {
            CompletionDateRange(
                earliest = today.plusDays((centerDays - margin).coerceAtLeast(1L)),
                latest = today.plusDays(Math.addExact(centerDays, margin)),
            )
        } catch (_: DateTimeException) {
            reasons += PredictionUnavailableReason.ARITHMETIC_OUT_OF_RANGE
            null
        } catch (_: ArithmeticException) {
            reasons += PredictionUnavailableReason.ARITHMETIC_OUT_OF_RANGE
            null
        }
    }

    private fun confidenceFor(
        window: SelectedAnalyticsWindow,
        robustCv: Double,
        latestAgeDays: Long,
    ): PredictionConfidence {
        val score = sessionScore(window.sessions.size) +
            readingDayScore(window.readingDays) +
            spanScore(window.inclusiveDataSpanDays) +
            variabilityScore(robustCv) +
            freshnessScore(latestAgeDays)
        return when {
            score >= 11 -> PredictionConfidence.HIGH
            score >= 7 -> PredictionConfidence.MEDIUM
            else -> PredictionConfidence.LOW
        }
    }

    private fun sessionScore(count: Int) = when {
        count >= 12 -> 3
        count >= 8 -> 2
        count >= 5 -> 1
        else -> 0
    }

    private fun readingDayScore(count: Int) = when {
        count >= 8 -> 3
        count >= 5 -> 2
        count >= 3 -> 1
        else -> 0
    }

    private fun spanScore(days: Int) = when {
        days >= 21 -> 3
        days >= 14 -> 2
        days >= 7 -> 1
        else -> 0
    }

    private fun variabilityScore(cv: Double) = when {
        cv <= 0.25 -> 3
        cv <= 0.50 -> 2
        cv <= MAX_ROBUST_CV -> 1
        else -> 0
    }

    private fun freshnessScore(days: Long) = when {
        days <= 3 -> 2
        days <= 7 -> 1
        else -> 0
    }

    private fun sessionSpeed(session: QualifiedSession): Double =
        session.pagesRead.toDouble() * 3_600_000.0 / session.duration.toMillis().toDouble()

    private fun robustCoefficientOfVariation(speeds: List<Double>): Double {
        if (speeds.size < 2 || speeds.any { !it.isFinite() || it < 0.0 }) return Double.POSITIVE_INFINITY
        return speeds.indices.minOf { removedIndex ->
            val sample = speeds.filterIndexed { index, _ -> index != removedIndex }
            val mean = sample.average()
            if (mean <= 0.0 || !mean.isFinite()) return@minOf Double.POSITIVE_INFINITY
            val variance = sample.sumOf { value ->
                val delta = value - mean
                delta * delta
            } / sample.size.toDouble()
            (sqrt(variance) / mean).takeIf(Double::isFinite) ?: Double.POSITIVE_INFINITY
        }
    }

    private companion object {
        const val MAX_ROBUST_CV = 0.75
    }
}
