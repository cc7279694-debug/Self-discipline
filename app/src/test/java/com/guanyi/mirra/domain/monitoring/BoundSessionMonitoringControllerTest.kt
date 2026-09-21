package com.guanyi.mirra.domain.monitoring

import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.repository.RiskConfirmation
import com.guanyi.mirra.data.repository.RuntimeFocusFacts
import com.guanyi.mirra.platform.focus.MonitorSnapshot
import com.guanyi.mirra.platform.focus.MonitoringBinding
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundSessionMonitoringControllerTest {
    private val binding = MonitoringBinding("session", "generation", 0)
    private val segment = SessionSegmentEntity("focus", "session", SessionSegmentType.FOCUS, 1_000,
        null, null, null, null, 0, null, 1)

    @Test fun `successful empty query keeps continuity and heartbeat writes only after fifteen seconds`() = runTest {
        val port = FakeFacts(segment)
        val controller = BoundSessionMonitoringController(port)
        controller.onSample(binding, snapshot(2_000, 1_000), ClockSample(2_000, 1_000))
        controller.onSample(binding, snapshot(7_900, 6_900), ClockSample(7_900, 6_900))
        assertTrue(port.losses.isEmpty())
        assertTrue(port.heartbeats.isEmpty())
        controller.onSample(binding, snapshot(17_000, 16_000), ClockSample(17_000, 16_000))
        // The missing 9.1s query interval is a gap, even if a late query succeeds.
        assertEquals(1, port.losses.size)
        assertTrue(port.heartbeats.isEmpty())
    }

    @Test fun `five point nine seconds is healthy while six seconds is a gap and loss is idempotent`() = runTest {
        val port = FakeFacts(segment)
        val controller = BoundSessionMonitoringController(port)
        controller.onSample(binding, snapshot(2_000, 1_000), ClockSample(2_000, 1_000))
        controller.onSample(binding, snapshot(7_900, 6_900), ClockSample(7_900, 6_900))
        assertTrue(port.losses.isEmpty())
        controller.onSample(binding, snapshot(13_900, 12_900), ClockSample(13_900, 12_900))
        controller.onSample(binding, snapshot(14_000, 13_000), ClockSample(14_000, 13_000))
        assertEquals(1, port.losses.size)
        assertEquals(7_900L, port.losses.single().second)
    }

    @Test fun `one-second successful queries write only one heartbeat at fifteen seconds`() = runTest {
        val port = FakeFacts(segment)
        val controller = BoundSessionMonitoringController(port)
        for (second in 1L..17L) {
            val wall = 1_000 + second * 1_000
            controller.onSample(binding, snapshot(wall, second * 1_000), ClockSample(wall, second * 1_000))
        }
        assertEquals(listOf(16_000L), port.heartbeats)
        assertTrue(port.losses.isEmpty())
    }

    @Test fun `risk confirmation uses source event wall and exit begins recovery only`() = runTest {
        val port = FakeFacts(segment)
        val controller = BoundSessionMonitoringController(port)
        controller.onSample(binding, snapshot(2_000, 1_000,
            ForegroundObservation.Package("risk", 1_000, 1_500)), ClockSample(2_000, 1_000))
        for (second in 2L..11L) {
            val wall = 1_000 + second * 1_000
            controller.onSample(binding, snapshot(wall, second * 1_000,
                ForegroundObservation.Package("risk", second * 1_000, 1_500)),
                ClockSample(wall, second * 1_000))
        }
        assertEquals(1, port.confirmations.size)
        assertEquals(1_500L, port.confirmations.single().candidateStartedAt)
        assertEquals("focus", port.confirmations.single().sourceSegmentId)
        controller.onSample(binding, snapshot(13_000, 12_000,
            ForegroundObservation.Package("normal", 12_000, 13_000), 13_000), ClockSample(13_000, 12_000))
        assertEquals(1, port.exits.size)
        assertEquals(SessionSegmentType.RECOVERY, port.current.activeSegment.type)
    }

    @Test fun `NONE coverage and unbound samples never write facts`() = runTest {
        val port = FakeFacts(segment)
        port.current = port.current.copy(coverage = MonitoringCoverage.NONE)
        val controller = BoundSessionMonitoringController(port)
        controller.onSample(null, snapshot(2_000, 1_000), ClockSample(2_000, 1_000))
        controller.onSample(binding, snapshot(2_000, 1_000), ClockSample(2_000, 1_000))
        controller.onServiceLost(binding, ClockSample(8_000, 7_000), "stopped")
        assertTrue(port.losses.isEmpty())
        assertTrue(port.heartbeats.isEmpty())
    }

    @Test fun `clock jump clears candidate and never persists a backward boundary`() = runTest {
        val port = FakeFacts(segment)
        val controller = BoundSessionMonitoringController(port)
        controller.onSample(binding, snapshot(2_000, 1_000,
            ForegroundObservation.Package("risk", 1_000, 1_500)), ClockSample(2_000, 1_000))
        assertEquals("risk", controller.diagnostics.candidatePackage)
        controller.onSample(binding, snapshot(100, 2_000).copy(running = false,
            signals = setOf(MonitoringSignal.WALL_CLOCK_JUMP)), ClockSample(100, 2_000))
        assertEquals(2_000L, port.losses.single().second)
        assertEquals(2_000L, port.losses.single().third)
        assertEquals(null, controller.diagnostics.candidatePackage)
        controller.onSample(binding, snapshot(3_000, 3_000), ClockSample(3_000, 3_000))
        assertEquals(1, port.losses.size)
        assertTrue(port.heartbeats.isEmpty())
    }

    @Test fun `unexpected service stop loses bound session from last trust only once`() = runTest {
        val port = FakeFacts(segment)
        val controller = BoundSessionMonitoringController(port)
        controller.onSample(binding, snapshot(2_000, 1_000), ClockSample(2_000, 1_000))
        controller.onServiceLost(binding, ClockSample(4_000, 3_000), "notification stop")
        controller.onServiceLost(binding, ClockSample(5_000, 4_000), "destroy")
        assertEquals(1, port.losses.size)
        assertEquals(2_000L, port.losses.single().second)
        assertEquals(MonitoringCoverage.PARTIAL, controller.diagnostics.coverage)
    }

    @Test fun `failed durable loss blocks normal finish until a retry succeeds`() = runTest {
        val port = FakeFacts(segment)
        val controller = BoundSessionMonitoringController(port)
        port.failLoss = true
        try {
            controller.onServiceLost(binding, ClockSample(4_000, 3_000), "permission revoked")
            fail("Loss should fail")
        } catch (_: IllegalStateException) { }
        var finished = false
        try {
            controller.finishWithFacts { finished = true }
            fail("Finish must not leave an unrecorded loss as FULL")
        } catch (_: IllegalStateException) { }
        assertFalse(finished)
        port.failLoss = false
        controller.onServiceLost(binding, ClockSample(5_000, 4_000), "retry")
        controller.finishWithFacts { finished = true }
        assertTrue(finished)
        assertEquals(1, port.losses.size)
    }

    private fun snapshot(wall: Long, elapsed: Long,
        observation: ForegroundObservation = ForegroundObservation.Unknown("no event"),
        eventWall: Long? = null,
    ) = MonitorSnapshot(running = true, queryGeneration = elapsed / 1_000,
        lastSuccessfulQueryElapsed = elapsed, lastSuccessfulQueryClock = ClockSample(wall, elapsed),
        observation = observation, lastEventWallMillis = eventWall)

    private class FakeFacts(segment: SessionSegmentEntity) : RuntimeFactsPort {
        var current = RuntimeFocusFacts(1_000, segment, MonitoringCoverage.FULL, 1_000, setOf("risk"))
        var failLoss = false
        val heartbeats = mutableListOf<Long>()
        val losses = mutableListOf<Triple<String, Long, Long>>()
        val confirmations = mutableListOf<RiskConfirmation>()
        val exits = mutableListOf<Long>()
        override suspend fun read(sessionId: String) = current
        override suspend fun heartbeat(sessionId: String, at: Long) { heartbeats += at }
        override suspend fun lose(sessionId: String, trustedAt: Long, detectedAt: Long) {
            if (failLoss) throw IllegalStateException("storage unavailable")
            losses += Triple(sessionId, trustedAt, detectedAt)
            current = current.copy(coverage = MonitoringCoverage.PARTIAL,
                activeSegment = current.activeSegment.copy(type = SessionSegmentType.UNMONITORED))
        }
        override suspend fun confirm(command: RiskConfirmation): Boolean {
            confirmations += command
            current = current.copy(activeSegment = current.activeSegment.copy(id = "distraction",
                type = SessionSegmentType.DISTRACTION, startedAt = command.candidateStartedAt,
                packageName = command.packageName))
            return true
        }
        override suspend fun brief(sessionId: String, packageName: String, at: Long) = true
        override suspend fun exit(sessionId: String, packageName: String, at: Long): Boolean {
            exits += at
            current = current.copy(activeSegment = current.activeSegment.copy(id = "recovery",
                type = SessionSegmentType.RECOVERY, startedAt = at))
            return true
        }
    }
}
