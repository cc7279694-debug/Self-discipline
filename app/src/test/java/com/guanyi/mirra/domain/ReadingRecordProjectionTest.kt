package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.local.model.ReadingRecordSource
import org.junit.Assert.*
import org.junit.Test

class ReadingRecordProjectionTest {
    private val service = ReadingRecordService()
    @Test fun continuousFocusAndDeepFocusMergeOnlyInPresentation() {
        val original = listOf(seg(0, 10), seg(10, 20, SessionSegmentType.DEEP_FOCUS))
        val source = source(original)
        val before = SessionTimelineValidator().analyze(source.session, source.context, original)
        val record = service.project(source)
        assertEquals(listOf(HumanReadingInterval(HumanReadingSegment.READING, 0, 20, null)), record.timeline)
        assertEquals(20L, record.effectiveFocusMillis)
        assertEquals(SessionSegmentType.DEEP_FOCUS, original[1].type)
        assertEquals(before, SessionTimelineValidator().analyze(source.session, source.context, source.segments))
        assertEquals(2, service.project(source(listOf(seg(0, 9), seg(10, 20)))).timeline.size)
        assertEquals(3, service.project(source(listOf(seg(0, 5), seg(5, 10, SessionSegmentType.BREAK), seg(10, 20)))).timeline.size)
    }
    @Test fun countsComeFromSegmentsNotFocusEventsOrClicks() {
        val record = service.project(source(listOf(seg(0, 2, SessionSegmentType.BREAK),
            seg(2, 4, SessionSegmentType.BREAK), seg(4, 6, SessionSegmentType.TEMPORARY_ALLOWANCE),
            seg(6, 8, SessionSegmentType.DISTRACTION), seg(8, 10, SessionSegmentType.RECOVERY), seg(10, 20))))
        assertEquals(ReadingSegmentCounts(2, 1, 1), record.wholeSessionCounts)
        assertEquals(10L, record.effectiveFocusMillis)
        assertEquals(HumanReadingSegment.TEMPORARY_USE, record.timeline[2].type)
        assertEquals(HumanReadingSegment.RECOVERING, record.timeline[4].type)
    }
    @Test fun skippedMalformedSegmentStillSeparatesReadingIntervals() {
        for (separator in listOf(seg(10, 10, SessionSegmentType.BREAK),
            seg(10, 10, SessionSegmentType.BREAK).copy(endedAt = null))) {
            val record = service.project(source(listOf(seg(0, 10), separator, seg(10, 20, SessionSegmentType.DEEP_FOCUS))))
            assertEquals(TimelineTrust.STRUCTURE_INVALID, record.trust)
            assertEquals(2, record.timeline.size)
        }
    }
    @Test fun partialTimelineNeverClaimsWholeSessionCounts() {
        for (coverage in listOf(MonitoringCoverage.PARTIAL, MonitoringCoverage.NONE)) {
            val value = source(listOf(seg(0, 20, SessionSegmentType.BREAK)))
            val record = service.project(value.copy(context = value.context!!.copy(monitoringStatus = coverage)))
            assertNull(record.wholeSessionCounts); assertNull(record.effectiveFocusMillis)
            assertEquals(coverage, record.monitoringStatus); assertEquals(1, record.timeline.size)
        }
        val invalid = service.project(source(listOf(seg(0, 10), seg(9, 20))))
        assertEquals(TimelineTrust.STRUCTURE_INVALID, invalid.trust)
        assertNull(invalid.wholeSessionCounts); assertNull(invalid.effectiveFocusMillis)
        assertEquals(9L, invalid.timeline[1].startedAt)
    }
    @Test fun trustedZeroIsDistinctFromUnavailable() {
        val zero = service.project(source(listOf(seg(0, 20, SessionSegmentType.BREAK))))
        assertEquals(TimelineTrust.COMPLETE_TRUSTED, zero.trust)
        assertEquals(0L, zero.effectiveFocusMillis)
        assertEquals(ReadingSegmentCounts(1, 0, 0), zero.wholeSessionCounts)
        val unmonitored = service.project(source(listOf(seg(0, 20, SessionSegmentType.UNMONITORED))))
        assertNull(unmonitored.effectiveFocusMillis)
        assertEquals(HumanReadingSegment.UNMONITORED, unmonitored.timeline.single().type)
    }
    @Test fun riskLabelsUseHistoricalSnapshot() {
        val value = source(listOf(seg(0, 20, SessionSegmentType.DISTRACTION).copy(packageName = "com.risk")))
        val record = service.project(value.copy(riskSnapshots = listOf(SessionRiskAppSnapshotEntity("s", "com.risk", "旧名称"))))
        assertEquals("旧名称", record.timeline.single().appLabel)
        assertEquals("风险 App", service.project(value.copy(riskSnapshots = listOf(SessionRiskAppSnapshotEntity("s", "com.risk", "  ")))).timeline.single().appLabel)
        assertEquals("风险 App", service.project(value).timeline.single().appLabel)
    }
    @Test fun malformedTimelineIsMarkedAndNeverRepaired() {
        val segments = listOf(seg(-5, 5), seg(10, 30), seg(30, 30), seg(30, 40).copy(endedAt = null, activeSlot = 1))
        val record = service.project(source(segments))
        assertEquals(TimelineTrust.STRUCTURE_INVALID, record.trust)
        assertEquals(listOf(-5L, 10L), record.timeline.map { it.startedAt })
        assertEquals(listOf(5L, 30L), record.timeline.map { it.endedAt })
        assertEquals(4, segments.size)
    }
    @Test fun oldRecordRetainsPagesDurationAndNotesWithoutFakeFocus() {
        val old = source(emptyList()).copy(context = null, noteCount = 2)
        val record = service.project(old)
        assertEquals(4L, record.pagesRead); assertEquals(20L, record.totalDurationMillis)
        assertEquals(2, record.noteCount); assertTrue(record.timeline.isEmpty())
        assertNull(record.effectiveFocusMillis); assertNull(record.wholeSessionCounts)
        val abnormal = service.project(old.copy(session = old.session.copy(endType = SessionEndType.ABNORMAL)))
        assertEquals(TimelineTrust.SESSION_INELIGIBLE, abnormal.trust)
    }
    @Test fun unavailableEndAndOverflowNeverBecomeZeroDuration() {
        val value = source(emptyList())
        assertNull(service.project(value.copy(session = value.session.copy(endedAt = null, endPage = null))).totalDurationMillis)
        val overflow = value.copy(session = value.session.copy(startedAt = Long.MIN_VALUE, endedAt = Long.MAX_VALUE,
            startPage = Int.MIN_VALUE, endPage = Int.MAX_VALUE))
        assertNull(service.project(overflow).totalDurationMillis)
        assertEquals(4_294_967_295L, service.project(overflow).pagesRead)
    }
    private fun source(segments: List<SessionSegmentEntity>) = ReadingRecordSource(
        StudySessionEntity("s", "book", "i", 0, null, 20, 1, 5, 5, SessionEndType.NORMAL, null, null),
        SessionFocusContextEntity(sessionId = "s", monitoringStatus = MonitoringCoverage.FULL, monitoringLostAt = null,
            priorDndInterruptionFilter = null, dndRuleId = null, requestedEndPage = null, closeoutStartedAt = null,
            lastHeartbeatAt = 20, createdAt = 0, updatedAt = 20),
        segments, emptyList(), 0)
    private fun seg(start: Long, end: Long, type: SessionSegmentType = SessionSegmentType.FOCUS) =
        SessionSegmentEntity("$start-$end-$type", "s", type, start, end, null, null, null, relatedSegmentId = null, activeSlot = null)
}
