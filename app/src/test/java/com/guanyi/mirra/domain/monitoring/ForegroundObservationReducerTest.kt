package com.guanyi.mirra.domain.monitoring

import org.junit.Assert.*
import org.junit.Test

class ForegroundObservationReducerTest {
    private val reducer = ForegroundObservationReducer()

    @Test fun `resumed package survives a duplicate and a late pause but switches without stop`() {
        var state = ObservationState()
        state = reducer.reduce(state, success(1_000, 2_000, 1_000, event(1_500, UsageEventKind.ACTIVITY_RESUMED, "a"))).state
        assertEquals("a", (state.observation as ForegroundObservation.Package).name)
        assertEquals(1_000L, state.lastForegroundEvidenceElapsed)
        state = reducer.reduce(state, success(1_500, 3_000, 2_000, event(1_500, UsageEventKind.ACTIVITY_RESUMED, "a"))).state
        assertEquals("a", (state.observation as ForegroundObservation.Package).name)
        state = reducer.reduce(state, success(2_500, 4_000, 3_000, event(1_600, UsageEventKind.ACTIVITY_PAUSED, "a"), event(3_500, UsageEventKind.ACTIVITY_RESUMED, "b"))).state
        assertEquals("b", (state.observation as ForegroundObservation.Package).name)
    }

    @Test fun `same timestamp contradictory events invalidate foreground`() {
        val result = reducer.reduce(ObservationState(), success(1_000, 2_000, 1_000,
            event(1_500, UsageEventKind.ACTIVITY_RESUMED, "a"), event(1_500, UsageEventKind.ACTIVITY_RESUMED, "b")))
        assertTrue(result.state.observation is ForegroundObservation.Unknown)
    }

    @Test fun `stopping an older activity does not end a newer activity in the same app`() {
        var state = reducer.reduce(ObservationState(), success(1_000, 2_000, 1_000,
            UsageEventFact(1_500, UsageEventKind.ACTIVITY_RESUMED, "risk", "OldActivity"))).state
        state = reducer.reduce(state, success(1_500, 3_000, 2_000,
            UsageEventFact(2_500, UsageEventKind.ACTIVITY_RESUMED, "risk", "NewActivity"))).state
        state = reducer.reduce(state, success(2_500, 4_000, 3_000,
            UsageEventFact(3_500, UsageEventKind.ACTIVITY_STOPPED, "risk", "OldActivity"))).state
        assertEquals("risk", (state.observation as ForegroundObservation.Package).name)
        assertEquals(2_500L, state.observation.sourceEventAtWallMillis)
        state = reducer.reduce(state, success(3_500, 5_000, 4_000,
            UsageEventFact(4_500, UsageEventKind.ACTIVITY_PAUSED, "risk", "NewActivity"))).state
        assertTrue(state.observation is ForegroundObservation.Unknown)
    }

    @Test fun `late exit of current package revokes confidence but late exit of old package does not`() {
        var state = reducer.reduce(ObservationState(), success(1_000, 2_000, 1_000,
            event(1_500, UsageEventKind.ACTIVITY_RESUMED, "a"))).state
        state = reducer.reduce(state, success(1_000, 3_000, 2_000,
            event(1_400, UsageEventKind.ACTIVITY_PAUSED, "a"))).state
        assertTrue(state.observation is ForegroundObservation.Unknown)

        state = reducer.reduce(state, success(2_500, 4_000, 3_000,
            event(3_500, UsageEventKind.ACTIVITY_RESUMED, "b"))).state
        state = reducer.reduce(state, success(3_000, 5_000, 4_000,
            event(3_400, UsageEventKind.ACTIVITY_STOPPED, "a"))).state
        assertEquals("b", (state.observation as ForegroundObservation.Package).name)
    }

    @Test fun `empty successes keep query continuous but never invent or indefinitely retain package`() {
        var state = reducer.reduce(ObservationState(), success(1_000, 2_000, 1_000)).state
        assertTrue(state.observation is ForegroundObservation.Unknown)
        state = reducer.reduce(state, success(1_500, 3_000, 2_000, event(2_500, UsageEventKind.ACTIVITY_RESUMED, "a"))).state
        state = reducer.reduce(state, success(2_500, 16_000, 15_000)).state
        assertEquals("a", (state.observation as ForegroundObservation.Package).name)
        state = reducer.reduce(state, success(15_500, 23_001, 22_001)).state
        assertTrue(state.observation is ForegroundObservation.Unknown)
        assertEquals(22_001L, state.lastSuccessfulQueryElapsed)
        assertEquals(2_000L, state.lastForegroundEvidenceElapsed)
        assertFalse(state.cursorNeedsRebaseline)
    }

