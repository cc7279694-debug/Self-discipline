package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.local.model.EffectiveReadingSource
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class EffectiveReadingServiceTest {
    private val service = EffectiveReadingService()
    private val time = AnalyticsTimeContext(Instant.parse("2026-09-13T12:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val today = time.now.atZone(time.zoneId).toLocalDate()

    @Test fun weightedSpeedUsesTotalPagesOverTotalFocus() {
        val source = source(row("a", 10, 10), row("b", 5, 25), row("c", 5, 25))
        val window = service.estimate(item(), source, time).window!!
        assertEquals(3, window.sessions.size)
        assertEquals(20L, window.totalPagesRead)
        assertEquals(3_600_000L, window.totalEffectiveFocusMillis)
        assertEquals(20.0, window.effectivePagesPerHour, 0.0)
        assertNotEquals(28.0, window.effectivePagesPerHour, 0.0)
    }

    @Test fun zeroPagePositiveFocusRetainsDenominator() {
        val source = source(row("a", 30, 30), row("b", 0, 15), row("c", 0, 15))
        val window = service.estimate(item(), source, time).window!!
        assertEquals(3, window.sessions.size)
        assertEquals(3_600_000L, window.totalEffectiveFocusMillis)
        assertEquals(30.0, window.effectivePagesPerHour, 0.0)
    }

    @Test fun trustedZeroFocusIsExcludedOnlyFromVelocitySamples() {
        val zero = row("zero", 0, 30, type = SessionSegmentType.BREAK)
        val source = source(zero, row("a", 10, 30), row("b", 10, 30))
        val analysis = SessionTimelineValidator().analyze(zero.first, source.contexts["zero"], zero.second)
        assertEquals(TimelineTrust.COMPLETE_TRUSTED, analysis.trust)
        assertEquals(0L, analysis.effectiveFocusMillis)
        assertEquals(2, service.qualify(source, time).size)
        assertEquals(EffectiveEstimateUnavailableReason.INSUFFICIENT_WINDOW, service.estimate(item(), source, time).unavailableReason)
    }

    @Test fun allZeroPagesKeepZeroSpeedWithoutRemainingTime() {
        val result = service.estimate(item(), source(row("a", 0, 10), row("b", 0, 10), row("c", 0, 10)), time)
        assertEquals(0.0, result.window!!.effectivePagesPerHour, 0.0)
        assertTrue(result.window.effectivePagesPerHour.isFinite())
        assertNull(result.remainingEffectiveReadingTime)
        assertNull(result.unavailableReason)
    }

    @Test fun selectsFirstSevenFourteenOrThirtyDayEligibleWindow() {
        for ((offsets, days) in listOf(listOf(0, 1, 2) to 7, listOf(0, 7, 8) to 14, listOf(0, 14, 29) to 30)) {
            val window = service.selectWindow(offsets.mapIndexed { i, offset -> sample("s$i", offset) }, time)!!
            assertEquals(days, window.days)
            assertEquals(today.minusDays(days - 1L), window.startDate)
            assertEquals(today, window.endDate)
        }
        assertNull(service.selectWindow(listOf(sample("a", 0), sample("b", 14), sample("c", 30)), time))
    }

    @Test fun exactSessionAndThirtyMinuteThresholdsCannotBeLowered() {
        val exact = listOf(sample("a", 0), sample("b", 0), sample("c", 0))
        assertEquals(1_800_000L, service.selectWindow(exact, time)!!.totalEffectiveFocusMillis)
        assertNull(service.selectWindow(exact.dropLast(1).map { it.copy(effectiveFocusMillis = 3_600_000) }, time))
        assertNull(service.selectWindow(exact.mapIndexed { i, it ->
            if (i == 0) it.copy(effectiveFocusMillis = 599_999) else it }, time))
        // Six-day boundary belongs to 7d; seven-day boundary must expand to 14d.
        assertEquals(7, service.selectWindow(exact.map { it.copy(endedDate = today.minusDays(6)) }, time)!!.days)
        assertEquals(14, service.selectWindow(exact.map { it.copy(endedDate = today.minusDays(7)) }, time)!!.days)
    }

    @Test fun effectiveWindowsRespectLocalDaysDstAndFutureCutoff() {
        val shanghai = AnalyticsTimeContext(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"))
        val midnight = Instant.parse("2025-12-31T16:00:00Z")
        val rows = source(row("midnight", 1, 10, end = midnight),
            row("before", 1, 10, end = midnight.minusMillis(1)), row("now", 1, 10, end = shanghai.now),
            row("future", 1, 10, end = shanghai.now.plusMillis(1)))
        val samples = service.qualify(rows, shanghai).associateBy { it.sessionId }
        assertEquals(LocalDate.of(2026, 1, 1), samples["midnight"]!!.endedDate)
        assertEquals(LocalDate.of(2025, 12, 31), samples["before"]!!.endedDate)
        assertFalse(samples.containsKey("future"))
        assertEquals(7, service.selectWindow(samples.values.toList(), shanghai)!!.days)

        val dst = AnalyticsTimeContext(Instant.parse("2026-11-02T12:00:00Z"), ZoneId.of("America/New_York"))
        val repeated = source(row("first-0130", 1, 10, end = Instant.parse("2026-11-01T05:30:00Z")),
            row("second-0130", 1, 10, end = Instant.parse("2026-11-01T06:30:00Z")),
            row("today", 1, 10, end = dst.now))
        val dstSamples = service.qualify(repeated, dst)
        assertEquals(listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)), dstSamples.map { it.endedDate })
        assertEquals(1_800_000L, service.selectWindow(dstSamples, dst)!!.totalEffectiveFocusMillis)
    }

    @Test fun onlyNormalCompletePositiveFocusWithEndPagesQualifies() {
        val valid = row("valid", 10, 30)
        val abnormal = row("abnormal", 10, 30).let { it.first.copy(endType = SessionEndType.ABNORMAL) to it.second }
        val noPage = row("no-page", 10, 30).let { it.first.copy(endPage = null) to it.second }
        val old = row("old", 10, 30)
        val partial = row("partial", 10, 30)
        val source = source(valid, abnormal, noPage, old, partial).let {
            it.copy(contexts = it.contexts - "old" + ("partial" to it.contexts.getValue("partial").copy(monitoringStatus = MonitoringCoverage.PARTIAL)),
                segments = it.segments - "old")
        }
        assertEquals(listOf("valid"), service.qualify(source, time).map { it.sessionId })
        assertNull(service.estimate(item(), source, time).window)
    }

    @Test fun pagesReadUsesLongDifferenceClampedAtZeroWithoutPlusOne() {
        val lower = row("lower", 0, 30).let { it.first.copy(startPage = 42, endPage = 40) to it.second }
        val extreme = row("extreme", 0, 30).let { it.first.copy(startPage = Int.MIN_VALUE, endPage = Int.MAX_VALUE) to it.second }
        val samples = service.qualify(source(lower, extreme), time)
        assertEquals(0L, samples[0].pagesRead)
        assertEquals(4_294_967_295L, samples[1].pagesRead)
    }

    @Test fun pausedCompletedAndLastPageKeepSpeedButSuppressFutureTime() {
        val source = source(row("a", 10, 20), row("b", 10, 20), row("c", 10, 20))
        for (status in LearningItemStatus.entries) {
            val result = service.estimate(item().copy(status = status), source, time)
            assertEquals(30.0, result.window!!.effectivePagesPerHour, 0.0)
            if (status == LearningItemStatus.IN_PROGRESS) assertNotNull(result.remainingEffectiveReadingTime)
            else assertNull(result.remainingEffectiveReadingTime)
        }
        assertNull(service.estimate(item().copy(currentPage = 200), source, time).remainingEffectiveReadingTime)
        val passed = service.estimate(item().copy(currentPage = 250), source, time)
        assertEquals(0L, passed.remainingPages)
        assertNotNull(passed.window)
        assertNull(passed.remainingEffectiveReadingTime)
        assertNull(service.estimate(item().copy(totalPages = 0), source, time).remainingEffectiveReadingTime)
    }

    @Test fun remainingEffectiveTimeRoundsUpToWholeMinutes() {
        val source = source(row("a", 10, 20), row("b", 10, 20), row("c", 10, 20))
        assertEquals(Duration.ofMinutes(150), service.estimate(item().copy(currentPage = 125), source, time).remainingEffectiveReadingTime)
        val fast = source(row("a", 100, 10), row("b", 100, 10), row("c", 100, 10))
        assertEquals(Duration.ofMinutes(1), service.estimate(item().copy(currentPage = 199), fast, time).remainingEffectiveReadingTime)
    }

    @Test fun exactWholeMinuteBoundaryIsNotRoundedUpByFloatingPointError() {
        val source = source(row("a", 1, 20), row("b", 0, 20), row("c", 0, 15))
        val result = service.estimate(item().copy(currentPage = 199), source, time)
        assertEquals(Duration.ofMinutes(55), result.remainingEffectiveReadingTime)
    }

    @Test fun outOfRangeArithmeticNeverProducesNonFiniteValues() {
        val focusOverflow = listOf(sample("a", 0, focus = Long.MAX_VALUE), sample("b", 0), sample("c", 0))
        assertThrows(ArithmeticException::class.java) { service.selectWindow(focusOverflow, time) }
        val pagesOverflow = listOf(sample("a", 0).copy(pagesRead = Long.MAX_VALUE), sample("b", 0), sample("c", 0))
        assertThrows(ArithmeticException::class.java) { service.selectWindow(pagesOverflow, time) }
        val extremeTime = AnalyticsTimeContext(Instant.ofEpochMilli(Long.MAX_VALUE), ZoneId.of("UTC"))
        val huge = source(*(0..2).map { row("$it", 1, 1, end = extremeTime.now, focus = Long.MAX_VALUE) }.toTypedArray())
        val unavailable = service.estimate(item(), huge, extremeTime)
        assertEquals(EffectiveEstimateUnavailableReason.ARITHMETIC_OUT_OF_RANGE, unavailable.unavailableReason)
        assertNull(unavailable.window)
        assertNull(unavailable.remainingEffectiveReadingTime)
    }

    @Test fun durationMinuteConversionOutOfRangeIsNotInsufficientWindow() {
        val extremeTime = AnalyticsTimeContext(Instant.ofEpochMilli(Long.MAX_VALUE), ZoneId.of("UTC"))
        val longSessions = source(*(0..2).map { row("$it", 1, 1, end = extremeTime.now, focus = Long.MAX_VALUE / 3 - 1) }.toTypedArray())
        val window = service.selectWindow(service.qualify(longSessions, extremeTime), extremeTime)!!
        assertTrue(window.effectivePagesPerHour.isFinite())
        val result = service.estimate(item().copy(totalPages = Int.MAX_VALUE, currentPage = 1), longSessions, extremeTime)
        assertEquals(EffectiveEstimateUnavailableReason.ARITHMETIC_OUT_OF_RANGE, result.unavailableReason)
        assertNull(result.remainingEffectiveReadingTime)
    }

    @Test fun estimateDoesNotMixLearningItems() {
        val other = (0..2).map { i -> row("other-$i", 100, 30).let { it.first.copy(learningItemId = "other") to it.second } }
        val result = service.estimate(item(), source(row("book", 1, 30), *other.toTypedArray()), time)
        assertNull(result.window)
        assertEquals(EffectiveEstimateUnavailableReason.INSUFFICIENT_WINDOW, result.unavailableReason)
    }

    private fun item() = LearningItemEntity("book", "Test", LearningItemStatus.IN_PROGRESS, 200, 100, null, "", 0, 0, null)
    private fun sample(id: String, daysAgo: Int, focus: Long = 600_000) =
        QualifiedEffectiveSession(id, "book", today.minusDays(daysAgo.toLong()), 10, focus)

    private fun row(id: String, pages: Int, minutes: Long, end: Instant = time.now,
        type: SessionSegmentType = SessionSegmentType.FOCUS, focus: Long = minutes * 60_000): Pair<StudySessionEntity, List<SessionSegmentEntity>> {
        val endedAt = end.toEpochMilli()
        val start = endedAt - focus
        val session = StudySessionEntity(id, "book", "i-$id", start, null, endedAt, 1, 1 + pages, 1 + pages,
            SessionEndType.NORMAL, null, null)
        val segment = SessionSegmentEntity("seg-$id", id, type, start, endedAt, null, null, null, relatedSegmentId = null, activeSlot = null)
        return session to listOf(segment)
    }

    private fun source(vararg rows: Pair<StudySessionEntity, List<SessionSegmentEntity>>) = EffectiveReadingSource(
        sessions = rows.map { it.first },
        contexts = rows.associate { (s, _) -> s.id to SessionFocusContextEntity(sessionId = s.id,
            monitoringStatus = MonitoringCoverage.FULL, monitoringLostAt = null, priorDndInterruptionFilter = null,
            dndRuleId = null, requestedEndPage = null, closeoutStartedAt = null,
            lastHeartbeatAt = s.endedAt!!, createdAt = s.startedAt, updatedAt = s.endedAt) },
        segments = rows.associate { it.first.id to it.second },
    )
}
