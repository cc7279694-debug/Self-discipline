package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.model.ReadingSessionProjection
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingAnalyticsServiceTest {
    private val service = ReadingAnalyticsService()
    private val now = Instant.parse("2026-09-13T12:00:00Z")
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val time = AnalyticsTimeContext(now, shanghai)

    @Test
    fun `qualified sessions exclude abnormal active malformed and future records`() {
        val sessions = listOf(
            session("normal", endedAt = now.minusSeconds(60).toEpochMilli()),
            session("zero", startPage = 20, endPage = 20, endedAt = now.minusSeconds(120).toEpochMilli()),
            session("abnormal", endType = SessionEndType.ABNORMAL, endedAt = now.minusSeconds(180).toEpochMilli()),
            session("early", endType = SessionEndType.EARLY, endedAt = now.minusSeconds(181).toEpochMilli()),
            session("auto", endType = SessionEndType.AUTO, endedAt = now.minusSeconds(182).toEpochMilli()),
            session("start-incomplete", endType = SessionEndType.START_INCOMPLETE, endedAt = now.minusSeconds(183).toEpochMilli()),
            session("active", endedAt = null, endPage = null, endType = null),
            session("missing-page", endedAt = now.minusSeconds(190).toEpochMilli(), endPage = null),
            session("zero-duration", startedAt = 1_000L, endedAt = 1_000L),
            session("future", endedAt = now.plusSeconds(60).toEpochMilli()),
        )

        val qualified = service.qualify(sessions, time)

        assertEquals(listOf("normal", "zero"), qualified.map { it.sessionId })
        assertEquals(0L, qualified.last().pagesRead)
        assertEquals(Duration.ofMinutes(10), qualified.last().duration)
    }

    @Test
    fun `pages read never adds one and reverse ranges clamp only the derived value`() {
        val qualified = service.qualify(
            listOf(
                session("forward", startPage = 40, endPage = 45, endedAt = now.minusSeconds(60).toEpochMilli()),
                session("reverse", startPage = 40, endPage = 35, endedAt = now.minusSeconds(120).toEpochMilli()),
            ),
            time,
        )

        assertEquals(5L, qualified.first { it.sessionId == "forward" }.pagesRead)
        assertEquals(0L, qualified.first { it.sessionId == "reverse" }.pagesRead)
        assertEquals(35, qualified.first { it.sessionId == "reverse" }.endPage)
    }

    @Test
    fun `overall speed divides total pages by total duration rather than averaging session speeds`() {
        val sessions = listOf(
            session("fast", startPage = 1, endPage = 11, durationMinutes = 10, endedAt = now.minusSeconds(60).toEpochMilli()),
            session("slow", startPage = 11, endPage = 31, durationMinutes = 50, endedAt = now.minusSeconds(120).toEpochMilli()),
            session("zero", startPage = 31, endPage = 31, durationMinutes = 30, endedAt = now.minusSeconds(180).toEpochMilli()),
        )

        val window = service.selectAnalyticsWindow(sessions, time)!!

        assertEquals(7, window.days)
        assertEquals(3, window.sessions.size)
        assertEquals(30L, window.totalPagesRead)
        assertEquals(Duration.ofMinutes(90), window.totalDuration)
        assertEquals(20.0, window.overallPagesPerHour, 0.0001)
    }

    @Test
    fun `window expands from seven to fourteen and then thirty natural days`() {
        val sessions = listOf(
            sessionOnLocalDate("a", "2026-09-03"),
            sessionOnLocalDate("b", "2026-09-04"),
            sessionOnLocalDate("c", "2026-09-05"),
        )

        val window = service.selectAnalyticsWindow(sessions, time)

        assertEquals(14, window?.days)
        assertNull(service.selectAnalyticsWindow(sessions.map { it.copy(endedAt = localEnd("2026-08-01")) }, time))
    }

    @Test
    fun `window boundary and cross year span use inclusive local dates`() {
        val newYearTime = AnalyticsTimeContext(Instant.parse("2027-01-02T12:00:00Z"), shanghai)
        val sessions = listOf(
            sessionOnLocalDate("start", "2026-12-27"),
            sessionOnLocalDate("middle", "2027-01-01"),
            sessionOnLocalDate("end", "2027-01-02"),
            sessionOnLocalDate("outside", "2026-12-26"),
        )

        val window = service.selectAnalyticsWindow(sessions, newYearTime)!!

        assertEquals(7, window.days)
        assertEquals(setOf("start", "middle", "end"), window.sessions.map { it.sessionId }.toSet())
        assertEquals(7, window.inclusiveDataSpanDays)
    }

    @Test
    fun `seven day comparison uses adjacent non overlapping local date ranges`() {
        val sessions = listOf(
            sessionOnLocalDate("current-start", "2026-09-07", pages = 4),
            sessionOnLocalDate("current-end", "2026-09-13", pages = 6),
            sessionOnLocalDate("previous-start", "2026-08-31", pages = 2),
            sessionOnLocalDate("previous-end", "2026-09-06", pages = 3),
            sessionOnLocalDate("outside", "2026-08-30", pages = 99),
            sessionOnLocalDate("abnormal", "2026-09-13", pages = 99, endType = SessionEndType.ABNORMAL),
        )

        val result = service.buildSevenDayComparison(
            sessions = sessions,
            currentNoteCount = 5,
            previousNoteCount = 2,
            time = time,
        )

        assertEquals(2, result.current.sessionCount)
        assertEquals(10L, result.current.totalPagesRead)
        assertEquals(Duration.ofMinutes(20), result.current.totalDuration)
        assertEquals(5, result.current.noteCount)
        assertEquals(2, result.previous.sessionCount)
        assertEquals(5L, result.previous.totalPagesRead)
        assertEquals(2, result.previous.noteCount)
    }

    @Test
    fun `natural day membership follows local date across daylight saving transition`() {
        val newYork = ZoneId.of("America/New_York")
        val dstTime = AnalyticsTimeContext(
            Instant.parse("2026-03-09T03:30:00Z"),
            newYork,
        )
        val sessions = listOf(
            session("today", endedAt = Instant.parse("2026-03-09T03:00:00Z").toEpochMilli()),
            session("six-days", endedAt = java.time.LocalDate.parse("2026-03-02").atTime(12, 0).atZone(newYork).toInstant().toEpochMilli()),
            session("seven-days", endedAt = java.time.LocalDate.parse("2026-03-01").atTime(12, 0).atZone(newYork).toInstant().toEpochMilli()),
        )

        val comparison = service.buildSevenDayComparison(sessions, 0, 0, dstTime)

        assertEquals(2, comparison.current.sessionCount)
        assertEquals(1, comparison.previous.sessionCount)
    }

    private fun sessionOnLocalDate(
        id: String,
        date: String,
        pages: Int = 10,
        endType: SessionEndType = SessionEndType.NORMAL,
    ) = session(id, startPage = 1, endPage = 1 + pages, endedAt = localEnd(date), endType = endType)

    private fun localEnd(date: String): Long =
        java.time.LocalDate.parse(date).atTime(12, 0).atZone(shanghai).toInstant().toEpochMilli()

    private fun session(
        id: String,
        startPage: Int = 1,
        endPage: Int? = 11,
        durationMinutes: Long = 10,
        endedAt: Long? = 10_000L,
        startedAt: Long = endedAt?.minus(Duration.ofMinutes(durationMinutes).toMillis()) ?: 1_000L,
        endType: SessionEndType? = SessionEndType.NORMAL,
    ) = ReadingSessionProjection(
        sessionId = id,
        learningItemId = "item",
        startedAt = startedAt,
        endedAt = endedAt,
        startPage = startPage,
        endPage = endPage,
        endType = endType,
        noteCount = 0,
    )
}
