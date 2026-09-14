package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionPredictionServiceTest {
    private val service = CompletionPredictionService()
    private val time = AnalyticsTimeContext(
        now = Instant.parse("2026-09-13T12:00:00Z"),
        zoneId = ZoneId.of("Asia/Shanghai"),
    )

    @Test
    fun `prediction separates remaining reading time from calendar completion pace`() {
        val prediction = service.predict(
            item = item(currentPage = 200, totalPages = 320),
            window = window(
                days = 14,
                speeds = listOf(20.0, 20.0, 20.0, 20.0, 20.0, 20.0, 20.0, 20.0),
                totalPages = 140,
                totalDuration = Duration.ofHours(7),
                readingDays = 5,
                spanDays = 14,
            ),
            time = time,
        )

        assertEquals(120L, prediction.remainingPages)
        assertEquals(20.0, prediction.overallPagesPerHour!!, 0.0001)
        assertEquals(Duration.ofHours(6), prediction.estimatedRemainingReadingTime)
        assertEquals(10.0, prediction.calendarPagesPerDay!!, 0.0001)
        assertEquals(PredictionConfidence.HIGH, prediction.confidence)
        assertEquals(LocalDate.parse("2026-09-23"), prediction.naturalCompletionRange?.earliest)
        assertEquals(LocalDate.parse("2026-09-27"), prediction.naturalCompletionRange?.latest)
    }

    @Test
    fun `eligibility gates run before confidence`() {
        val prediction = service.predict(
            item = item(),
            window = window(
                days = 7,
                speeds = listOf(20.0, 20.0, 20.0, 20.0, 20.0),
                totalPages = 70,
                totalDuration = Duration.ofHours(3),
                readingDays = 2,
                spanDays = 7,
            ),
            time = time,
        )

        assertNull(prediction.confidence)
        assertNull(prediction.naturalCompletionRange)
        assertTrue(PredictionUnavailableReason.INSUFFICIENT_READING_DAYS in prediction.unavailableReasons)
    }

    @Test
    fun `one extreme or one zero speed is tolerated but sustained variation is rejected`() {
        val oneExtreme = service.predict(item(), window(speeds = listOf(20.0, 20.0, 20.0, 20.0, 100.0)), time)
        val oneZero = service.predict(item(), window(speeds = listOf(0.0, 20.0, 20.0, 20.0, 20.0)), time)
        val dispersed = service.predict(item(), window(speeds = listOf(1.0, 1.0, 1.0, 50.0, 50.0, 50.0)), time)

        assertFalse(PredictionUnavailableReason.EXCESSIVE_VARIABILITY in oneExtreme.unavailableReasons)
        assertFalse(PredictionUnavailableReason.EXCESSIVE_VARIABILITY in oneZero.unavailableReasons)
        assertTrue(PredictionUnavailableReason.EXCESSIVE_VARIABILITY in dispersed.unavailableReasons)
        assertNull(dispersed.naturalCompletionRange)
    }

    @Test
    fun `all zero page sessions produce no speed or predictions`() {
        val prediction = service.predict(
            item(),
            window(
                speeds = listOf(0.0, 0.0, 0.0, 0.0, 0.0),
                totalPages = 0,
                totalDuration = Duration.ofHours(2),
            ),
            time,
        )

        assertNull(prediction.overallPagesPerHour)
        assertNull(prediction.estimatedRemainingReadingTime)
        assertNull(prediction.naturalCompletionRange)
        assertTrue(PredictionUnavailableReason.ZERO_READING_SPEED in prediction.unavailableReasons)
    }

    @Test
    fun `zero dominated mixed speeds are high variation without non finite outputs`() {
        val prediction = service.predict(
            item(),
            window(
                speeds = listOf(0.0, 0.0, 0.0, 20.0, 20.0),
                totalPages = 40,
                totalDuration = Duration.ofHours(5),
            ),
            time,
        )

        assertTrue(PredictionUnavailableReason.EXCESSIVE_VARIABILITY in prediction.unavailableReasons)
        assertNull(prediction.naturalCompletionRange)
        assertTrue(prediction.overallPagesPerHour?.isFinite() == true)
        assertTrue(prediction.calendarPagesPerDay?.isFinite() == true)
    }

    @Test
    fun `paused and completed items retain facts but never receive future predictions`() {
        listOf(LearningItemStatus.PAUSED, LearningItemStatus.COMPLETED).forEach { status ->
            val prediction = service.predict(item(status = status), window(), time)

            assertEquals(70.0 / 3.0, prediction.overallPagesPerHour!!, 0.0001)
            assertNull(prediction.estimatedRemainingReadingTime)
            assertNull(prediction.naturalCompletionRange)
            assertTrue(PredictionUnavailableReason.ITEM_NOT_IN_PROGRESS in prediction.unavailableReasons)
        }
    }

    @Test
    fun `medium confidence produces twenty percent date range and low hides the date`() {
        val medium = service.predict(
            item(currentPage = 220, totalPages = 320),
            window(
                days = 14,
                speeds = listOf(18.0, 20.0, 22.0, 19.0, 21.0, 20.0, 19.0, 21.0),
                totalPages = 70,
                totalDuration = Duration.ofHours(4),
                readingDays = 5,
                spanDays = 14,
                latestReadingDaysAgo = 5,
            ),
            time,
        )
        val low = service.predict(
            item(),
            window(
                days = 7,
                speeds = listOf(10.0, 20.0, 30.0, 20.0, 10.0),
                totalPages = 35,
                totalDuration = Duration.ofHours(3),
                readingDays = 3,
                spanDays = 7,
                latestReadingDaysAgo = 10,
            ),
            time,
        )

        assertEquals(PredictionConfidence.MEDIUM, medium.confidence)
        assertEquals(LocalDate.parse("2026-09-29"), medium.naturalCompletionRange?.earliest)
        assertEquals(LocalDate.parse("2026-10-07"), medium.naturalCompletionRange?.latest)
        assertEquals(PredictionConfidence.LOW, low.confidence)
        assertNull(low.naturalCompletionRange)
        assertTrue(PredictionUnavailableReason.LOW_CONFIDENCE in low.unavailableReasons)
    }

    private fun item(
        status: LearningItemStatus = LearningItemStatus.IN_PROGRESS,
        currentPage: Int = 100,
        totalPages: Int = 200,
    ) = LearningItemEntity(
        id = "item",
        name = "Book",
        status = status,
        totalPages = totalPages,
        currentPage = currentPage,
        mainlineSlot = null,
        firstAction = "",
        createdAt = 1L,
        updatedAt = 1L,
        completedAt = if (status == LearningItemStatus.COMPLETED) 2L else null,
    )

    private fun window(
        days: Int = 7,
        speeds: List<Double> = listOf(18.0, 20.0, 22.0, 19.0, 21.0),
        totalPages: Long = 70,
        totalDuration: Duration = Duration.ofHours(3),
        readingDays: Int = 3,
        spanDays: Int = 7,
        latestReadingDaysAgo: Long = 0,
    ): SelectedAnalyticsWindow {
        val today = LocalDate.of(2026, 9, 13)
        val sessions = speeds.mapIndexed { index, speed ->
            val endedDate = when {
                index == 0 -> today.minusDays(latestReadingDaysAgo)
                index == speeds.lastIndex -> today.minusDays(latestReadingDaysAgo + (spanDays - 1).toLong())
                else -> today.minusDays(latestReadingDaysAgo + (index % readingDays).toLong())
            }
            QualifiedSession(
                sessionId = "s$index",
                learningItemId = "item",
                endedDate = endedDate,
                startedAt = endedDate.atStartOfDay(time.zoneId).toInstant(),
                endedAt = endedDate.atStartOfDay(time.zoneId).plusHours(1).toInstant(),
                startPage = 1,
                endPage = 2,
                duration = Duration.ofHours(1),
                pagesRead = if (speed == 0.0) 0 else speed.toLong(),
                noteCount = 0,
            )
        }
        return SelectedAnalyticsWindow(
            days = days,
            startDate = today.minusDays(days - 1L),
            endDate = today,
            sessions = sessions,
            totalDuration = totalDuration,
            totalPagesRead = totalPages,
            readingDays = readingDays,
            inclusiveDataSpanDays = spanDays,
            overallPagesPerHour = totalPages * 3_600_000.0 / totalDuration.toMillis(),
        )
    }
}
