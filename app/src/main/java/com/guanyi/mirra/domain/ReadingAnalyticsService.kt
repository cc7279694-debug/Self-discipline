package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.model.ReadingSessionProjection
import java.time.Duration
import java.time.LocalDate

class ReadingAnalyticsService {
    fun qualify(
        sessions: List<ReadingSessionProjection>,
        time: AnalyticsTimeContext,
    ): List<QualifiedSession> = sessions.mapNotNull { session ->
        if (session.endType != SessionEndType.NORMAL) return@mapNotNull null
        val endedAtMillis = session.endedAt ?: return@mapNotNull null
        val endPage = session.endPage ?: return@mapNotNull null
        if (endedAtMillis <= session.startedAt) return@mapNotNull null
        val startedAt = java.time.Instant.ofEpochMilli(session.startedAt)
        val endedAt = java.time.Instant.ofEpochMilli(endedAtMillis)
        if (endedAt > time.now) return@mapNotNull null
        QualifiedSession(
            sessionId = session.sessionId,
            learningItemId = session.learningItemId,
            endedDate = endedAt.atZone(time.zoneId).toLocalDate(),
            startedAt = startedAt,
            endedAt = endedAt,
            startPage = session.startPage,
            endPage = endPage,
            duration = Duration.between(startedAt, endedAt),
            pagesRead = (endPage.toLong() - session.startPage.toLong()).coerceAtLeast(0L),
            noteCount = session.noteCount,
        )
    }.sortedWith(compareByDescending<QualifiedSession> { it.endedAt }.thenByDescending { it.sessionId })

    fun selectAnalyticsWindow(
        sessions: List<ReadingSessionProjection>,
        time: AnalyticsTimeContext,
    ): SelectedAnalyticsWindow? {
        val today = time.now.atZone(time.zoneId).toLocalDate()
        val qualified = qualify(sessions, time)
        return listOf(7, 14, 30).firstNotNullOfOrNull { days ->
            val startDate = today.minusDays(days - 1L)
            val samples = qualified.filter { it.endedDate in startDate..today }
            val totalDuration = samples.fold(Duration.ZERO) { total, session -> total.plus(session.duration) }
            if (samples.size < 3 || totalDuration < Duration.ofMinutes(30)) return@firstNotNullOfOrNull null
            val totalPages = samples.fold(0L) { total, session -> safeAdd(total, session.pagesRead) }
            val dates = samples.map { it.endedDate }
            SelectedAnalyticsWindow(
                days = days,
                startDate = startDate,
                endDate = today,
                sessions = samples,
                totalDuration = totalDuration,
                totalPagesRead = totalPages,
                readingDays = dates.distinct().size,
                inclusiveDataSpanDays = (java.time.temporal.ChronoUnit.DAYS.between(dates.min(), dates.max()) + 1L)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                overallPagesPerHour = totalPages * MILLIS_PER_HOUR / totalDuration.toMillis(),
            )
        }
    }

    fun buildSevenDayComparison(
        sessions: List<ReadingSessionProjection>,
        currentNoteCount: Int,
        previousNoteCount: Int,
        time: AnalyticsTimeContext,
    ): SevenDayComparison {
        val today = time.now.atZone(time.zoneId).toLocalDate()
        val currentStart = today.minusDays(6)
        val previousStart = today.minusDays(13)
        val qualified = qualify(sessions, time)
        return SevenDayComparison(
            current = summarize(qualified.filter { it.endedDate in currentStart..today }, currentNoteCount),
            previous = summarize(qualified.filter { it.endedDate >= previousStart && it.endedDate < currentStart }, previousNoteCount),
        )
    }

    private fun summarize(sessions: List<QualifiedSession>, noteCount: Int): ReadingPeriodSummary =
        ReadingPeriodSummary(
            sessionCount = sessions.size,
            totalDuration = sessions.fold(Duration.ZERO) { total, session -> total.plus(session.duration) },
            totalPagesRead = sessions.fold(0L) { total, session -> safeAdd(total, session.pagesRead) },
            noteCount = noteCount,
        )

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private companion object {
        const val MILLIS_PER_HOUR = 3_600_000.0
    }
}
