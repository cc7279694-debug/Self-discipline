package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.*
import org.junit.Assert.*
import org.junit.Test

class BehaviorTransitionsTest {
    private val machine = SessionSegmentStateMachine()
    private fun state(type: SessionSegmentType) = SegmentMachineState(0, null, 1, type,
        MonitoringCoverage.FULL, null, 120_000, 90_000, 1_000)

    @Test fun distractionAndRecoveryCanEnterAllowanceOrBreakWithoutFakeFocus() {
        for (source in listOf(SessionSegmentType.DISTRACTION, SessionSegmentType.RECOVERY)) {
            for (target in listOf(SessionSegmentType.TEMPORARY_ALLOWANCE, SessionSegmentType.BREAK)) {
                assertEquals(target, machine.transition(state(source), target, 1_000).nextType)
            }
        }
    }
    @Test fun breakEndsInRecoveryNotFocus() {
        assertEquals(SessionSegmentType.RECOVERY,
            machine.transition(state(SessionSegmentType.BREAK), SessionSegmentType.RECOVERY, 1_000).nextType)
        try { machine.transition(state(SessionSegmentType.BREAK), SessionSegmentType.FOCUS, 1_000)
            fail("Countdown alone cannot establish focus") } catch (_: IllegalStateException) { }
    }
    @Test fun allowanceCanBeAtomicallyReplacedByBreak() {
        assertEquals(SessionSegmentType.BREAK, machine.transition(
            state(SessionSegmentType.TEMPORARY_ALLOWANCE), SessionSegmentType.BREAK, 1_000).nextType)
    }
}
