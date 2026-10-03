package com.guanyi.mirra.domain.monitoring

import com.guanyi.mirra.data.local.entity.*
import org.junit.Assert.*
import org.junit.Test

class BehaviorPolicyTest {
    private val context = SessionFocusContextEntity("s", monitoringStatus = MonitoringCoverage.FULL,
        monitoringLostAt = null, priorDndInterruptionFilter = null, dndRuleId = null,
        requestedEndPage = null, closeoutStartedAt = null, lastHeartbeatAt = 0, createdAt = 0, updatedAt = 0)

    @Test fun secondConfirmationWaitsFiveSecondsAndThirdAndLaterCapAtFifteen() {
        assertEquals(listOf(0L, 5_000L, 15_000L, 15_000L),
            (1..4).map { InterventionPolicy.waitMillis(it, context) })
    }
    @Test fun frictionCountsOnlyVisibleTimeAndChangingReasonResetsWait() {
        val wait = FrictionWait(5_000)
        wait.update(0, true)
        wait.update(2_000, false)
        wait.update(50_000, true)
        assertEquals(3_000L, wait.remaining(50_000))
        wait.update(53_000, true)
        assertEquals(0L, wait.remaining(53_000))
    }
    @Test fun unknownCannotCompleteRecoveryAndNewPositiveWindowMustReachNinetySeconds() {
        val tracker = StableEvidenceTracker()
        tracker.accept("r", SessionSegmentType.RECOVERY, MonitoringCoverage.FULL, 0, true, true, false)
        assertNull(tracker.accept("r", SessionSegmentType.RECOVERY, MonitoringCoverage.FULL,
            89_900, true, true, false).recovery)
        tracker.accept("r", SessionSegmentType.RECOVERY, MonitoringCoverage.FULL, 90_000, true, false, false)
        tracker.accept("r", SessionSegmentType.RECOVERY, MonitoringCoverage.FULL, 91_000, true, true, false)
        assertNull(tracker.accept("r", SessionSegmentType.RECOVERY, MonitoringCoverage.FULL,
            180_900, true, true, false).recovery)
        assertNotNull(tracker.accept("r", SessionSegmentType.RECOVERY, MonitoringCoverage.FULL,
            181_000, true, true, false).recovery)
    }
    @Test fun partialCanRecoverButCannotProduceStableOrDeepMilestones() {
        val t = StableEvidenceTracker()
        t.accept("r", SessionSegmentType.RECOVERY, MonitoringCoverage.PARTIAL, 0, true, true, true)
        assertNotNull(t.accept("r", SessionSegmentType.RECOVERY, MonitoringCoverage.PARTIAL,
            90_000, true, true, true).recovery)
        t.accept("f", SessionSegmentType.FOCUS, MonitoringCoverage.PARTIAL, 90_000, true, true, true)
        val result = t.accept("f", SessionSegmentType.FOCUS, MonitoringCoverage.PARTIAL,
            1_000_000, true, true, true)
        assertNull(result.stable)
        assertNull(result.deep)
    }
    @Test fun stableAndDeepRequireIndependentPositiveAndScreenOffWindows() {
        val t = StableEvidenceTracker()
        t.accept("f", SessionSegmentType.FOCUS, MonitoringCoverage.FULL, 0, true, true, false)
        assertNull(t.accept("f", SessionSegmentType.FOCUS, MonitoringCoverage.FULL,
            119_999, true, true, false).stable)
        assertNotNull(t.accept("f", SessionSegmentType.FOCUS, MonitoringCoverage.FULL,
            120_000, true, true, false).stable)
        t.accept("f", SessionSegmentType.FOCUS, MonitoringCoverage.FULL, 300_000, true, true, true)
        assertNull(t.accept("f", SessionSegmentType.FOCUS, MonitoringCoverage.FULL,
            899_999, true, true, true).deep)
        assertNotNull(t.accept("f", SessionSegmentType.FOCUS, MonitoringCoverage.FULL,
            900_000, true, true, true).deep)
    }
    @Test fun gapAndSegmentChangeInvalidateProofAndNoneNeverSucceeds() {
        val t = StableEvidenceTracker()
        t.accept("r", SessionSegmentType.RECOVERY, MonitoringCoverage.FULL, 0, true, true, true)
        assertNull(t.accept("r", SessionSegmentType.RECOVERY, MonitoringCoverage.FULL,
            90_000, false, true, true).recovery)
        assertNull(t.accept("other", SessionSegmentType.RECOVERY, MonitoringCoverage.FULL,
            100_000, true, true, true).recovery)
        assertNull(t.accept("other", SessionSegmentType.RECOVERY, MonitoringCoverage.NONE,
            200_000, true, true, true).recovery)
    }
}
