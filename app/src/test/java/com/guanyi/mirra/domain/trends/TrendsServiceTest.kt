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
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendsServiceTest {
    private val service = TrendsService()
    private val time = AnalyticsTimeContext(Instant.parse("2026-10-07T12:00:00Z"), ZoneId.of("Asia/Shanghai"))

    @Test fun convertedAbandonedTimeoutExcludeOpenFromDenominator() {
        val start = trends(starts = listOf(start("c", IntentOutcome.CONVERTED),
            start("a", IntentOutcome.ABANDONED), start("t", IntentOutcome.TIMEOUT), start("o", null))).current.start
        assertEquals(1L, start.convertedCount)
        assertEquals(1L, start.abandonedCount)
        assertEquals(1L, start.timeoutCount)
        assertEquals(1L, start.openCount)
        assertEquals(1L, start.conversion.numerator)
        assertEquals(3L, start.conversion.denominator)
        assertEquals(1.0 / 3.0, requireNotNull(start.conversion.value), 0.000001)
    }

    @Test fun laterSessionOutcomeNeverReversesConversion() {
        val sessions = listOf(session().copy(endType = SessionEndType.ABNORMAL),
            session().copy(endType = null, endedAt = null, activeSlot = 1),
            session().copy(endType = null))
        val start = trends(starts = sessions.mapIndexed { i, s ->
            start("$i").copy(linkedSessions = listOf(s.copy(intentId = "$i")))
        }).current.start
        assertEquals(3L, start.convertedCount)
        assertEquals(1.0, requireNotNull(start.conversion.value), 0.0)
        assertEquals(3L, start.startLatencySampleCount)
    }

    @Test fun brokenAssociationPreservesConversionButMarksDependentFactsUnavailable() {
        val bad = listOf(start("missing").copy(linkedSessions = emptyList()),
            start("wrong").copy(linkedSessions = listOf(session().copy(intentId = "other"))),
            start("duplicate").copy(linkedSessions = listOf(session(), session().copy(id = "other"))),
            start("wrong-book").copy(linkedSessions = listOf(session().copy(intentId = "wrong-book", learningItemId = "other"))))
        val result = trends(starts = bad).current.start
        assertEquals(4L, result.convertedCount)
        assertEquals(4L, result.startDataIssueCount)
        assertEquals(4L, result.stableDataIssueCount)
        assertNull(result.medianStartLatencyMillis)
        assertNull(result.medianStableLatencyMillis)
    }

    @Test fun startMedianUsesLegalSamplesRatherThanMean() {
        val result = trends(starts = listOf(start("a", latency = 10), start("b", latency = 20),
            start("c", latency = 1_000), start("negative", latency = -1),
            start("future").copy(linkedSessions = listOf(session().copy(intentId = "future", startedAt = time.now.toEpochMilli() + 1))))).current.start
        assertEquals(3L, result.startLatencySampleCount)
        assertEquals(20.0, requireNotNull(result.medianStartLatencyMillis), 0.0)
        assertEquals(2L, result.startDataIssueCount)
    }

    @Test fun evenMedianCannotOverflowLongAddition() {
        val hugeTime = time.copy(now = Instant.ofEpochMilli(Long.MAX_VALUE))
        val samples = listOf(start("a", latency = Long.MAX_VALUE - 2), start("b", latency = Long.MAX_VALUE - 4))
        val result = trends(starts = samples, snapshot = hugeTime).current.start
        assertEquals(2L, result.startLatencySampleCount)
        assertEquals(Long.MAX_VALUE.toDouble(), requireNotNull(result.medianStartLatencyMillis), 4.0)
        assertTrue(requireNotNull(result.medianStartLatencyMillis).isFinite())
    }

    @Test fun stableConfirmedIsSeparateFromUnconfirmedAndInvalid() {
        val samples = listOf(start("confirmed").copy(linkedSessions = listOf(session().copy(intentId = "confirmed", stableStartedAt = 30))),
            start("null"), start("before").copy(linkedSessions = listOf(session().copy(intentId = "before", stableStartedAt = 9))),
            start("after").copy(linkedSessions = listOf(session().copy(intentId = "after", stableStartedAt = 301))),
            start("future").copy(linkedSessions = listOf(session().copy(intentId = "future", endedAt = null,
                stableStartedAt = time.now.toEpochMilli() + 1))))
        val result = trends(starts = samples).current.start
        assertEquals(5L, result.convertedCount)
        assertEquals(1L, result.stableConfirmedCount)
        assertEquals(1L, result.stableUnconfirmedCount)
        assertEquals(3L, result.stableDataIssueCount)
        assertEquals(20.0, requireNotNull(result.medianStableLatencyMillis), 0.0)
    }

    @Test fun stableConfirmationCanBelongToStillActiveSession() {
        val result = trends(starts = listOf(start("i").copy(linkedSessions = listOf(
            session().copy(endedAt = null, activeSlot = 1, stableStartedAt = 30))))).current.start
        assertEquals(1L, result.stableConfirmedCount)
        assertEquals(20.0, requireNotNull(result.medianStableLatencyMillis), 0.0)
    }

    @Test fun maintainUsesValidatorFocusIncludingOnlyActualDeepDuration() {
        val result = trends(sessions = listOf(source(segments = listOf(segment("f", SessionSegmentType.FOCUS, 10, 100),
            segment("deep", SessionSegmentType.DEEP_FOCUS, 100, 140), segment("d", SessionSegmentType.DISTRACTION, 140, 300))))).current.maintain
        assertEquals(1L, result.trustedSessionCount)
        assertEquals(130L, result.effectiveFocusMillis)
        assertEquals(40L, result.deepFocusMillis)
        assertEquals(1L, result.distractionCount)
        assertEquals(160L, result.distractionMillis)
    }

    @Test fun trustedZeroRemainsAValueWhilePartialNoneMissingRemainUnavailable() {
        val noFocus = listOf(segment("d", SessionSegmentType.DISTRACTION, 10, 300))
        val trusted = trends(sessions = listOf(source(segments = noFocus))).current.maintain
        assertEquals(1L, trusted.trustedSessionCount)
        assertEquals(0L, trusted.effectiveFocusMillis)
        for (context in listOf(null, context().copy(monitoringStatus = MonitoringCoverage.PARTIAL),
            context().copy(monitoringStatus = MonitoringCoverage.NONE))) {
            val untrusted = trends(sessions = listOf(source(context = context, segments = noFocus))).current.maintain
            assertEquals(1L, untrusted.unavailableSessionCount)
            assertNull(untrusted.effectiveFocusMillis)
            assertNull(untrusted.deepFocusMillis)
            assertEquals(0L, untrusted.distractionCount)
        }
    }

    @Test fun abnormalIsUnavailableAndNeverEntersRecover() {
        val input = successSource().copy(session = session().copy(endType = SessionEndType.ABNORMAL))
        val result = trends(sessions = listOf(input)).current
        assertEquals(1L, result.maintain.unavailableSessionCount)
        assertEquals(0L, result.maintain.trustedSessionCount)
        assertEquals(0L, result.recover.attemptCount)
    }

    @Test fun malformedFullTimelineCannotContributeTrustedTime() {
        val result = trends(sessions = listOf(source(segments = listOf(segment("gap", SessionSegmentType.FOCUS, 11, 300))))).current.maintain
        assertEquals(1L, result.unavailableSessionCount)
        assertNull(result.effectiveFocusMillis)
    }

    @Test fun stillActivePendingAndFutureSessionsAreExcluded() {
        val valid = successSource()
        val inputs = listOf(valid,
            valid.copy(session = session().copy(activeSlot = 1)),
            valid.copy(context = context().copy(closeoutState = FocusCloseoutState.PENDING)),
            valid.copy(session = session().copy(endedAt = time.now.toEpochMilli() + 1)))
        val result = trends(sessions = inputs).current
        assertEquals(1L, result.maintain.endedSessionCount)
        assertEquals(1L, result.recover.attemptCount)
    }

    @Test fun successEventMustPointAtNewFocusAndExactRecoveryEnd() {
        val result = trends(sessions = listOf(successSource())).current.recover
        assertEquals(1L, result.attemptCount)
        assertEquals(1L, result.successCount)
        assertEquals(0L, result.unknownCount)
        assertEquals(100.0, requireNotNull(result.medianSuccessfulRecoveryMillis), 0.0)
    }

    @Test fun successPointingAtOldRecoveryOrWrongTimestampIsUnknown() {
        val sample = successSource()
        for (event in listOf(sample.events.single().copy(segmentId = "r"),
            sample.events.single().copy(occurredAt = 201), sample.events.single().copy(sessionId = "other"))) {
            val result = trends(sessions = listOf(sample.copy(events = listOf(event)))).current.recover
            assertEquals(1L, result.attemptCount)
            assertEquals(1L, result.unknownCount)
            assertEquals(0L, result.successCount)
            assertNull(result.medianSuccessfulRecoveryMillis)
        }
    }

    @Test fun interruptedConfirmationCanBeLaterThanBackfilledBoundary() {
        val result = trends(sessions = listOf(interruptedSource())).current.recover
        assertEquals(1L, result.interruptedCount)
        assertEquals(0L, result.successCount)
        assertEquals(0L, result.unknownCount)
        assertEquals(1L, result.knownOutcomeSuccess.denominator)
        assertEquals(0.0, requireNotNull(result.knownOutcomeSuccess.value), 0.0)
    }

    @Test fun interruptedMustLinkOriginalRecoveryAndStayWithinSuccessorBoundary() {
        val sample = interruptedSource()
        for (event in listOf(sample.events.single().copy(segmentId = "d2"),
            sample.events.single().copy(occurredAt = 189), sample.events.single().copy(occurredAt = 301))) {
            val result = trends(sessions = listOf(sample.copy(events = listOf(event)))).current.recover
            assertEquals(1L, result.unknownCount)
            assertEquals(0L, result.interruptedCount)
        }
    }

    @Test fun absentOutcomeClosedInRecoveryAllowanceAndLostMonitoringAreUnknown() {
        val sample = successSource()
        val inputs = listOf(sample.copy(events = emptyList()),
            source(segments = sample.segments.take(2).map { if (it.id == "r") it.copy(endedAt = 300) else it }),
            sample.copy(segments = sample.segments.map { if (it.id == "f") it.copy(type = SessionSegmentType.TEMPORARY_ALLOWANCE) else it }),
            sample.copy(context = context().copy(monitoringLostAt = 150)))
        val result = trends(sessions = inputs).current.recover
        assertEquals(4L, result.attemptCount)
        assertEquals(4L, result.unknownCount)
        assertNull(result.knownOutcomeSuccess.value)
    }

    @Test fun laterMonitoringLossCannotEraseEarlierConfirmedRecovery() {
        val sample = successSource().copy(context = context().copy(
            monitoringStatus = MonitoringCoverage.PARTIAL, monitoringLostAt = 250))
        val result = trends(sessions = listOf(sample)).current
        assertEquals(1L, result.maintain.unavailableSessionCount)
        assertEquals(1L, result.recover.successCount)
        assertEquals(0L, result.recover.unknownCount)
    }

    @Test fun recoverySuccessRequiresContextWithObservableLocalCoverage() {
        for (context in listOf(null, context().copy(monitoringStatus = MonitoringCoverage.NONE),
            context().copy(monitoringStatus = MonitoringCoverage.PARTIAL))) {
            val result = trends(sessions = listOf(successSource().copy(context = context))).current.recover
            assertEquals(1L, result.attemptCount)
            assertEquals(0L, result.successCount)
            assertEquals(1L, result.unknownCount)
            assertNull(result.knownOutcomeSuccess.value)
        }
    }

    @Test fun recoveryInterruptionRequiresContextWithObservableLocalCoverage() {
        for (context in listOf(null, context().copy(monitoringStatus = MonitoringCoverage.NONE),
            context().copy(monitoringStatus = MonitoringCoverage.PARTIAL))) {
            val result = trends(sessions = listOf(interruptedSource().copy(context = context))).current.recover
            assertEquals(1L, result.attemptCount)
            assertEquals(0L, result.interruptedCount)
            assertEquals(1L, result.unknownCount)
            assertNull(result.knownOutcomeSuccess.value)
        }
    }

    @Test fun unmonitoredSegmentInsideRecoveryMakesOtherwiseLinkedOutcomeUnknown() {
        for (sample in listOf(successSource(), interruptedSource())) {
            val result = trends(sessions = listOf(sample.copy(segments = sample.segments +
                segment("unmonitored", SessionSegmentType.UNMONITORED, 150, 180)))).current.recover
            assertEquals(1L, result.attemptCount)
            assertEquals(0L, result.successCount)
            assertEquals(0L, result.interruptedCount)
            assertEquals(1L, result.unknownCount)
        }
    }

    @Test fun overlappingSegmentInsideRecoveryMakesOtherwiseLinkedOutcomeUnknown() {
        for (sample in listOf(successSource(), interruptedSource())) {
            val result = trends(sessions = listOf(sample.copy(segments = sample.segments +
                segment("overlap", SessionSegmentType.FOCUS, 150, 180)))).current.recover
            assertEquals(1L, result.attemptCount)
            assertEquals(0L, result.successCount)
            assertEquals(0L, result.interruptedCount)
            assertEquals(1L, result.unknownCount)
        }
    }

    @Test fun unmonitoredAtDelayedInterruptionConfirmationMakesOutcomeUnknown() {
        val sample = interruptedSource()
        val result = trends(sessions = listOf(sample.copy(segments = sample.segments +
            segment("unmonitored", SessionSegmentType.UNMONITORED, 195, 205)))).current.recover
        assertEquals(1L, result.attemptCount)
        assertEquals(0L, result.interruptedCount)
        assertEquals(1L, result.unknownCount)
    }

    @Test fun laterUnmonitoredSegmentPreservesEarlierConfirmedRecovery() {
        val sample = successSource().copy(context = context().copy(
            monitoringStatus = MonitoringCoverage.PARTIAL, monitoringLostAt = 250))
        val result = trends(sessions = listOf(sample.copy(segments = sample.segments.map {
            if (it.id == "f") it.copy(endedAt = 250) else it
        } + segment("later-unmonitored", SessionSegmentType.UNMONITORED, 250, 300)))).current
        assertEquals(1L, result.maintain.unavailableSessionCount)
        assertEquals(1L, result.recover.successCount)
        assertEquals(0L, result.recover.unknownCount)
    }

    @Test fun repeatedSameEventIdIsIdempotentButInconsistentSameIdIsUnknown() {
        val sample = successSource()
        val repeated = trends(sessions = listOf(sample.copy(events = sample.events + sample.events))).current.recover
        assertEquals(1L, repeated.successCount)
        assertEquals(1L, repeated.attemptCount)
        val conflict = sample.copy(events = sample.events + sample.events.single().copy(occurredAt = 201))
        assertEquals(1L, trends(sessions = listOf(conflict)).current.recover.unknownCount)
    }

    @Test fun duplicateAndConflictingOutcomeEventsAreNotDoubleCountedOrSelected() {
        val sample = successSource()
        val duplicate = sample.copy(events = sample.events + sample.events.single().copy(id = "another"))
        val conflict = sample.copy(events = sample.events + event("interrupt", FocusEventType.RECOVERY_INTERRUPTED, 210, "r"))
        val result = trends(sessions = listOf(duplicate, conflict)).current.recover
        assertEquals(2L, result.attemptCount)
        assertEquals(2L, result.unknownCount)
        assertEquals(0L, result.successCount)
    }

    @Test fun breakAllowanceAndBrokenOriginsStayOutsidePrimaryRecover() {
        val sample = successSource()
        val inputs = listOf(SessionSegmentType.BREAK, SessionSegmentType.TEMPORARY_ALLOWANCE).map { type ->
            sample.copy(segments = sample.segments.map { if (it.id == "d") it.copy(type = type) else it })
        } + sample.copy(segments = sample.segments.map { if (it.id == "r") it.copy(relatedSegmentId = "absent") else it })
        val result = trends(sessions = inputs).current.recover
        assertEquals(0L, result.attemptCount)
        assertEquals(2L, result.plannedReturnCount)
        assertEquals(1L, result.invalidOriginCount)
    }

    @Test fun pageAggregationKeepsExactMediansWithoutRetainingSourcePages() {
        val accumulator = service.accumulator(TrendsRange.ALL, time)
        val mutablePage = mutableListOf(start("a", latency = 10), start("b", latency = 40))
        accumulator.addStartPage(mutablePage)
        mutablePage.clear()
        accumulator.addStartPage(listOf(start("c", latency = 20), start("d", latency = 30)))
        accumulator.addSessionPage(listOf(successSource()))
        accumulator.addSessionPage(listOf(interruptedSource()))
        val result = accumulator.finish()
        assertEquals(4L, result.current.start.convertedCount)
        assertEquals(25.0, requireNotNull(result.current.start.medianStartLatencyMillis), 0.0)
        assertEquals(2L, result.current.maintain.trustedSessionCount)
        assertEquals(2L, result.current.recover.attemptCount)
    }

    @Test fun startUsesIntentDateWhileSessionUsesEndDate() {
        val dateTime = time.copy(now = Instant.parse("2026-10-07T12:00:00Z"), zoneId = ZoneId.of("UTC"))
        val created = Instant.parse("2026-09-30T23:59:00Z").toEpochMilli()
        val begun = Instant.parse("2026-10-01T00:01:00Z").toEpochMilli()
        val ended = begun + 300
        val s = session().copy(startedAt = begun, endedAt = ended)
        val result = trends(starts = listOf(start("i").copy(intent = intent().copy(createdAt = created), linkedSessions = listOf(s))),
            sessions = listOf(source().copy(session = s, segments = listOf(segment("f", SessionSegmentType.FOCUS, begun, ended)))),
            range = TrendsRange.SEVEN_DAYS, snapshot = dateTime)
        assertEquals(0L, result.current.start.convertedCount)
        assertEquals(1L, requireNotNull(result.previous).start.convertedCount)
        assertEquals(1L, result.current.maintain.trustedSessionCount)
    }

    @Test fun naturalDayWindowsHandleDstAndFixedTimezoneSnapshot() {
        val spring = AnalyticsTimeContext(Instant.parse("2026-03-09T04:00:00Z"), ZoneId.of("America/New_York"))
        val windows = service.windows(TrendsRange.SEVEN_DAYS, spring)
        assertEquals(Instant.parse("2026-03-03T05:00:00Z").toEpochMilli(), windows.current.startInclusive)
        assertEquals(Instant.parse("2026-02-24T05:00:00Z").toEpochMilli(), requireNotNull(windows.previous).startInclusive)
        assertEquals(windows.current.startInclusive, requireNotNull(windows.previous).endExclusive)
        val fall = AnalyticsTimeContext(Instant.parse("2026-11-02T05:00:00Z"), ZoneId.of("America/New_York"))
        assertEquals(Instant.parse("2026-10-27T04:00:00Z").toEpochMilli(), service.windows(TrendsRange.SEVEN_DAYS, fall).current.startInclusive)
        val thirty = service.windows(TrendsRange.THIRTY_DAYS, time)
        assertEquals(Instant.parse("2026-09-07T16:00:00Z").toEpochMilli(), thirty.current.startInclusive)
        val ninety = service.windows(TrendsRange.NINETY_DAYS, time)
        assertEquals(Instant.parse("2026-07-09T16:00:00Z").toEpochMilli(), ninety.current.startInclusive)
        assertFalse(thirty.current == service.windows(TrendsRange.THIRTY_DAYS, time.copy(zoneId = ZoneId.of("UTC"))).current)
    }

    @Test fun currentIncludesSnapshotInstantAndExcludesFuture() {
        val now = time.now.toEpochMilli()
        val result = trends(starts = listOf(start("at").copy(intent = intent("at").copy(createdAt = now, outcome = IntentOutcome.ABANDONED)),
            start("future").copy(intent = intent("future").copy(createdAt = now + 1))), range = TrendsRange.SEVEN_DAYS)
        assertEquals(1L, result.current.start.abandonedCount)
        assertEquals(0L, result.current.start.convertedCount)
        assertTrue(result.previous != null)
    }

    @Test fun comparisonUsesAbsoluteCountsAndPercentagePoints() {
        val currentDate = Instant.parse("2026-10-06T00:00:00Z").toEpochMilli()
        val previousDate = Instant.parse("2026-09-28T00:00:00Z").toEpochMilli()
        fun dated(id: String, outcome: IntentOutcome, date: Long) = start(id, outcome).copy(intent = intent(id).copy(createdAt = date, outcome = outcome),
            linkedSessions = listOf(session().copy(intentId = id, startedAt = date + 10)))
        val result = trends(starts = listOf(dated("c", IntentOutcome.CONVERTED, currentDate), dated("p", IntentOutcome.CONVERTED, previousDate),
            dated("pa", IntentOutcome.ABANDONED, previousDate)), range = TrendsRange.SEVEN_DAYS)
        val delta = requireNotNull(result.comparison)
        assertEquals(0L, delta.convertedCountDelta)
        assertEquals(-1L, delta.resolvedIntentCountDelta)
        assertEquals(50.0, requireNotNull(delta.conversionPercentagePointDelta), 0.0)
    }

    @Test fun zeroDenominatorIsUnavailableAndNeverInfinity() {
        val date = Instant.parse("2026-10-06T00:00:00Z").toEpochMilli()
        val input = start("c").copy(intent = intent("c").copy(createdAt = date), linkedSessions = listOf(session().copy(intentId = "c", startedAt = date + 10)))
        val result = trends(starts = listOf(input), range = TrendsRange.SEVEN_DAYS)
        assertEquals(1L, result.current.start.convertedCount)
        assertNull(requireNotNull(result.previous).start.conversion.value)
        assertNull(requireNotNull(result.comparison).conversionPercentagePointDelta)
        assertEquals(1L, requireNotNull(result.comparison).convertedCountDelta)
    }

    @Test fun allHistoryHasNoInventedPreviousOrComparison() {
        val result = trends(starts = listOf(start("c")))
        assertEquals(1L, result.current.start.convertedCount)
        assertNull(result.windows.current.startInclusive)
        assertNull(result.previous)
        assertNull(result.comparison)
    }

    @Test fun sumOverflowMakesDurationUnavailableWithoutSaturation() {
        val hugeTime = time.copy(now = Instant.ofEpochMilli(Long.MAX_VALUE))
        val end = Long.MAX_VALUE - 1
        val huge = source().copy(session = session().copy(startedAt = 0, endedAt = end),
            segments = listOf(segment("f", SessionSegmentType.FOCUS, 0, end)))
        val result = trends(sessions = listOf(huge, huge.copy(session = huge.session.copy(id = "s2"),
            context = context().copy(sessionId = "s2"), segments = huge.segments.map { it.copy(sessionId = "s2") })), snapshot = hugeTime).current.maintain
        assertEquals(2L, result.trustedSessionCount)
        assertNull(result.effectiveFocusMillis)
        assertTrue(result.durationOverflow)
        assertEquals(0L, result.deepFocusMillis)
    }

    private fun trends(starts: List<StartTrendSource> = emptyList(), sessions: List<SessionTrendSource> = emptyList(),
        range: TrendsRange = TrendsRange.ALL, snapshot: AnalyticsTimeContext = time): TrendsSnapshot {
        val accumulator = service.accumulator(range, snapshot)
        accumulator.addStartPage(starts)
        accumulator.addSessionPage(sessions)
        return accumulator.finish()
    }

    private fun intent(id: String = "i") = StudyIntentEntity(id, "book", 0, null, 10, 10, IntentOutcome.CONVERTED, null)
    private fun start(id: String, outcome: IntentOutcome? = IntentOutcome.CONVERTED, latency: Long = 10) =
        StartTrendSource(intent(id).copy(outcome = outcome), listOf(session().copy(intentId = id, startedAt = latency)))
    private fun session() = StudySessionEntity("s", "book", "i", 10, null, 300, 1, 2, 2, SessionEndType.NORMAL, null, null)
    private fun context() = SessionFocusContextEntity(sessionId = "s", monitoringStatus = MonitoringCoverage.FULL,
        monitoringLostAt = null, priorDndInterruptionFilter = null, dndRuleId = null, dndLifecycle = DndLifecycle.NOT_APPLIED,
        closeoutState = FocusCloseoutState.COMPLETED, requestedEndPage = null, closeoutStartedAt = null,
        lastHeartbeatAt = 300, createdAt = 10, updatedAt = 300)
    private fun segment(id: String, type: SessionSegmentType, start: Long, end: Long, related: String? = null) =
        SessionSegmentEntity(id, "s", type, start, end, null, null, null, relatedSegmentId = related, activeSlot = null)
    private fun event(id: String, type: FocusEventType, occurredAt: Long, segmentId: String) =
        FocusEventEntity(id, "s", type, occurredAt, null, segmentId, null)
    private fun source(context: SessionFocusContextEntity? = context(),
        segments: List<SessionSegmentEntity> = listOf(segment("f", SessionSegmentType.FOCUS, 10, 300)),
        events: List<FocusEventEntity> = emptyList()) = SessionTrendSource(session(), context, segments, events)
    private fun successSource() = source(segments = listOf(segment("d", SessionSegmentType.DISTRACTION, 10, 100),
        segment("r", SessionSegmentType.RECOVERY, 100, 200, "d"), segment("f", SessionSegmentType.FOCUS, 200, 300)),
        events = listOf(event("success", FocusEventType.RECOVERY_SUCCEEDED, 200, "f")))
    private fun interruptedSource() = source(segments = listOf(segment("d", SessionSegmentType.DISTRACTION, 10, 100),
        segment("r", SessionSegmentType.RECOVERY, 100, 190, "d"), segment("d2", SessionSegmentType.DISTRACTION, 190, 300)),
        events = listOf(event("interrupted", FocusEventType.RECOVERY_INTERRUPTED, 210, "r")))
}
