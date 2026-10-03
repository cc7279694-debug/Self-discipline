package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.DndLifecycle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DndControllerTest {
    @Test fun `modern rule is created once reused and released without global writes`() = runTest {
        val store = FakeStore()
        val system = FakeSystem(35)
        val controller = DndController(store, system)
        controller.apply("s1", enabled = true)
        controller.apply("s1", enabled = true)
        assertEquals(1, system.created)
        assertEquals(1, system.activated)
        assertEquals(DndLifecycle.ACTIVE, store.record.lifecycle)
        store.record = store.record.copy(active = false)
        controller.release("s1")
        controller.release("s1")
        assertEquals(1, system.deactivated)
        assertEquals(DndLifecycle.RELEASED, store.record.lifecycle)
        assertEquals(0, system.globalWrites)
    }

    @Test fun `api 29 uses the owned rule path without global policy writes`() = runTest {
        val store = FakeStore()
        val system = FakeSystem(29)
        DndController(store, system).apply("s1", enabled = true)
        assertEquals(1, system.created)
        assertEquals(1, system.activated)
        assertEquals(0, system.globalWrites)
        assertEquals(DndLifecycle.ACTIVE, store.record.lifecycle)
    }

    @Test fun `orphan rule is found after crash and deactivated on bootstrap`() = runTest {
        val store = FakeStore()
        val system = FakeSystem(35).apply { ownRule = "owned" }
        store.record = store.record.copy(active = false, ruleId = "mirra:rule-creation-pending")
        DndController(store, system).reconcileAfterRecovery()
        assertEquals(1, system.deactivated)
        assertEquals(0, system.created)
    }

    @Test fun `legacy restores only while same process still owns unchanged priority`() = runTest {
        val store = FakeStore()
        val system = FakeSystem(28)
        val controller = DndController(store, system)
        controller.apply("s1", true)
        assertEquals(1, store.record.priorFilter)
        assertEquals(2, system.filter)
        store.record = store.record.copy(active = false)
        controller.release("s1")
        assertEquals(1, system.filter)
        assertEquals(DndLifecycle.RELEASED, store.record.lifecycle)

        store.record = DndRecord("s2", true)
        controller.apply("s2", true)
        system.userChanged = true
        system.filter = 3
        store.record = store.record.copy(active = false)
        controller.release("s2")
        assertEquals(3, system.filter)
        assertEquals(DndLifecycle.RELEASED, store.record.lifecycle)
    }

    @Test fun `denial and system failure never modify coverage`() = runTest {
        val store = FakeStore()
        val system = FakeSystem(35).apply { granted = false }
        DndController(store, system).apply("s1", true)
        assertEquals(DndLifecycle.APPLY_FAILED, store.record.lifecycle)
        assertEquals("FULL", store.coverage)
        system.granted = true
        system.failActivation = true
        DndController(store, system).apply("s1", true)
        assertEquals(DndLifecycle.APPLY_FAILED, store.record.lifecycle)
        assertEquals(0, system.deactivated)
        assertEquals("FULL", store.coverage)
    }

    @Test fun `database failure after activating an owned rule is compensated`() = runTest {
        val store = FakeStore().apply { failActiveWrite = true }
        val system = FakeSystem(35)
        DndController(store, system).apply("s1", true)
        assertEquals(1, system.activated)
        assertEquals(1, system.deactivated)
        assertEquals(DndLifecycle.APPLY_FAILED, store.record.lifecycle)
        assertEquals("FULL", store.coverage)
    }

    @Test fun `system rejecting state change never records false active or released state`() = runTest {
        val store = FakeStore()
        val system = FakeSystem(35).apply { rejectActivation = true }
        val controller = DndController(store, system)

        controller.apply("s1", true)
        assertEquals(DndLifecycle.APPLY_FAILED, store.record.lifecycle)

        system.rejectActivation = false
        controller.apply("s1", true)
        assertEquals(DndLifecycle.ACTIVE, store.record.lifecycle)

        store.record = store.record.copy(active = false)
        system.rejectDeactivation = true
        controller.release("s1")
        assertEquals(DndLifecycle.RELEASE_FAILED, store.record.lifecycle)
    }

    @Test fun `crash after durable rule intent but before id write is reconciled`() = runTest {
        val store = FakeStore().apply {
            record = record.copy(active = false, ruleId = "mirra:rule-creation-pending")
        }
        val system = FakeSystem(35).apply { ownRule = "owned" }
        DndController(store, system).reconcileAfterRecovery()
        assertEquals(1, system.deactivated)
        assertEquals(DndLifecycle.RELEASED, store.record.lifecycle)
    }

    @Test fun `legacy crash cannot prove ownership and never writes global state`() = runTest {
        val store = FakeStore().apply {
            record = record.copy(active = false, priorFilter = 1, lifecycle = DndLifecycle.ACTIVE)
        }
        val system = FakeSystem(28).apply { filter = 2 }
        DndController(store, system).reconcileAfterRecovery()
        assertEquals(2, system.filter)
        assertEquals(0, system.globalWrites)
        assertEquals(DndLifecycle.RELEASE_FAILED, store.record.lifecycle)
    }

    @Test fun `revoked permission keeps release pending for retry and never changes coverage`() = runTest {
        val store = FakeStore()
        val system = FakeSystem(35)
        val controller = DndController(store, system)
        controller.apply("s1", true)
        store.record = store.record.copy(active = false)
        system.granted = false
        controller.release("s1")
        assertEquals(DndLifecycle.RELEASE_FAILED, store.record.lifecycle)
        assertEquals(0, system.deactivated)
        system.granted = true
        controller.release("s1")
        assertEquals(DndLifecycle.RELEASED, store.record.lifecycle)
        assertEquals("FULL", store.coverage)
    }

    @Test fun `user disabled rule is never reactivated after already active`() = runTest {
        val store = FakeStore()
        val system = FakeSystem(35)
        val controller = DndController(store, system)
        controller.apply("s1", true)
        system.userChanged = true
        controller.apply("s1", true)
        assertEquals(1, system.activated)
    }

    private class FakeStore : DndStateStore {
        var record = DndRecord("s1", true)
        val coverage = "FULL"
        var failActiveWrite = false
        override suspend fun get(sessionId: String) = record.takeIf { it.sessionId == sessionId }
        override suspend fun prepare(sessionId: String, priorFilter: Int?, ruleId: String?) {
            record = record.copy(priorFilter = priorFilter, ruleId = ruleId)
        }
        override suspend fun setLifecycle(sessionId: String, lifecycle: DndLifecycle) {
            if (lifecycle == DndLifecycle.ACTIVE && failActiveWrite) throw IllegalStateException("disk failure")
            record = record.copy(lifecycle = lifecycle)
        }
        override suspend fun pendingAfterRecovery() = listOf(record).filter { !it.active }
    }

    private class FakeSystem(override val apiLevel: Int) : DndSystem {
        var granted = true
        var ownRule: String? = null
        var filter = 1
        var created = 0
        var activated = 0
        var deactivated = 0
        var globalWrites = 0
        var userChanged = false
        var failActivation = false
        var rejectActivation = false
        var rejectDeactivation = false
        override fun hasAccess() = granted
        override fun findOwnedRule() = ownRule
        override fun createOwnedRule(): String { created++; ownRule = "owned"; return "owned" }
        override fun activateOwnedRule(id: String) {
            if (failActivation) throw SecurityException()
            if (rejectActivation) throw IllegalStateException("system did not accept state change")
            activated++
        }
        override fun deactivateOwnedRule(id: String) {
            if (rejectDeactivation) throw IllegalStateException("system did not accept state change")
            deactivated++
        }
        override fun currentFilter() = filter
        override fun applyLegacyPriority() { globalWrites++; filter = 2 }
        override fun legacyOwnershipIntact() = !userChanged && filter == 2
        override fun restoreLegacyFilter(priorFilter: Int) { globalWrites++; filter = priorFilter }
    }
}
