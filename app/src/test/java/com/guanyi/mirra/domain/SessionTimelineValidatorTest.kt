package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTimelineValidatorTest {
    private val validator = SessionTimelineValidator()

    @Test fun exactClosedContinuousTimelineIsTrusted() {
        val original = listOf(segment(10, 20), segment(0, 10))
        val result = validator.analyze(session(), context(), original)
        assertEquals(TimelineTrust.COMPLETE_TRUSTED, result.trust)
        assertEquals(20L, result.effectiveFocusMillis)
        assertEquals(10L, original.first().startedAt) // Sorting must not mutate caller facts.
    }

    @Test fun onlyFocusAndDeepFocusContributeWithoutWeights() {
        val types = SessionSegmentType.entries.filter { it != SessionSegmentType.UNMONITORED }
        val segments = types.mapIndexed { i, type -> segment(i * 10L, (i + 1) * 10L, type) }
        val result = validator.analyze(session(end = 60), context(), segments)
        assertEquals(TimelineTrust.COMPLETE_TRUSTED, result.trust)
        assertEquals(20L, result.effectiveFocusMillis)
        assertEquals(1, result.breakCount)
        assertEquals(1, result.allowanceCount)
        assertEquals(1, result.distractionCount)
        assertNull(validator.analyze(session(end = 70), context(),
            segments + segment(60, 70, SessionSegmentType.UNMONITORED)).effectiveFocusMillis)
    }

    @Test fun fullFlagCannotOverrideBrokenTimelineOrLostCoverage() {
        val invalid = listOf(
            listOf(segment(0, 9), segment(10, 20)),
            listOf(segment(0, 11), segment(10, 20)),
            listOf(segment(1, 20)),
            listOf(segment(0, 19)),
            listOf(segment(0, 20).copy(endedAt = null, activeSlot = 1)),
            listOf(segment(0, 20).copy(activeSlot = 1)),
            listOf(segment(0, 0), segment(0, 20)),
            listOf(segment(0, 10), segment(10, 9), segment(9, 20)),
            listOf(segment(-1, 20)),
            listOf(segment(0, 21)),
        )
        invalid.forEachIndexed { i, segments ->
            val result = validator.analyze(session(), context(), segments)
            assertEquals("case $i", TimelineTrust.STRUCTURE_INVALID, result.trust)
            assertNull(result.effectiveFocusMillis)
        }
        val lost = validator.analyze(session(), context().copy(monitoringLostAt = 10), listOf(segment(0, 20)))
        assertEquals(TimelineTrust.MONITORING_INCOMPLETE, lost.trust)
        assertNull(lost.effectiveFocusMillis)
    }

    @Test fun unmonitoredPartialNoneAndMissingContextAreIncomplete() {
        listOf(null, context().copy(monitoringStatus = MonitoringCoverage.PARTIAL),
            context().copy(monitoringStatus = MonitoringCoverage.NONE)).forEach { context ->
            val result = validator.analyze(session(), context, listOf(segment(0, 20)))
            assertEquals(TimelineTrust.MONITORING_INCOMPLETE, result.trust)
            assertNull(result.effectiveFocusMillis)
        }
        val result = validator.analyze(session(), context(), listOf(segment(0, 20, SessionSegmentType.UNMONITORED)))
        assertEquals(TimelineTrust.MONITORING_INCOMPLETE, result.trust)
        assertNull(result.effectiveFocusMillis)
    }

    @Test fun abnormalActiveAndNonPositiveSessionAreIneligible() {
        val invalid = SessionEndType.entries.filter { it != SessionEndType.NORMAL }.map { session().copy(endType = it) } +
            listOf(session().copy(endType = null, endedAt = null, activeSlot = 1),
                session(end = 0), session(end = -1))
        invalid.forEach {
            val result = validator.analyze(it, null, emptyList())
            assertEquals(TimelineTrust.SESSION_INELIGIBLE, result.trust)
            assertNull(result.effectiveFocusMillis)
        }
    }

    @Test fun oldSessionWithoutSegmentsDoesNotInventEffectiveTime() {
        val original = session()
        assertEquals(TimelineTrust.MONITORING_INCOMPLETE, validator.analyze(original, null, emptyList()).trust)
        assertEquals(TimelineTrust.STRUCTURE_INVALID, validator.analyze(original, context(), emptyList()).trust)
        assertEquals(session(), original)
    }

    @Test fun trustedZeroIsAValueButUnavailableIsNull() {
        val segments = listOf(segment(0, 5, SessionSegmentType.BREAK),
            segment(5, 10, SessionSegmentType.TEMPORARY_ALLOWANCE), segment(10, 20, SessionSegmentType.RECOVERY))
        val trusted = validator.analyze(session(), context(), segments)
        assertEquals(TimelineTrust.COMPLETE_TRUSTED, trusted.trust)
        assertEquals(0L, trusted.effectiveFocusMillis)
        assertEquals(1, trusted.breakCount)
        val incomplete = validator.analyze(session(), context().copy(monitoringStatus = MonitoringCoverage.PARTIAL), segments)
        assertNull(incomplete.effectiveFocusMillis)
        assertEquals(1, incomplete.breakCount)
        assertEquals(1, incomplete.allowanceCount)
    }

    @Test fun overflowInAnyIntervalCannotProduceTrustedTime() {
        // Even a non-focus interval must have representable duration.
        SessionSegmentType.entries.filter { it != SessionSegmentType.UNMONITORED }.forEach { type ->
            val result = validator.analyze(session(start = Long.MIN_VALUE, end = Long.MAX_VALUE), context(),
                listOf(segment(Long.MIN_VALUE, Long.MAX_VALUE, type)))
            assertEquals(TimelineTrust.STRUCTURE_INVALID, result.trust)
            assertNull(result.effectiveFocusMillis)
        }
    }

    private fun session(start: Long = 0, end: Long = 20) = StudySessionEntity(
        "s", "book", "intent", start, null, end, 1, 2, 2, SessionEndType.NORMAL, null, null,
    )

    private fun context() = SessionFocusContextEntity(
        sessionId = "s", monitoringStatus = MonitoringCoverage.FULL, monitoringLostAt = null,
        priorDndInterruptionFilter = null, dndRuleId = null, requestedEndPage = null,
        closeoutStartedAt = null, lastHeartbeatAt = 20, createdAt = 0, updatedAt = 20,
    )

    private fun segment(start: Long, end: Long, type: SessionSegmentType = SessionSegmentType.FOCUS) =
        SessionSegmentEntity("$start-$end-$type", "s", type, start, end, null, null, null,
            relatedSegmentId = null, activeSlot = null)
}
