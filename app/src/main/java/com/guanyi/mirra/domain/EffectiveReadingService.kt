package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.model.EffectiveReadingSource
import java.math.BigInteger
import java.time.Duration
import java.time.Instant

class EffectiveReadingService(private val validator: SessionTimelineValidator = SessionTimelineValidator()) {
    fun qualify(source: EffectiveReadingSource, time: AnalyticsTimeContext): List<QualifiedEffectiveSession> =
        source.sessions.mapNotNull { session ->
            val endedAt = session.endedAt ?: return@mapNotNull null
            val endPage = session.endPage ?: return@mapNotNull null
            if (session.endType != SessionEndType.NORMAL || endedAt <= session.startedAt) return@mapNotNull null
            val ended = Instant.ofEpochMilli(endedAt)
            if (ended > time.now) return@mapNotNull null
            val analysis = validator.analyze(session, source.contexts[session.id], source.segments[session.id].orEmpty())
            val focusMillis = analysis.effectiveFocusMillis ?: return@mapNotNull null
            if (analysis.trust != TimelineTrust.COMPLETE_TRUSTED || focusMillis <= 0L) return@mapNotNull null
            QualifiedEffectiveSession(
                session.id, session.learningItemId, ended.atZone(time.zoneId).toLocalDate(),
                (endPage.toLong() - session.startPage.toLong()).coerceAtLeast(0L), focusMillis,
            )
        }

    /** Null means insufficient samples, never overflow. Arithmetic failures propagate to estimate. */
    fun selectWindow(
        sessions: List<QualifiedEffectiveSession>,
        time: AnalyticsTimeContext,
    ): SelectedEffectiveAnalyticsWindow? {
        val today = time.now.atZone(time.zoneId).toLocalDate()
        for (days in listOf(7, 14, 30)) {
            val start = today.minusDays(days - 1L)
            val samples = sessions.filter { it.effectiveFocusMillis > 0 && it.endedDate in start..today }
            if (samples.size < 3) continue
            val totalFocus = samples.fold(0L) { total, sample -> Math.addExact(total, sample.effectiveFocusMillis) }
            if (totalFocus < 1_800_000L) continue
            val totalPages = samples.fold(0L) { total, sample -> Math.addExact(total, sample.pagesRead) }
            val speed = totalPages.toDouble() * 3_600_000.0 / totalFocus.toDouble()
            if (!speed.isFinite()) throw ArithmeticException("Effective speed out of range")
            return SelectedEffectiveAnalyticsWindow(days, start, today, samples, totalPages, totalFocus, speed)
        }
        return null
    }

    /** Only presentation entry point; it does not derive a calendar completion date. */
    fun estimate(
        item: LearningItemEntity,
        source: EffectiveReadingSource,
        time: AnalyticsTimeContext,
    ): EffectiveReadingEstimate {
        val remainingPages = (item.totalPages.toLong() - item.currentPage.toLong()).coerceAtLeast(0L)
        var selected: SelectedEffectiveAnalyticsWindow? = null
        return try {
            selected = selectWindow(qualify(source, time).filter { it.learningItemId == item.id }, time)
            val window = selected ?: return EffectiveReadingEstimate(
                null, remainingPages, null, EffectiveEstimateUnavailableReason.INSUFFICIENT_WINDOW,
            )
            val futureTime = if (item.status == LearningItemStatus.IN_PROGRESS && item.totalPages > 0 &&
                remainingPages > 0 && window.effectivePagesPerHour > 0
            ) {
                // Ceil the exact ratio: a rounded Double speed can turn exactly 55 min into 56.
                // BigInteger keeps the intermediate product safe without a new dependency.
                val numerator = BigInteger.valueOf(remainingPages)
                    .multiply(BigInteger.valueOf(window.totalEffectiveFocusMillis))
                val denominator = BigInteger.valueOf(window.totalPagesRead).multiply(BigInteger.valueOf(60_000L))
                val minutes = numerator.add(denominator).subtract(BigInteger.ONE).divide(denominator)
                if (minutes.signum() <= 0 || minutes > BigInteger.valueOf(Long.MAX_VALUE)) {
                    throw ArithmeticException("Remaining effective time out of range")
                }
                Duration.ofMinutes(minutes.toLong())
            } else null
            EffectiveReadingEstimate(window, remainingPages, futureTime, null)
        } catch (_: ArithmeticException) {
            EffectiveReadingEstimate(selected, remainingPages, null, EffectiveEstimateUnavailableReason.ARITHMETIC_OUT_OF_RANGE)
        }
    }
}
