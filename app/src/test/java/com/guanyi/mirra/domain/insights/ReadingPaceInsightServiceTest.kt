package com.guanyi.mirra.domain.insights

import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.local.model.EffectiveReadingSource
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.EffectiveReadingService
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReadingPaceInsightServiceTest {
    private val service = ReadingPaceInsightService()
    private val time = AnalyticsTimeContext(Instant.parse("2026-11-02T12:00:00Z"), ZoneId.of("America/New_York"))
    private val today = time.now.atZone(time.zoneId).toLocalDate()

    @Test fun exactlyThreeRecentAndFiveBaselineProduceSlower() {
        val result = analyze(samples())
        assertEquals(ReadingPaceInsightDirection.SLOWER, result.insight!!.direction)
        assertEquals(3, result.insight.recent.sampleCount)
        assertEquals(5, result.insight.baseline.sampleCount)
        assertEquals(21L, result.insight.recent.totalPagesRead)
        assertEquals(1_800_000L, result.insight.recent.totalEffectiveFocusMillis)
        assertEquals(60L, result.insight.baseline.totalPagesRead)
        assertEquals(3_600_000L, result.insight.baseline.totalEffectiveFocusMillis)
        assertEquals(0.70, result.insight.ratio, 0.0)
        assertNull(result.unavailableReason)
    }

    @Test fun onlyTwoRecentCannotProduceAnInsight() {
        unavailable(ReadingPaceInsightUnavailableReason.INSUFFICIENT_RECENT_DATA, samples().take(2))
    }

    @Test fun threeNewestMustAllBeFreshEvenWhenOnlyTwoAreRecent() {
        val values = samples().mapIndexed { i, s -> if (i == 2) s.copy(endedAt = end(30)) else s }
        // Other baseline samples are also older: the third newest is exactly outside 30 days.
        val older = values.mapIndexed { i, s -> if (i >= 3) s.copy(endedAt = end(31 + i)) else s }
        unavailable(ReadingPaceInsightUnavailableReason.STALE_RECENT_DATA, older)
    }

    @Test fun recentTwentyNineMinutesFiftyNineSecondsIsInsufficient() {
        unavailable(ReadingPaceInsightUnavailableReason.INSUFFICIENT_RECENT_DATA,
            samples().mapIndexed { i, s -> if (i == 0) s.copy(effectiveFocusMillis = 599_000) else s })
    }

    @Test fun recentExactlyThirtyMinutesQualifies() { assertNotNull(analyze(samples()).insight) }

    @Test fun fourBaselineSamplesAreInsufficient() {
        unavailable(ReadingPaceInsightUnavailableReason.INSUFFICIENT_BASELINE, samples().dropLast(1))
    }

    @Test fun baselineFiftyNineMinutesFiftyNineSecondsIsInsufficient() {
        unavailable(ReadingPaceInsightUnavailableReason.INSUFFICIENT_BASELINE,
            samples().mapIndexed { i, s -> if (i == 3) s.copy(effectiveFocusMillis = 719_000) else s })
    }

    @Test fun baselineExactlySixtyMinutesQualifies() { assertNotNull(analyze(samples()).insight) }

    @Test fun baselineIsNextTenAndNeverIncludesRecentOrOlderEleventh() {
        val values = samples(baselineCount = 11).mapIndexed { i, s -> if (i == 13) s.copy(pagesRead = 1_000_000) else s }
        val result = analyze(values.reversed()).insight!!
        assertEquals(listOf("r0", "r1", "r2"), result.recent.sessionIds)
        assertEquals((0..9).map { "b$it" }, result.baseline.sessionIds)
        assertEquals(120L, result.baseline.totalPagesRead)
        assertEquals(7_200_000L, result.baseline.totalEffectiveFocusMillis)
        assertEquals(0.7, result.ratio, 0.0)
    }

    @Test fun inclusiveNinetiethNaturalDayQualifies() {
        val boundary = today.minusDays(89).atStartOfDay(time.zoneId).toInstant()
        assertNotNull(analyze(samples().mapIndexed { i, s -> if (i == 7) s.copy(endedAt = boundary) else s }).insight)
    }

    @Test fun oneMillisecondBeforeNinetiethDayDoesNotFillBaseline() {
        val boundary = today.minusDays(89).atStartOfDay(time.zoneId).toInstant().minusMillis(1)
        unavailable(ReadingPaceInsightUnavailableReason.INSUFFICIENT_BASELINE,
            samples().mapIndexed { i, s -> if (i == 7) s.copy(endedAt = boundary) else s })
    }

    @Test fun inclusiveThirtiethDayQualifiesAcrossDst() {
        val boundary = today.minusDays(29).atStartOfDay(time.zoneId).toInstant()
        val values = samples().mapIndexed { i, s -> when {
            i == 2 -> s.copy(endedAt = boundary)
            i >= 3 -> s.copy(endedAt = end(40 + i))
            else -> s
        } }
        assertNotNull(analyze(values).insight)
        assertTrue(time.now.toEpochMilli() - boundary.toEpochMilli() > 29L * 86_400_000)
    }

    @Test fun beforeThirtiethLocalMidnightIsStale() {
        val boundary = today.minusDays(29).atStartOfDay(time.zoneId).toInstant().minusMillis(1)
        unavailable(ReadingPaceInsightUnavailableReason.STALE_RECENT_DATA,
            samples().mapIndexed { i, s -> when { i == 2 -> s.copy(endedAt = boundary); i >= 3 -> s.copy(endedAt = end(40 + i)); else -> s } })
    }

    @Test fun snapshotZoneDeterminesNaturalDayNotUtc() {
        val shanghai = AnalyticsTimeContext(Instant.parse("2026-10-07T01:00:00Z"), ZoneId.of("Asia/Shanghai"))
        val boundary = Instant.parse("2026-09-07T16:00:00Z")
        val values = samples().mapIndexed { i, s -> s.copy(endedAt = when {
            i == 0 -> shanghai.now; i == 1 -> shanghai.now.minusSeconds(3600)
            i == 2 -> boundary; else -> boundary.minusSeconds((i - 2) * 86_400L)
        }) }
        assertNotNull(service.analyzeQualified("book", values, shanghai).insight)
        assertEquals(ReadingPaceInsightUnavailableReason.STALE_RECENT_DATA,
            service.analyzeQualified("book", values, shanghai.copy(zoneId = ZoneId.of("UTC"))).unavailableReason)
    }

    @Test fun sameBookIsolationIgnoresOtherBooks() {
        val other = samples().map { it.copy(sessionId = "other-${it.sessionId}", learningItemId = "other", pagesRead = 100_000, endedAt = time.now) }
        assertEquals(0.7, analyze(samples() + other).insight!!.ratio, 0.0)
        unavailable(ReadingPaceInsightUnavailableReason.INSUFFICIENT_RECENT_DATA, other)
    }

    @Test fun exactEndTimestampOrderingPrecedesIdAndInputOrder() {
        val values = samples().mapIndexed { i, s -> if (i < 4) s.copy(endedAt = time.now.minusMillis(i.toLong())) else s }
        assertEquals(listOf("r0", "r1", "r2"), analyze(values.reversed()).insight!!.recent.sessionIds)
    }

    @Test fun sameTimestampUsesDescendingSessionId() {
        val values = samples().mapIndexed { i, s -> if (i < 3) s.copy(endedAt = time.now, sessionId = listOf("a", "z", "m")[i]) else s }
        assertEquals(listOf("z", "m", "a"), analyze(values.reversed()).insight!!.recent.sessionIds)
    }

    @Test fun weightedAggregationDoesNotAverageIndividualSpeeds() {
        val values = samples().mapIndexed { i, s -> when (i) {
            0 -> s.copy(pagesRead = 1, effectiveFocusMillis = 120_000)
            1 -> s.copy(pagesRead = 1, effectiveFocusMillis = 120_000)
            2 -> s.copy(pagesRead = 1, effectiveFocusMillis = 1_560_000)
            else -> s
        } }
        val result = analyze(values).insight!!
        assertEquals(6.0, result.recent.effectivePagesPerHour, 0.0)
        assertEquals(60.0, result.baseline.effectivePagesPerHour, 0.0)
        assertEquals(0.1, result.ratio, 0.0)
    }

    @Test fun exactlyPointSevenIsSlower() { assertEquals(ReadingPaceInsightDirection.SLOWER, analyze(samples()).insight!!.direction) }

    @Test fun exactlyOnePointThreeIsFaster() {
        val result = analyze(samples(recentPages = 13)).insight!!
        assertEquals(ReadingPaceInsightDirection.FASTER, result.direction)
        assertEquals(1.3, result.ratio, 0.0)
    }

    @Test fun exactRationalBoundaryIsNotLostToRoundedSpeeds() {
        val result = analyze(samples(recentPages = 13).map { it.copy(effectiveFocusMillis = it.effectiveFocusMillis * 7) }).insight!!
        assertEquals(ReadingPaceInsightDirection.FASTER, result.direction)
    }

    @Test fun ratioInsideBandIsHidden() {
        unavailable(ReadingPaceInsightUnavailableReason.NO_NOTABLE_CHANGE, samples(recentPages = 10))
    }

    @Test fun oneEqualRecentSpeedSuppressesSlowerDespiteWeightedRatio() {
        unavailable(ReadingPaceInsightUnavailableReason.NO_NOTABLE_CHANGE,
            samples(recentPages = 1).mapIndexed { i, s -> if (i == 0) s.copy(pagesRead = 10) else s })
    }

    @Test fun oneOppositeRecentSpeedSuppressesFasterDespiteWeightedRatio() {
        unavailable(ReadingPaceInsightUnavailableReason.NO_NOTABLE_CHANGE,
            samples(recentPages = 100).mapIndexed { i, s -> if (i == 0) s.copy(pagesRead = 1) else s })
    }

    @Test fun sourceReusesFrozenQualificationAndSkipsZeroPagesFocusAndUntrustedFacts() {
        val good = facts()
        val extras = facts().sessions.take(1).map { it.copy(id = "zero", intentId = "i-zero", startPage = 10, endPage = 10) }
        val zero = factSource(extras)
        val full = EffectiveReadingSource(good.sessions + zero.sessions, good.contexts + zero.contexts, good.segments + zero.segments)
        assertEquals(9, EffectiveReadingService().qualify(full, time).size)
        assertEquals(3, service.analyze("book", full, time).insight!!.recent.sampleCount)
        val noFocus = full.copy(segments = full.segments + ("zero" to full.segments.getValue("zero").map { it.copy(type = SessionSegmentType.BREAK) }))
        assertNotNull(service.analyze("book", noFocus, time).insight)
        for (coverage in listOf(MonitoringCoverage.PARTIAL, MonitoringCoverage.NONE)) {
            val incomplete = good.copy(contexts = good.contexts + ("r0" to good.contexts.getValue("r0").copy(monitoringStatus = coverage)))
            assertNull(service.analyze("book", incomplete, time).insight)
        }
    }

    @Test fun futureSampleIsExcludedWithoutReplacingSnapshot() {
        val values = samples() + samples().first().copy(sessionId = "future", endedAt = time.now.plusMillis(1), pagesRead = 1_000_000)
        assertEquals(0.7, analyze(values).insight!!.ratio, 0.0)
    }

    @Test fun rawSourcePreservesMillisecondsForSelection() {
        val values = samples().mapIndexed { i, s -> s.copy(endedAt = time.now.minusMillis(i.toLong())) }
        val result = service.analyze("book", factSource(values.map(::session).reversed()), time).insight!!
        assertEquals(listOf("r0", "r1", "r2"), result.recent.sessionIds)
    }

    @Test fun duplicateSessionCannotInventEvidence() {
        val source = facts()
        assertEquals(ReadingPaceInsightUnavailableReason.INVALID_ASSOCIATION,
            service.analyze("book", source.copy(sessions = source.sessions + source.sessions.first()), time).unavailableReason)
        unavailable(ReadingPaceInsightUnavailableReason.INVALID_ASSOCIATION, samples() + samples().first())
    }

    @Test fun wrongContextAssociationCannotProduceInsight() {
        val source = facts()
        val corrupt = source.copy(contexts = source.contexts + ("r0" to source.contexts.getValue("r0").copy(sessionId = "r1")))
        assertEquals(ReadingPaceInsightUnavailableReason.INVALID_ASSOCIATION, service.analyze("book", corrupt, time).unavailableReason)
    }

    @Test fun wrongSegmentAssociationCannotProduceInsight() {
        val source = facts()
        val corrupt = source.copy(segments = source.segments + ("r0" to source.segments.getValue("r0").map { it.copy(sessionId = "r1") }))
        assertEquals(ReadingPaceInsightUnavailableReason.INVALID_ASSOCIATION, service.analyze("book", corrupt, time).unavailableReason)
    }

    @Test fun duplicateSegmentIdsCannotProduceInsight() {
        val source = facts()
        val corrupt = source.copy(segments = source.segments + ("r1" to source.segments.getValue("r1").map { it.copy(id = "seg-r0") }))
        assertEquals(ReadingPaceInsightUnavailableReason.INVALID_ASSOCIATION, service.analyze("book", corrupt, time).unavailableReason)
    }

    @Test fun extraContextKeyAliasingCurrentSessionCannotProduceInsight() {
        val source = facts()
        val corrupt = source.copy(contexts = source.contexts + ("orphan-key" to source.contexts.getValue("r0")))
        assertEquals(ReadingPaceInsightUnavailableReason.INVALID_ASSOCIATION, service.analyze("book", corrupt, time).unavailableReason)
    }

    @Test fun extraSegmentKeyAliasingCurrentSessionCannotProduceInsight() {
        val source = facts()
        val corrupt = source.copy(segments = source.segments + ("orphan-key" to source.segments.getValue("r0")))
        assertEquals(ReadingPaceInsightUnavailableReason.INVALID_ASSOCIATION, service.analyze("book", corrupt, time).unavailableReason)
    }

    @Test fun duplicatedIntentAssociationCannotProduceInsight() {
        val source = facts()
        val corrupt = source.copy(sessions = source.sessions.map { if (it.id == "r1") it.copy(intentId = "i-r0") else it })
        assertEquals(ReadingPaceInsightUnavailableReason.INVALID_ASSOCIATION, service.analyze("book", corrupt, time).unavailableReason)
    }

    @Test fun zeroAndNegativePageOrFocusSamplesCannotFillBaseline() {
        for (invalid in listOf(samples().last().copy(pagesRead = 0), samples().last().copy(pagesRead = -1),
            samples().last().copy(effectiveFocusMillis = 0), samples().last().copy(effectiveFocusMillis = -1))) {
            unavailable(ReadingPaceInsightUnavailableReason.INSUFFICIENT_BASELINE, samples().dropLast(1) + invalid)
        }
    }

    @Test fun positivePageZeroEffectiveFocusIsExcludedByFrozenQualification() {
        val source = facts()
        val breaks = source.copy(segments = source.segments + ("b4" to source.segments.getValue("b4").map { it.copy(type = SessionSegmentType.BREAK) }))
        assertEquals(ReadingPaceInsightUnavailableReason.INSUFFICIENT_BASELINE, service.analyze("book", breaks, time).unavailableReason)
    }

    @Test fun thresholdsCannotExpandIntoBand() {
        for (pages in listOf(7_001L, 12_999L)) {
            unavailable(ReadingPaceInsightUnavailableReason.NO_NOTABLE_CHANGE,
                samples().mapIndexed { i, s -> s.copy(pagesRead = if (i < 3) pages else 12_000) })
        }
    }

    @Test fun oneOppositeRecentSpeedSuppressesSlowerDespiteWeightedRatio() {
        unavailable(ReadingPaceInsightUnavailableReason.NO_NOTABLE_CHANGE,
            samples(recentPages = 1).mapIndexed { i, s -> if (i == 0) s.copy(pagesRead = 11) else s })
    }

    @Test fun oneEqualRecentSpeedSuppressesFasterDespiteWeightedRatio() {
        unavailable(ReadingPaceInsightUnavailableReason.NO_NOTABLE_CHANGE,
            samples(recentPages = 100).mapIndexed { i, s -> if (i == 0) s.copy(pagesRead = 10) else s })
    }

    @Test fun baselineFocusOverflowNeverProducesInsight() {
        unavailable(ReadingPaceInsightUnavailableReason.ARITHMETIC_OUT_OF_RANGE,
            samples().mapIndexed { i, s -> if (i >= 3) s.copy(effectiveFocusMillis = Long.MAX_VALUE) else s })
    }

    @Test fun overflowFocusNeverProducesInsight() {
        unavailable(ReadingPaceInsightUnavailableReason.ARITHMETIC_OUT_OF_RANGE,
            samples().mapIndexed { i, s -> if (i < 3) s.copy(effectiveFocusMillis = Long.MAX_VALUE) else s })
    }

    @Test fun overflowPagesNeverProducesInsight() {
        unavailable(ReadingPaceInsightUnavailableReason.ARITHMETIC_OUT_OF_RANGE,
            samples().mapIndexed { i, s -> if (i >= 3) s.copy(pagesRead = Long.MAX_VALUE) else s })
    }

    @Test fun extremeValidArithmeticKeepsSpeedsAndRatioFinite() {
        val values = samples().mapIndexed { i, s -> s.copy(pagesRead = if (i < 3) 1 else Long.MAX_VALUE / 5) }
        val insight = analyze(values).insight!!
        assertTrue(insight.ratio.isFinite())
        assertTrue(insight.recent.effectivePagesPerHour.isFinite())
        assertTrue(insight.baseline.effectivePagesPerHour.isFinite())
        assertTrue(insight.ratio > 0)
    }

    @Test fun invalidSnapshotIsUnavailableInsteadOfThrowing() {
        assertEquals(ReadingPaceInsightUnavailableReason.INVALID_TIMESTAMP,
            service.analyze("book", facts(), time.copy(now = Instant.MAX)).unavailableReason)
    }

    @Test fun invalidSessionTimestampCannotProduceInsight() {
        val source = facts()
        val corrupt = source.copy(sessions = source.sessions.map { if (it.id == "r0") it.copy(startedAt = it.endedAt!!) else it })
        assertNull(service.analyze("book", corrupt, time).insight)
    }

    @Test fun pausedAndCompletedBookStillDescribeHistory() {
        for (status in listOf(LearningItemStatus.PAUSED, LearningItemStatus.COMPLETED)) {
            val item = LearningItemEntity("book", "Test", status, 200, 100, null, "", 0, 0, null)
            assertEquals(ReadingPaceInsightDirection.SLOWER, service.analyze(item.id, facts(), time).insight!!.direction)
        }
    }

    @Test fun displayMagnitudeRoundsToNearestFivePercentAndCapsOnlyAboveHundred() {
        val examples = listOf(13_170L to 30, 13_320L to 35, 13_250L to 35, 20_000L to 100)
        for ((pages, percent) in examples) {
            val values = samples().mapIndexed { i, s -> s.copy(pagesRead = if (i < 3) pages else 12_000) }
            val insight = analyze(values).insight!!
            assertEquals(percent, insight.roundedChangePercent)
            assertFalse(insight.changeExceeds100Percent)
        }
        val values = samples().mapIndexed { i, s -> s.copy(pagesRead = if (i < 3) 20_001 else 12_000) }
        val over = analyze(values).insight!!
        assertEquals(100, over.roundedChangePercent)
        assertTrue(over.changeExceeds100Percent)
    }

    private fun analyze(values: List<ReadingPaceSample>) = service.analyzeQualified("book", values, time)
    private fun unavailable(reason: ReadingPaceInsightUnavailableReason, values: List<ReadingPaceSample>) {
        val result = analyze(values)
        assertNull(result.insight)
        assertEquals(reason, result.unavailableReason)
    }
    private fun end(daysAgo: Int): Instant = today.minusDays(daysAgo.toLong()).atTime(12, 0).atZone(time.zoneId).toInstant().coerceAtMost(time.now)
    private fun samples(recentPages: Long = 7, baselineCount: Int = 5): List<ReadingPaceSample> =
        (0..2).map { ReadingPaceSample("r$it", "book", end(it), recentPages, 600_000) } +
            (0 until baselineCount).map { ReadingPaceSample("b$it", "book", end(10 + it), 12, 720_000) }
    private fun session(s: ReadingPaceSample): StudySessionEntity {
        val end = s.endedAt.toEpochMilli()
        return StudySessionEntity(s.sessionId, s.learningItemId, "i-${s.sessionId}", end - s.effectiveFocusMillis,
            null, end, 1, (s.pagesRead + 1).toInt(), (s.pagesRead + 1).toInt(), SessionEndType.NORMAL, null, null)
    }
    private fun facts() = factSource(samples().map(::session))
    private fun factSource(sessions: List<StudySessionEntity>): EffectiveReadingSource = EffectiveReadingSource(
        sessions,
        sessions.associate { s -> s.id to SessionFocusContextEntity(sessionId = s.id,
            monitoringStatus = MonitoringCoverage.FULL, monitoringLostAt = null, priorDndInterruptionFilter = null,
            dndRuleId = null, requestedEndPage = null, closeoutStartedAt = null, lastHeartbeatAt = s.endedAt!!,
            createdAt = s.startedAt, updatedAt = s.endedAt) },
        sessions.associate { s -> s.id to listOf(SessionSegmentEntity("seg-${s.id}", s.id, SessionSegmentType.FOCUS,
            s.startedAt, s.endedAt, null, null, null, relatedSegmentId = null, activeSlot = null)) },
    )
}
