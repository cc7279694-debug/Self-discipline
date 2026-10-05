package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.local.model.EffectiveReadingSource
import com.guanyi.mirra.data.local.model.ReadingSessionProjection
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class EffectiveAnalyticsCompatibilityTest {
    private val overall = ReadingAnalyticsService()
    private val prediction = CompletionPredictionService()
    private val effective = EffectiveReadingService()
    private val time = AnalyticsTimeContext(Instant.parse("2026-09-13T12:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val item = LearningItemEntity("book", "Compatibility", LearningItemStatus.IN_PROGRESS, 253, 113,
        null, "", 0, 0, null)

    @Test fun phaseTwoFixturesKeepAllOriginalOutputs() {
        val fixture = fixture()
        assertOriginalOutputs(fixture)
        val estimate = effective.estimate(item, fixture.source, time)
        assertEquals(14, estimate.window!!.days)
        assertEquals(112L, estimate.window.totalPagesRead)
        assertEquals(Duration.ofHours(4).toMillis(), estimate.window.totalEffectiveFocusMillis)
        assertEquals(28.0, estimate.window.effectivePagesPerHour, 0.0)
        assertEquals(Duration.ofMinutes(300), estimate.remainingEffectiveReadingTime)
        assertEquals(140L, estimate.remainingPages)
        assertNull(estimate.unavailableReason)

        // Negative control: replacing ordinary reading duration with focus duration must not
        // satisfy the frozen oracle. No Phase 2 production formula is changed to run this check.
        val conflated = fixture.projections.map { it.copy(startedAt = it.startedAt + 1_800_000) }
        val wrongWindow = overall.selectAnalyticsWindow(conflated, time)!!
        assertNotEquals(expectedWindow(fixture), wrongWindow)
        assertNotEquals(expectedPrediction(), prediction.predict(item, wrongWindow, time))
        assertOriginalOutputs(fixture)
    }

    @Test fun oldSessionsParticipateOnlyInOverallAnalytics() {
        val fixture = fixture()
        val legacy = fixture.source.copy(contexts = emptyMap(), segments = emptyMap())
        val estimate = effective.estimate(item, legacy, time)
        assertNull(estimate.window)
        assertNull(estimate.remainingEffectiveReadingTime)
        assertEquals(EffectiveEstimateUnavailableReason.INSUFFICIENT_WINDOW, estimate.unavailableReason)
        assertOriginalOutputs(fixture)
        assertEquals(8, overall.qualify(fixture.projections, time).size)
    }

    @Test fun existingThreeCTrustedSessionsQualifyWithoutVersionExclusion() {
        val fixture = fixture()
        assertTrue(fixture.source.contexts.values.all { it.closeoutState == FocusCloseoutState.ACTIVE && it.snapshotVersion == 1 })
        val before = fixture.source.copy(sessions = fixture.source.sessions.toList(),
            contexts = fixture.source.contexts.toMap(), segments = fixture.source.segments.mapValues { it.value.toList() })
        val samples = effective.qualify(fixture.source, time)
        assertEquals(8, samples.size)
        assertTrue(samples.all { it.effectiveFocusMillis == 1_800_000L && it.pagesRead == 14L })
        assertOriginalOutputs(fixture)
        assertEquals(before, fixture.source) // Derived services never repair or rewrite historical facts.
    }

    @Test fun effectiveUnavailableDoesNotChangeNaturalPrediction() {
        val fixture = fixture()
        val variants = listOf(
            fixture.source.copy(contexts = fixture.source.contexts.mapValues { it.value.copy(monitoringStatus = MonitoringCoverage.PARTIAL) }),
            fixture.source.copy(contexts = fixture.source.contexts.mapValues { it.value.copy(monitoringStatus = MonitoringCoverage.NONE) }),
            fixture.source.copy(contexts = fixture.source.contexts.mapValues { it.value.copy(monitoringLostAt = it.value.lastHeartbeatAt) }),
            fixture.source.copy(segments = fixture.source.segments.mapValues { (_, rows) -> rows.map { it.copy(type = SessionSegmentType.UNMONITORED) } }),
            fixture.source.copy(segments = fixture.source.segments.mapValues { (_, rows) -> rows.mapIndexed { i, row ->
                if (i == 0) row.copy(startedAt = row.startedAt + 1) else row } }),
        )
        variants.forEach { source ->
            assertNull(effective.estimate(item, source, time).remainingEffectiveReadingTime)
            assertNull(effective.estimate(item, source, time).window)
            assertOriginalOutputs(fixture)
        }
    }

    private fun assertOriginalOutputs(fixture: Fixture) {
        val window = overall.selectAnalyticsWindow(fixture.projections, time)!!
        assertEquals(expectedWindow(fixture), window)
        assertEquals(expectedPrediction(), prediction.predict(item, window, time))
        assertEquals(SevenDayComparison(
            ReadingPeriodSummary(2, Duration.ofHours(2), 28, 2),
            ReadingPeriodSummary(6, Duration.ofHours(6), 84, 6),
        ), overall.buildSevenDayComparison(fixture.projections, 2, 6, time))
    }

    private fun expectedWindow(fixture: Fixture) = SelectedAnalyticsWindow(
        days = 14, startDate = LocalDate.of(2026, 8, 31), endDate = LocalDate.of(2026, 9, 13),
        sessions = fixture.projections.map { p -> QualifiedSession(
            p.sessionId, "book", Instant.ofEpochMilli(p.endedAt!!).atZone(time.zoneId).toLocalDate(),
            Instant.ofEpochMilli(p.startedAt), Instant.ofEpochMilli(p.endedAt), p.startPage, p.endPage!!,
            Duration.ofHours(1), 14, 1,
        ) },
        totalDuration = Duration.ofHours(8), totalPagesRead = 112,
        readingDays = 8, inclusiveDataSpanDays = 14, overallPagesPerHour = 14.0,
    )

    private fun expectedPrediction() = CompletionPrediction(
        remainingPages = 140, overallPagesPerHour = 14.0,
        estimatedRemainingReadingTime = Duration.ofMinutes(600), calendarPagesPerDay = 8.0,
        naturalCompletionRange = CompletionDateRange(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 3)),
        confidence = PredictionConfidence.HIGH, unavailableReasons = emptySet(), sourceWindowDays = 14,
        sourceSessionCount = 8,
    )

    private fun fixture(): Fixture {
        val dates = listOf("2026-08-31", "2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04", "2026-09-05", "2026-09-12", "2026-09-13")
        val sessions = dates.mapIndexed { i, date ->
            val end = LocalDate.parse(date).atTime(19, 0).atZone(time.zoneId).toInstant().toEpochMilli()
            val startPage = 1 + i * 14
            StudySessionEntity("s$i", "book", "i$i", end - 3_600_000, null, end, startPage,
                startPage + 14, startPage + 14, SessionEndType.NORMAL, null, null)
        }.asReversed()
        val source = EffectiveReadingSource(sessions, sessions.associate { s ->
            s.id to SessionFocusContextEntity(sessionId = s.id, monitoringStatus = MonitoringCoverage.FULL,
                monitoringLostAt = null, priorDndInterruptionFilter = null, dndRuleId = null,
                requestedEndPage = null, closeoutStartedAt = null, lastHeartbeatAt = s.endedAt!!,
                createdAt = s.startedAt, updatedAt = s.endedAt)
        }, sessions.associate { s -> s.id to listOf(
            segment(s, SessionSegmentType.FOCUS, s.startedAt, s.startedAt + 1_800_000),
            segment(s, SessionSegmentType.BREAK, s.startedAt + 1_800_000, s.endedAt!!),
        ) })
        val projections = sessions.map { s -> ReadingSessionProjection(s.id, s.learningItemId, s.startedAt, s.endedAt,
            s.startPage, s.endPage, s.endType, 1) }
        return Fixture(source, projections)
    }

    private fun segment(s: StudySessionEntity, type: SessionSegmentType, start: Long, end: Long) =
        SessionSegmentEntity("${s.id}-$type", s.id, type, start, end, null, null, null, relatedSegmentId = null, activeSlot = null)

    private data class Fixture(val source: EffectiveReadingSource, val projections: List<ReadingSessionProjection>)
}
