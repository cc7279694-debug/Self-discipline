package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSegmentStateMachineTest {
    private val machine = SessionSegmentStateMachine()

    @Test
    fun `transition closes and opens segments at one shared boundary`() {
        val decision = machine.transition(state(), SessionSegmentType.BREAK, at = 200)

        assertEquals(200, decision.closeCurrentAt)
        assertEquals(200, decision.openNextAt)
        assertEquals(SessionSegmentType.BREAK, decision.nextType)
    }

    @Test
    fun `zero duration backwards out of bounds and ended session transitions are rejected`() {
        listOf(99L, 100L, 301L).forEach { at ->
            assertFails { machine.transition(state(), SessionSegmentType.BREAK, at) }
        }
        assertFails {
            machine.transition(state(sessionEndedAt = 250), SessionSegmentType.BREAK, 200)
        }
    }

    @Test
    fun `partial never returns to full while initially none can become partial`() {
        assertEquals(
            MonitoringCoverage.PARTIAL,
            machine.degradeCoverage(MonitoringCoverage.FULL, MonitoringCoverage.PARTIAL),
        )
        assertEquals(
            MonitoringCoverage.NONE,
            machine.degradeCoverage(MonitoringCoverage.PARTIAL, MonitoringCoverage.NONE),
        )
        assertFails { machine.degradeCoverage(MonitoringCoverage.PARTIAL, MonitoringCoverage.FULL) }
        assertEquals(MonitoringCoverage.PARTIAL, machine.degradeCoverage(MonitoringCoverage.NONE, MonitoringCoverage.PARTIAL))
        assertFails { machine.degradeCoverage(MonitoringCoverage.NONE, MonitoringCoverage.FULL) }
    }

    @Test
    fun `unmonitored is never focus and monitoring gap opens it`() {
        assertTrue(!SessionSegmentType.UNMONITORED.countsAsFocus)
        val decision = machine.monitoringGap(state(), lastTrustedAt = 180, detectedAt = 240)
        assertEquals(SessionSegmentType.UNMONITORED, decision.nextType)
        assertEquals(180, decision.closeCurrentAt)
        assertEquals(180, decision.openNextAt)
        assertEquals(MonitoringCoverage.PARTIAL, decision.coverage)
    }

    @Test
    fun `recovery and stable start milestones require full trusted coverage`() {
        val recovery = machine.completeRecovery(
            state(type = SessionSegmentType.RECOVERY),
            at = 200,
        )
        assertEquals(SessionSegmentType.FOCUS, recovery.nextType)
        assertTrue(recovery.recordRecoverySucceeded)
        assertFails { machine.completeRecovery(state(type = SessionSegmentType.RECOVERY), at = 199) }

        assertTrue(machine.canMarkStableStart(state(type = SessionSegmentType.FOCUS), at = 220))
        assertTrue(!machine.canMarkStableStart(state(type = SessionSegmentType.FOCUS), at = 199))
        assertTrue(!machine.canMarkStableStart(state(type = SessionSegmentType.UNMONITORED), at = 220))
        assertTrue(!machine.canMarkStableStart(state(coverage = MonitoringCoverage.PARTIAL), at = 220))
        assertEquals(
            SessionSegmentType.FOCUS,
            machine.completeRecovery(state(type = SessionSegmentType.RECOVERY, coverage = MonitoringCoverage.PARTIAL), 200).nextType,
        )
        assertFails { machine.transition(state(type = SessionSegmentType.BREAK), SessionSegmentType.DEEP_FOCUS, 200) }
    }

    private fun state(
        type: SessionSegmentType = SessionSegmentType.FOCUS,
        coverage: MonitoringCoverage = MonitoringCoverage.FULL,
        sessionEndedAt: Long? = null,
    ) = SegmentMachineState(
        sessionStartedAt = 50,
        sessionEndedAt = sessionEndedAt,
        activeSegmentStartedAt = 100,
        activeSegmentType = type,
        coverage = coverage,
        stableStartedAt = null,
        stableStartMillis = 100,
        recoveryStableMillis = 100,
        now = 300,
    )

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected failure")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }
}
