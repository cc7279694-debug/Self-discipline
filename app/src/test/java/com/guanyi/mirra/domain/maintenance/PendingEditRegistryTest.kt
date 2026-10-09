package com.guanyi.mirra.domain.maintenance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PendingEditRegistryTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun freezeBlocksNewBufferedEditsBeforeAwaitingEveryExistingOwner() = runTest(dispatcher) {
        val registry = PendingEditRegistry()
        val persisted = mutableListOf<String>()
        var text = "old"
        val response = CompletableDeferred<Unit>()
        val first = registry.register { response.await(); persisted += text }
        registry.register { persisted += "second owner" }
        assertTrue(first.edit { text = "latest" })
        val frozen = async { registry.freezeAndFlush() }; runCurrent()
        assertFalse(registry.acceptingEdits.value)
        assertFalse(first.edit { text = "late" })
        assertFalse(frozen.isCompleted)
        response.complete(Unit); runCurrent()
        assertEquals(listOf("latest", "second owner"), persisted)
        frozen.await().release()
        assertTrue(registry.acceptingEdits.value)
        assertTrue(first.edit { text = "after backup" })
    }

    @Test fun flushFailureReopensSameOwnersAndPropagatesFailureInsteadOfStartingMaintenance() = runTest(dispatcher) {
        val registry = PendingEditRegistry()
        val failure = IllegalStateException("caption not durable")
        val owner = registry.register { throw failure }
        val caught = runCatching { registry.freezeAndFlush() }.exceptionOrNull()
        // Coroutine stack-trace recovery may copy an exception across withContext.
        // Its original failure must still be propagated directly or retained as its cause.
        assertTrue(generateSequence(caught) { it.cause }.any { it === failure })
        assertTrue(registry.acceptingEdits.value)
        assertTrue(owner.edit {})
    }

    @Test fun retiredGenerationPermanentlyRejectsLateCallbacksAndCannotBeThawed() = runTest(dispatcher) {
        val registry = PendingEditRegistry()
        var writes = 0
        val owner = registry.register { writes++ }
        val freeze = registry.freezeAndFlush()
        freeze.retire(); freeze.release()
        assertFalse(registry.acceptingEdits.value)
        assertFalse(owner.edit { writes++ })
        assertEquals(1, writes)
        assertTrue(runCatching { registry.freezeAndFlush() }.isFailure)
        assertTrue(runCatching { registry.register {} }.isFailure)
    }

    @Test fun closeUnregistersOwnerAndDuplicateReleaseCannotReopenAnotherFreeze() = runTest(dispatcher) {
        val registry = PendingEditRegistry()
        var writes = 0
        val owner = registry.register { writes++ }
        owner.close()
        assertFalse(owner.edit { writes++ })
        val first = registry.freezeAndFlush()
        assertEquals(0, writes)
        first.release()
        val second = registry.freezeAndFlush()
        first.release()
        assertFalse(registry.acceptingEdits.value)
        second.release()
        assertTrue(registry.acceptingEdits.value)
    }

    @Test fun cancelledFlushWaitDoesNotLeaveEditingFrozen() = runTest(dispatcher) {
        val registry = PendingEditRegistry()
        val entered = CompletableDeferred<Unit>()
        val owner = registry.register { entered.complete(Unit); CompletableDeferred<Unit>().await() }
        val freeze = async { registry.freezeAndFlush() }
        entered.await(); freeze.cancel(); runCurrent()
        assertTrue(registry.acceptingEdits.value)
        assertTrue(owner.edit {})
    }
}
