package com.guanyi.mirra.domain.monitoring

import org.junit.Assert.*
import org.junit.Test

class CandidateRiskAppMachineTest {
    private fun machine() = CandidateRiskAppMachine { name ->
        when (name) { "risk", "risk2" -> PackageClass.RISK; "launcher", "systemui", "settings", "mirra" -> PackageClass.SYSTEM; else -> PackageClass.NORMAL }
    }

    @Test fun `candidate confirms at ten monotonic seconds with later query evidence once`() {
        val machine = machine()
        machine.accept(input("risk", 1_000, 1))
        val candidate = machine.state as RiskCandidateState.Candidate
        assertEquals(1_000L, candidate.firstSeenElapsed)
        assertEquals("segment-42", candidate.sourceFocusSegmentId)
        assertTrue(machine.accept(input("risk", 10_900, 2)).isEmpty())
        val confirmed = machine.accept(input("risk", 11_000, 3)).single() as RiskDecision.Confirmed
        assertEquals(candidate.token, confirmed.token)
        assertEquals("segment-42", confirmed.sourceFocusSegmentId)
        assertEquals(1_000L, confirmed.firstSeenElapsed)
        assertTrue(machine.accept(input("risk", 12_000, 4)).isEmpty())
        assertTrue(machine.state is RiskCandidateState.Confirmed)
    }

    @Test fun `query count and wall time do not substitute monotonic duration`() {
        val machine = machine()
        machine.accept(input("risk", 1_000, 1))
        for (i in 2..11) assertTrue(machine.accept(input("risk", 1_000L + i * 500, i.toLong())).isEmpty())
        assertTrue(machine.state is RiskCandidateState.Candidate)
        val noLaterQuery = machine(); noLaterQuery.accept(input("risk", 1_000, 1))
        assertTrue(noLaterQuery.accept(input("risk", 11_000, 1)).isEmpty())
        val sparse = machine(); sparse.accept(input("risk", 1_000, 1))
        listOf(4_000L, 7_000L, 9_000L).forEachIndexed { index, elapsed ->
            assertTrue(sparse.accept(input("risk", elapsed, index + 2L)).isEmpty())
        }
        assertEquals(1, sparse.accept(input("risk", 16_000, 5)).size) // 15 seconds, five queries
    }

    @Test fun `switching risk package starts a new token and normal or system clears it`() {
        val machine = machine()
        machine.accept(input("risk", 1_000, 1))
        val old = (machine.state as RiskCandidateState.Candidate).token
        machine.accept(input("risk2", 5_000, 2))
        val next = machine.state as RiskCandidateState.Candidate
        assertNotEquals(old, next.token)
        assertEquals(5_000L, next.firstSeenElapsed)
        machine.accept(input("launcher", 6_000, 3))
        assertTrue(machine.state is RiskCandidateState.Idle)
        machine.accept(input("risk", 7_000, 4))
        machine.accept(input("normal", 8_000, 5))
        assertTrue(machine.state is RiskCandidateState.Idle)
    }

    @Test fun `screen lock unknown unavailable stale and discontinuous evidence clear candidate`() {
        val cases = listOf<ForegroundObservation>(ForegroundObservation.ScreenOff(2_000), ForegroundObservation.DeviceLocked(2_000), ForegroundObservation.Unknown("unknown"), ForegroundObservation.MonitoringUnavailable("revoked"))
        cases.forEach { observation ->
            val machine = machine(); machine.accept(input("risk", 1_000, 1))
            machine.accept(CandidateInput(observation, 2_000, 2, "segment-42", true, true))
            assertTrue(machine.state is RiskCandidateState.Idle)
        }
        val stale = machine(); stale.accept(input("risk", 1_000, 1))
        stale.accept(input("risk", 2_000, 2).copy(foregroundFresh = false))
        assertTrue(stale.state is RiskCandidateState.Idle)
        val gap = machine(); gap.accept(input("risk", 1_000, 1))
        gap.accept(input("risk", 2_000, 2).copy(queryContinuous = false))
        assertTrue(gap.state is RiskCandidateState.Idle)
    }

    @Test fun `changed source segment invalidates candidate without carrying elapsed time`() {
        val machine = machine(); machine.accept(input("risk", 1_000, 1))
        assertTrue(machine.accept(input("risk", 12_000, 2).copy(sourceFocusSegmentId = "segment-43")).isEmpty())
        assertTrue(machine.state is RiskCandidateState.Idle)
    }

    private fun input(name: String, elapsed: Long, query: Long) = CandidateInput(
        ForegroundObservation.Package(name, elapsed, 1_500), elapsed, query, "segment-42", true, true,
    )
}