    @Test fun `stale repeated query window cannot advance successful coverage time`() {
        val first = reducer.reduce(ObservationState(), success(1_000, 2_000, 1_000)).state
        val repeated = reducer.reduce(first, success(1_000, 2_000, 1_500))
        assertEquals(1_000L, repeated.state.lastSuccessfulQueryElapsed)
        assertTrue(repeated.signals.isEmpty())
    }

    @Test fun `unrelated pause does not refresh another package foreground evidence`() {
        var state = reducer.reduce(ObservationState(), success(1_000, 2_000, 1_000,
            event(1_500, UsageEventKind.ACTIVITY_RESUMED, "b"))).state
        state = reducer.reduce(state, success(1_500, 16_000, 15_000,
            event(15_000, UsageEventKind.ACTIVITY_PAUSED, "a"))).state
        assertEquals(1_000L, state.lastForegroundEvidenceElapsed)
        state = reducer.reduce(state, success(15_500, 23_001, 22_001)).state
        assertTrue(state.observation is ForegroundObservation.Unknown)
        assertEquals(22_001L, state.lastSuccessfulQueryElapsed)
    }

    @Test fun `failed query and nonconnecting window clear confidence and report monitoring gap`() {
        val first = reducer.reduce(ObservationState(), success(1_000, 2_000, 1_000, event(1_500, UsageEventKind.ACTIVITY_RESUMED, "a"))).state
        val failed = reducer.reduce(first, QueryResult.Failure(ClockSample(2_500, 2_000), QueryFailureReason.PERMISSION_LOST))
        assertTrue(failed.state.observation is ForegroundObservation.MonitoringUnavailable)
        assertTrue(MonitoringSignal.GAP in failed.signals)
        val gap = reducer.reduce(first, success(2_001, 3_000, 2_000))
        assertTrue(gap.state.observation is ForegroundObservation.MonitoringUnavailable)
        assertTrue(MonitoringSignal.GAP in gap.signals)
    }

    @Test fun `screen off and device locked are distinct observed facts`() {
        var state = reducer.reduce(ObservationState(), success(1_000, 2_000, 1_000, event(1_500, UsageEventKind.SCREEN_OFF))).state
        assertTrue(state.observation is ForegroundObservation.ScreenOff)
        state = reducer.reduce(state, success(1_500, 3_000, 2_000, event(2_500, UsageEventKind.DEVICE_LOCKED))).state
        assertTrue(state.observation is ForegroundObservation.DeviceLocked)
    }

    @Test fun `screen and lock at same physical timestamp need no fabricated millisecond`() {
        val result = reducer.reduce(ObservationState(), success(1_000, 2_000, 1_000,
            event(1_500, UsageEventKind.SCREEN_OFF), event(1_500, UsageEventKind.DEVICE_LOCKED)))
        assertTrue(result.state.observation is ForegroundObservation.DeviceLocked)
        assertEquals(1_500L, result.state.lastEventWallMillis)
        val screenOnly = reducer.reduce(ObservationState(), success(1_000, 2_000, 1_000,
            event(1_500, UsageEventKind.SCREEN_OFF))).state
        val delayedLock = reducer.reduce(screenOnly, success(1_000, 3_000, 2_000,
            event(1_500, UsageEventKind.DEVICE_LOCKED)))
        assertTrue(delayedLock.state.observation is ForegroundObservation.DeviceLocked)
    }

    @Test fun `forward and backward wall jumps invalidate cursor and foreground`() {
        val first = reducer.reduce(ObservationState(), success(1_000, 2_000, 1_000, event(1_500, UsageEventKind.ACTIVITY_RESUMED, "a"))).state
        for (wallNow in listOf(8_001L, -2_001L)) {
            val jumped = reducer.reduce(first, success(wallNow - 500, wallNow, 2_000))
            assertTrue(jumped.state.observation is ForegroundObservation.Unknown)
            assertTrue(jumped.state.cursorNeedsRebaseline)
            assertNull(jumped.state.cursorWallMillis)
            assertEquals(wallNow, jumped.state.rebaselineAtWallMillis)
            assertTrue(MonitoringSignal.WALL_CLOCK_JUMP in jumped.signals)
            assertTrue(MonitoringSignal.GAP in jumped.signals)
        }
    }

    private fun event(at: Long, kind: UsageEventKind, pkg: String? = null) = UsageEventFact(at, kind, pkg)
    private fun success(start: Long, end: Long, elapsed: Long, vararg events: UsageEventFact) =
        QueryResult.Success(start, end, ClockSample(end, elapsed), events.toList())
}
