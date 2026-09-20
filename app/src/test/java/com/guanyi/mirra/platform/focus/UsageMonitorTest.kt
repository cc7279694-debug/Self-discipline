package com.guanyi.mirra.platform.focus

import com.guanyi.mirra.domain.monitoring.ClockSample
import com.guanyi.mirra.domain.monitoring.ForegroundObservation
import com.guanyi.mirra.domain.monitoring.MonitoringSignal
import com.guanyi.mirra.domain.monitoring.UsageEventFact
import com.guanyi.mirra.domain.monitoring.UsageEventKind
import org.junit.Assert.*
import org.junit.Test

class UsageMonitorTest {
    @Test fun `successful empty query establishes readiness without inventing package`() {
        val source = FakeSource()
        val monitor = UsageMonitor(source, overlapMillis = 3_000)
        monitor.poll(ClockSample(10_000, 1_000))
        assertEquals(7_000L to 10_000L, source.windows.single())
        assertEquals(1L, monitor.state.value.queryGeneration)
        assertTrue(monitor.state.value.observation is ForegroundObservation.Unknown)
        assertEquals(1_000L, monitor.state.value.lastSuccessfulQueryElapsed)
        assertTrue(monitor.state.value.running)
    }

    @Test fun `overlap deduplicates Android-like event and query generation advances`() {
        val source = FakeSource().apply { events = listOf(UsageEventFact(9_000, UsageEventKind.ACTIVITY_RESUMED, "reader")) }
        val monitor = UsageMonitor(source)
        monitor.poll(ClockSample(10_000, 1_000))
        monitor.poll(ClockSample(11_000, 2_000))
        assertEquals(7_000L to 11_000L, source.windows.last())
        assertEquals(2L, monitor.state.value.queryGeneration)
        assertEquals(1_000L, monitor.state.value.lastForegroundEvidenceElapsed)
    }

    @Test fun `failed query never advances generation and drops ready`() {
        val source = FakeSource()
        val monitor = UsageMonitor(source)
        monitor.poll(ClockSample(10_000, 1_000))
        source.fail = true
        monitor.poll(ClockSample(11_000, 2_000))
        assertEquals(1L, monitor.state.value.queryGeneration)
        assertFalse(monitor.state.value.running)
        assertTrue(monitor.state.value.observation is ForegroundObservation.MonitoringUnavailable)
    }

    @Test fun `wall clock jump invalidates old cursor and next query rebaselines`() {
        val source = FakeSource()
        val monitor = UsageMonitor(source)
        monitor.poll(ClockSample(10_000, 1_000))
        monitor.poll(ClockSample(20_000, 2_000))
        assertTrue(MonitoringSignal.WALL_CLOCK_JUMP in monitor.state.value.signals)
        assertEquals(1L, monitor.state.value.queryGeneration)
        monitor.poll(ClockSample(21_000, 3_000))
        assertEquals(20_000L to 21_000L, source.windows.last())
        assertEquals(2L, monitor.state.value.queryGeneration)
    }

    private class FakeSource : UsageEventSource {
        var fail = false
        var events = emptyList<UsageEventFact>()
        val windows = mutableListOf<Pair<Long, Long>>()
        override fun query(startWallMillis: Long, endWallMillis: Long): List<UsageEventFact>? {
            windows += startWallMillis to endWallMillis
            if (fail) return null
            return events
        }
    }
}
