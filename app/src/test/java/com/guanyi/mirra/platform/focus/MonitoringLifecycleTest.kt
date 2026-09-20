package com.guanyi.mirra.platform.focus

import org.junit.Assert.*
import org.junit.Test

class MonitoringLifecycleTest {
    @Test fun `start requires a generation and acknowledges foreground before ready`() {
        val lifecycle = MonitoringLifecycle()
        assertTrue(lifecycle.start("one"))
        assertEquals(ServicePhase.STARTING, lifecycle.state.value.phase)
        assertFalse(lifecycle.state.value.foregroundAck)
        assertTrue(lifecycle.foregroundAck("one"))
        assertEquals(ServicePhase.FOREGROUND_ACK, lifecycle.state.value.phase)
        assertTrue(lifecycle.monitorInitializing("one"))
        assertTrue(lifecycle.monitorReady("one"))
        assertEquals(ServicePhase.MONITOR_READY, lifecycle.state.value.phase)
        assertTrue(lifecycle.state.value.foregroundAck)
        assertTrue(lifecycle.stop("one"))
        assertEquals(ServicePhase.STOPPED, lifecycle.state.value.phase)
    }

    @Test fun `same generation is idempotent and another generation cannot start a second poller`() {
        val lifecycle = MonitoringLifecycle()
        assertTrue(lifecycle.start("one"))
        assertTrue(lifecycle.start("one"))
        assertFalse(lifecycle.start("two"))
        assertFalse(lifecycle.stop("two"))
        assertEquals("one", lifecycle.state.value.generation)
        assertTrue(lifecycle.stop("one"))
        assertTrue(lifecycle.start("two"))
    }

    @Test fun `failed initialization never reports ready`() {
        val lifecycle = MonitoringLifecycle()
        lifecycle.start("one")
        lifecycle.foregroundAck("one")
        lifecycle.monitorInitializing("one")
        lifecycle.failed("one", "query failed")
        assertEquals(ServicePhase.FAILED, lifecycle.state.value.phase)
        assertFalse(lifecycle.monitorReady("one"))
        assertFalse(lifecycle.state.value.foregroundAck && lifecycle.state.value.phase == ServicePhase.MONITOR_READY)
    }

    @Test fun `interrupted query revokes ready until cursor is reestablished`() {
        val lifecycle = MonitoringLifecycle()
        lifecycle.start("one")
        lifecycle.foregroundAck("one")
        lifecycle.monitorInitializing("one")
        lifecycle.monitorReady("one")
        assertTrue(lifecycle.monitorInterrupted("one"))
        assertEquals(ServicePhase.MONITOR_INITIALIZING, lifecycle.state.value.phase)
        assertTrue(lifecycle.monitorReady("one"))
        assertFalse(lifecycle.monitorInterrupted("other"))
    }

    @Test fun `failed service stop preserves diagnostic error but not readiness`() {
        val lifecycle = MonitoringLifecycle()
        lifecycle.start("one")
        lifecycle.failed("one", "usage access unavailable")
        lifecycle.stop("one")
        assertEquals(ServicePhase.STOPPED, lifecycle.state.value.phase)
        assertNull(lifecycle.state.value.generation)
        assertEquals("usage access unavailable", lifecycle.state.value.lastError)
        assertTrue(lifecycle.start("two"))
        assertNull(lifecycle.state.value.lastError)
    }

    @Test fun `optional notification and DND denial never block monitoring readiness`() {
        val manager = MonitoringCapabilityManager()
        manager.updateUsage(CapabilityStatus.AVAILABLE)
        manager.updateNotification(NotificationStatus(fgsCanRun = true, drawerVisible = false))
        manager.updateDnd(CapabilityStatus.NEEDS_USER_ACTION)
        manager.updateService(ServiceState(ServicePhase.MONITOR_READY, "one", foregroundAck = true))
        manager.updateMonitor(MonitorSnapshot(running = true, queryGeneration = 1, lastSuccessfulQueryElapsed = 1000))
        assertTrue(manager.state.value.monitoringReady)
        assertFalse(manager.state.value.notification.drawerVisible)
        manager.updateUsage(CapabilityStatus.NEEDS_USER_ACTION)
        assertFalse(manager.state.value.monitoringReady)
    }
}
