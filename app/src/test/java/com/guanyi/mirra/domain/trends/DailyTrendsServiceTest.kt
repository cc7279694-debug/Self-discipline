package com.guanyi.mirra.domain.trends

import com.guanyi.mirra.data.local.entity.DndLifecycle
import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.entity.FocusEventEntity
import com.guanyi.mirra.data.local.entity.FocusEventType
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.domain.AnalyticsTimeContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyTrendsServiceTest {
    private val service = TrendsService()
    private val time = AnalyticsTimeContext(Instant.parse("2026-10-07T12:00:00Z"), ZoneId.of("Asia/Shanghai"))

    @Test fun fixedRangeUsesActualDailyDurationsAndFillsDaysWithoutReading() {
        val actual = snapshot(sessions = listOf(
            source("short", "2026-10-01T01:00:00Z", "2026-10-01T01:10:00Z"),
            source("long", "2026-10-06T01:00:00Z", "2026-10-06T01:50:00Z"),
        ))
        assertEquals(7, actual.daily.size)
        assertEquals(LocalDate.parse("2026-10-01"), actual.daily.first().date)
        assertEquals(LocalDate.parse("2026-10-07"), actual.daily.last().date)
        assertEquals(listOf(600_000L, 0L, 0L, 0L, 0L, 3_000_000L, 0L),
            actual.daily.map { it.normalReading.totalDurationMillis })
        assertEquals(2L, actual.normalReading.sessionCount)
        assertEquals(3_600_000L, actual.normalReading.totalDurationMillis)
    }

    @Test fun crossingMidnightBelongsEntirelyToEndedLocalDay() {
        val actual = snapshot(sessions = listOf(source("overnight", "2026-10-01T15:50:00Z", "2026-10-01T16:20:00Z")))
        assertEquals(7, actual.daily.size)
        assertEquals(0L, actual.daily[0].normalReading.totalDurationMillis)
        assertEquals(1_800_000L, actual.daily[1].normalReading.totalDurationMillis)
        assertEquals(1L, actual.daily[1].period.maintain.trustedSessionCount)
    }

    @Test fun localCalendarRangeSupportsDaylightSavingRatherThanFixedTwentyFourHourDays() {
        val dstTime = AnalyticsTimeContext(Instant.parse("2026-03-10T12:00:00Z"), ZoneId.of("America/New_York"))
        val actual = snapshot(time = dstTime, sessions = listOf(
            source("spring", "2026-03-08T06:30:00Z", "2026-03-08T07:30:00Z"),
        ))
        assertEquals((4..10).map { LocalDate.of(2026, 3, it) }, actual.daily.map { it.date })
        assertEquals(3_600_000L, actual.daily.single { it.date == LocalDate.of(2026, 3, 8) }.normalReading.totalDurationMillis)
    }

    @Test fun dailyStartUsesIntentCreationCohortWhileReadingUsesSessionEndCohort() {
        val sessionSource = source("cross", "2026-10-01T16:00:00Z", "2026-10-01T16:10:00Z")
        val intent = StudyIntentEntity("i-cross", "book", millis("2026-10-01T15:59:00Z"),
            null, sessionSource.session.startedAt, sessionSource.session.startedAt, IntentOutcome.CONVERTED, null)
        val actual = snapshot(starts = listOf(StartTrendSource(intent, listOf(sessionSource.session))),
            sessions = listOf(sessionSource))
        assertEquals(7, actual.daily.size)
        assertEquals(1L, actual.daily[0].period.start.convertedCount)
        assertEquals(0L, actual.daily[1].period.start.convertedCount)
        assertEquals(600_000L, actual.daily[1].normalReading.totalDurationMillis)
        assertEquals(60_000.0, requireNotNull(actual.daily[0].period.start.medianStartLatencyMillis), 0.0)
    }

    @Test fun normalReadingStaysSeparateFromTrustedEffectiveFocusAndUnavailableSamples() {
        val knownZero = source("zero", "2026-10-02T01:00:00Z", "2026-10-02T01:10:00Z", SessionSegmentType.DISTRACTION)
        val unavailable = source("partial", "2026-10-02T02:00:00Z", "2026-10-02T02:20:00Z")
            .let { it.copy(context = it.context!!.copy(monitoringStatus = MonitoringCoverage.PARTIAL)) }
        val missing = source("legacy", "2026-10-03T01:00:00Z", "2026-10-03T01:05:00Z").copy(context = null)
        val actual = snapshot(sessions = listOf(knownZero, unavailable, missing))
        assertEquals(7, actual.daily.size)
        val mixedDay = actual.daily[1]
        assertEquals(1_800_000L, mixedDay.normalReading.totalDurationMillis)
        assertEquals(0L, mixedDay.period.maintain.effectiveFocusMillis)
        assertEquals(1L, mixedDay.period.maintain.trustedSessionCount)
        assertEquals(1L, mixedDay.period.maintain.unavailableSessionCount)
        assertEquals(300_000L, actual.daily[2].normalReading.totalDurationMillis)
        assertNull(actual.daily[2].period.maintain.effectiveFocusMillis)
        assertNull(actual.daily[3].period.maintain.effectiveFocusMillis)
        assertEquals(0L, actual.daily[3].normalReading.totalDurationMillis)
    }

    @Test fun malformedNormalReadingIsUnavailableWhileAbnormalIsExcluded() {
        val good = source("good", "2026-10-03T01:00:00Z", "2026-10-03T01:10:00Z")
        val endedAt = requireNotNull(good.session.endedAt)
        val invalid = listOf(
            good.copy(session = good.session.copy(endPage = null)),
            good.copy(session = good.session.copy(startedAt = endedAt)),
            good.copy(session = good.session.copy(startedAt = endedAt + 1)),
            good.copy(session = good.session.copy(endType = null)),
        )
        val actual = snapshot(sessions = invalid + good.copy(session = good.session.copy(endType = SessionEndType.ABNORMAL)))
        assertEquals(0L, actual.normalReading.sessionCount)
        assertEquals(4L, actual.normalReading.dataIssueCount)
        assertNull(actual.normalReading.totalDurationMillis)
        assertNull(actual.daily[2].normalReading.totalDurationMillis)
        assertEquals(4L, actual.daily[2].normalReading.dataIssueCount)
        val abnormalOnly = snapshot(sessions = listOf(good.copy(session = good.session.copy(endType = SessionEndType.ABNORMAL))))
        assertEquals(0L, abnormalOnly.normalReading.totalDurationMillis)
        assertEquals(0L, abnormalOnly.normalReading.dataIssueCount)
    }

    @Test fun invalidSamplesDoNotEraseValidReadingButRemainCounted() {
        val good = source("good", "2026-10-03T01:00:00Z", "2026-10-03T01:10:00Z")
        val actual = snapshot(sessions = listOf(good, good.copy(session = good.session.copy(endPage = null))))
        assertEquals(600_000L, actual.normalReading.totalDurationMillis)
        assertEquals(1L, actual.normalReading.sessionCount)
        assertEquals(1L, actual.normalReading.dataIssueCount)
    }

    @Test fun allKnownNonNormalEndingsAreExcludedInsteadOfReportedAsDamagedReading() {
        val input = source("excluded", "2026-10-03T01:00:00Z", "2026-10-03T01:10:00Z")
        for (endType in SessionEndType.entries.filter { it != SessionEndType.NORMAL }) {
            val actual = snapshot(sessions = listOf(input.copy(session = input.session.copy(endType = endType))))
            assertEquals("$endType must not enter normal reading", 0L, actual.normalReading.sessionCount)
            assertEquals("$endType is a known exclusion, not a damaged normal record", 0L, actual.normalReading.dataIssueCount)
            assertEquals("$endType-only days have no normal reading", 0L, actual.daily[2].normalReading.totalDurationMillis)
        }
    }

    @Test fun fixedRangeSizesAndPreviousBoundaryExcludeFactsOutsideCurrentSeries() {
        for (range in listOf(TrendsRange.SEVEN_DAYS, TrendsRange.THIRTY_DAYS, TrendsRange.NINETY_DAYS)) {
            val actual = snapshot(range = range, sessions = listOf(
                source("previous", "2026-07-08T15:58:00Z", "2026-07-08T15:59:00Z"),
                source("today", "2026-10-07T11:59:00Z", "2026-10-07T12:00:00Z"),
            ))
            assertEquals(range.days, actual.daily.size)
            assertEquals(60_000L, actual.normalReading.totalDurationMillis)
            assertEquals(60_000L, actual.daily.last().normalReading.totalDurationMillis)
        }
    }

    @Test fun previousReadingUsesTheSameFrozenTimeSnapshotAndIsAbsentForAllRange() {
        val actual = snapshot(sessions = listOf(
            source("previous", "2026-09-24T00:00:00Z", "2026-09-24T00:10:00Z"),
            source("boundary", "2026-09-30T15:59:00Z", "2026-09-30T16:00:00Z"),
        ))
        assertEquals(60_000L, actual.normalReading.totalDurationMillis)
        assertEquals(600_000L, actual.previousNormalReading!!.totalDurationMillis)
        assertEquals(1L, actual.previousNormalReading.sessionCount)
        assertEquals(60_000L, actual.daily.first().normalReading.totalDurationMillis)
        assertNull(snapshot(range = TrendsRange.ALL).previousNormalReading)
    }

    @Test fun durationSumOverflowIsUnavailableRatherThanZeroOrSaturatedValue() {
        val input = source("huge", "1970-01-01T00:00:00Z", "1970-01-01T00:00:00.001Z")
            .let { it.copy(session = it.session.copy(startedAt = Long.MIN_VALUE + 10_000)) }
        val actual = snapshot(range = TrendsRange.ALL, sessions = listOf(input, input))
        assertEquals(2L, actual.normalReading.sessionCount)
        assertEquals(0L, actual.normalReading.dataIssueCount)
        assertNull(actual.normalReading.totalDurationMillis)
        assertTrue(actual.normalReading.durationOverflow)
        assertNull(actual.daily.single().normalReading.totalDurationMillis)
        assertTrue(actual.daily.single().normalReading.durationOverflow)
    }

    @Test fun individuallyOverflowingReadingDurationIsCountedAsInvalid() {
        val input = source("overflow", "2026-10-01T01:00:00Z", "2026-10-01T01:10:00Z")
            .let { it.copy(session = it.session.copy(startedAt = Long.MIN_VALUE)) }
        val actual = snapshot(sessions = listOf(input))
        assertEquals(0L, actual.normalReading.sessionCount)
        assertEquals(1L, actual.normalReading.dataIssueCount)
        assertNull(actual.normalReading.totalDurationMillis)
        assertTrue(actual.normalReading.durationOverflow)
    }

    @Test fun activePendingFutureAndUnendedNeverEnterDailyReading() {
        val good = source("good", "2026-10-03T01:00:00Z", "2026-10-03T01:10:00Z")
        val actual = snapshot(sessions = listOf(
            good.copy(session = good.session.copy(activeSlot = 1)),
            good.copy(context = good.context!!.copy(closeoutState = FocusCloseoutState.PENDING)),
            good.copy(session = good.session.copy(endedAt = null)),
            source("future", "2026-10-07T11:59:00Z", "2026-10-07T12:00:00.001Z"),
        ))
        assertEquals(0L, actual.normalReading.sessionCount)
        assertEquals(0L, actual.normalReading.dataIssueCount)
        assertEquals(7, actual.daily.size)
        assertTrue(actual.daily.all { it.normalReading.totalDurationMillis == 0L })
    }

    @Test fun allRangeIsSparseAndSortedWithoutExpandingAncientCalendarGaps() {
        val ancient = source("ancient", "1900-01-01T01:00:00Z", "1900-01-01T01:01:00Z")
            .let { it.copy(session = it.session.copy(endPage = null)) }
        val actual = snapshot(range = TrendsRange.ALL, sessions = listOf(
            source("recent", "2026-10-06T01:00:00Z", "2026-10-06T01:10:00Z"), ancient,
        ))
        assertEquals(listOf(LocalDate.of(1900, 1, 1), LocalDate.of(2026, 10, 6)), actual.daily.map { it.date })
        assertNull(actual.daily.first().normalReading.totalDurationMillis)
        assertEquals(600_000L, actual.daily.last().normalReading.totalDurationMillis)
        assertTrue(snapshot(range = TrendsRange.ALL).daily.isEmpty())
    }

    @Test fun dailyRecoveryKeepsConfirmedAndUnknownOutcomesSeparate() {
        val input = source("recovery", "2026-10-04T01:00:00Z", "2026-10-04T01:10:00Z")
        val start = input.session.startedAt
        val segments = listOf(
            segment("d", input.session.id, SessionSegmentType.DISTRACTION, start, start + 60_000),
            segment("r", input.session.id, SessionSegmentType.RECOVERY, start + 60_000, start + 180_000, "d"),
            segment("f", input.session.id, SessionSegmentType.FOCUS, start + 180_000, input.session.endedAt!!),
        )
        val event = FocusEventEntity("success", input.session.id, FocusEventType.RECOVERY_SUCCEEDED,
            start + 180_000, null, "f", null)
        val successful = input.copy(segments = segments, events = listOf(event))
        val actual = snapshot(sessions = listOf(successful, successful.copy(events = emptyList())))
        assertEquals(7, actual.daily.size)
        val recover = actual.daily[3].period.recover
        assertEquals(2L, recover.attemptCount)
        assertEquals(1L, recover.successCount)
        assertEquals(1L, recover.unknownCount)
        assertEquals(1L, recover.knownOutcomeSuccess.denominator)
        assertEquals(1.0, requireNotNull(recover.knownOutcomeSuccess.value), 0.0)
    }

    @Test fun dailyAggregationDoesNotRetainOrDependOnMutableSourcePages() {
        val accumulator = service.accumulator(TrendsRange.SEVEN_DAYS, time)
        val page = mutableListOf(source("a", "2026-10-01T01:00:00Z", "2026-10-01T01:10:00Z"))
        accumulator.addSessionPage(page)
        page.clear()
        page.add(source("b", "2026-10-06T01:00:00Z", "2026-10-06T01:20:00Z"))
        accumulator.addSessionPage(page)
        page.clear()
        val actual = accumulator.finish()
        assertEquals(7, actual.daily.size)
        assertEquals(600_000L, actual.daily.first().normalReading.totalDurationMillis)
        assertEquals(1_200_000L, actual.daily[5].normalReading.totalDurationMillis)
        assertEquals(1_800_000L, actual.normalReading.totalDurationMillis)
        assertFalse(actual.normalReading.durationOverflow)
    }

    private fun snapshot(range: TrendsRange = TrendsRange.SEVEN_DAYS, time: AnalyticsTimeContext = this.time,
        starts: List<StartTrendSource> = emptyList(), sessions: List<SessionTrendSource> = emptyList()): TrendsSnapshot =
        service.accumulator(range, time).apply { addStartPage(starts); addSessionPage(sessions) }.finish()

    private fun source(id: String, startedAt: String, endedAt: String,
        type: SessionSegmentType = SessionSegmentType.FOCUS): SessionTrendSource {
        val start = millis(startedAt)
        val end = millis(endedAt)
        val session = StudySessionEntity(id, "book", "i-$id", start, null, end, 1, 2, 2, SessionEndType.NORMAL, null, null)
        val context = SessionFocusContextEntity(sessionId = id, monitoringStatus = MonitoringCoverage.FULL,
            monitoringLostAt = null, priorDndInterruptionFilter = null, dndRuleId = null, dndLifecycle = DndLifecycle.NOT_APPLIED,
            closeoutState = FocusCloseoutState.COMPLETED, requestedEndPage = null, closeoutStartedAt = null,
            lastHeartbeatAt = end, createdAt = start, updatedAt = end)
        return SessionTrendSource(session, context, listOf(segment("segment-$id", id, type, start, end)), emptyList())
    }

    private fun segment(id: String, sessionId: String, type: SessionSegmentType, start: Long, end: Long, related: String? = null) =
        SessionSegmentEntity(id, sessionId, type, start, end, null, null, null, relatedSegmentId = related, activeSlot = null)

    private fun millis(instant: String): Long = Instant.parse(instant).toEpochMilli()
}
