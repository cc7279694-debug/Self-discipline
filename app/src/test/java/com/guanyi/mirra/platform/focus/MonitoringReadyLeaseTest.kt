package com.guanyi.mirra.platform.focus

import com.guanyi.mirra.domain.monitoring.ClockSample
import com.guanyi.mirra.domain.monitoring.ForegroundObservation
import org.junit.Assert.*
import org.junit.Test

class MonitoringReadyLeaseTest {
    private val sample = ClockSample(1_000_000, 20_000)

    @Test fun `ready lease uses one query clock and does not require a foreground package`() {
        val state = readyState()
        val lease = ReadyLeasePolicy().capture(state, nowElapsed = 20_100)
        assertNotNull(lease)
        assertEquals(1_000_000L, lease!!.readyAtWall)
        assertEquals(20_000L, lease.readyAtElapsed)
        assertEquals("generation-1", lease.generation)
        assertEquals(5L, lease.successfulQueryGeneration)
        assertTrue(ReadyLeasePolicy().isValid(lease, state, nowElapsed = 20_200))
    }

    @Test fun `expired generation changed and broken continuity invalidate a lease`() {
        val policy = ReadyLeasePolicy()
        val state = readyState()
        val lease = policy.capture(state, 20_100)!!
        assertFalse(policy.isValid(lease, state, 22_001))
        assertFalse(policy.isValid(lease, state.copy(service = state.service.copy(generation = "other")), 20_200))
        assertFalse(policy.isValid(lease, state.copy(monitor = state.monitor.copy(continuityEpoch = 2)), 20_200))
        assertFalse(policy.isValid(lease, state.copy(monitor = state.monitor.copy(running = false)), 20_200))
        assertFalse(policy.isValid(lease, state.copy(usage = CapabilityStatus.NEEDS_USER_ACTION), 20_200))
    }

    private fun readyState() = MonitoringCapabilities(
        usage = CapabilityStatus.AVAILABLE,
        service = ServiceState(ServicePhase.MONITOR_READY, "generation-1", foregroundAck = true),
        monitor = MonitorSnapshot(
            running = true, queryGeneration = 5, cursorWallMillis = 1_000_000,
            lastSuccessfulQueryElapsed = 20_000, lastSuccessfulQueryClock = sample,
            continuityEpoch = 1,
            observation = ForegroundObservation.Unknown("empty query"),
        ),
    )
}
