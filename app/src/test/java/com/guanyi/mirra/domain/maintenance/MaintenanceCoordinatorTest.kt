package com.guanyi.mirra.domain.maintenance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceCoordinatorTest {
    @Test fun `open admits and normal completion releases`() = runTest {
        val coordinator = MaintenanceCoordinator()
        assertEquals(MaintenancePhase.OPEN, coordinator.state.value.phase)
        assertEquals(42, coordinator.withOperation {
            assertEquals(1, coordinator.state.value.activePermits)
            42
        })
        assertEquals(0, coordinator.state.value.activePermits)
    }

    @Test fun `draining rejects new admission but waits original writer`() = runTest {
        val coordinator = MaintenanceCoordinator()
        val release = CompletableDeferred<Unit>()
        val writer = launch { coordinator.withOperation { release.await() } }
        runCurrent()
        val exclusive = async { coordinator.withExclusive(1_000) {
            assertEquals(0, coordinator.state.value.activePermits)
            assertEquals(MaintenancePhase.EXCLUSIVE, coordinator.state.value.phase)
        } }
        runCurrent()
        assertEquals(MaintenancePhase.DRAINING, coordinator.state.value.phase)
        expect<MaintenanceUnavailableException> { coordinator.withOperation { fail("admitted") } }
        assertFalse(exclusive.isCompleted)
        release.complete(Unit)
        writer.join()
        exclusive.await()
        assertEquals(MaintenancePhase.OPEN, coordinator.state.value.phase)
    }

    @Test fun `drain winning admission race rejects writer before body`() = runTest {
        val coordinator = MaintenanceCoordinator()
        val release = CompletableDeferred<Unit>()
        val drain = launch { coordinator.withExclusive(1_000) { release.await() } }
        runCurrent()
        val error = expect<MaintenanceUnavailableException> { coordinator.withOperation { fail("body") } }
        assertEquals(MaintenancePhase.EXCLUSIVE, error.phase)
        release.complete(Unit)
        drain.join()
    }

    @Test fun `nested registration reuses admitted operation while draining`() = runTest {
        val coordinator = MaintenanceCoordinator()
        val proceed = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val writer = launch {
            coordinator.withOperation { permit ->
                proceed.await()
                coordinator.withNestedOperation(permit) { nested ->
                    assertSame(permit, nested)
                    assertEquals(2, coordinator.state.value.activePermits)
                    finish.await()
                }
            }
        }
        runCurrent()
        val drain = launch { coordinator.withExclusive(1_000) {} }
        runCurrent()
        proceed.complete(Unit)
        runCurrent()
        assertEquals(MaintenancePhase.DRAINING, coordinator.state.value.phase)
        finish.complete(Unit)
        writer.join()
        drain.join()
        assertEquals(0, coordinator.state.value.activePermits)
    }

    @Test fun `exception releases root and nested permits`() = runTest {
        val coordinator = MaintenanceCoordinator()
        expect<IllegalArgumentException> {
            coordinator.withOperation { permit ->
                coordinator.withNestedOperation(permit) { throw IllegalArgumentException("work") }
            }
        }
        assertEquals(0, coordinator.state.value.activePermits)
        coordinator.withExclusive(1_000) {}
    }

    @Test fun `cancelled writer completes compensation before drain succeeds`() = runTest {
        val coordinator = MaintenanceCoordinator()
        val compensated = CompletableDeferred<Unit>()
        val writer = launch {
            coordinator.withOperation {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { compensated.await() } }
            }
        }
        runCurrent()
        val drain = async { coordinator.withExclusive(1_000) {} }
        runCurrent()
        writer.cancel()
        runCurrent()
        assertEquals(1, coordinator.state.value.activePermits)
        assertFalse(drain.isCompleted)
        compensated.complete(Unit)
        writer.join()
        drain.await()
        assertEquals(0, coordinator.state.value.activePermits)
    }

    @Test fun `drain timeout is not successful exclusive and leaves unchanged resources open`() = runTest {
        val coordinator = MaintenanceCoordinator()
        val release = CompletableDeferred<Unit>()
        val writer = launch { coordinator.withOperation { release.await() } }
        runCurrent()
        var entered = false
        val drain = async { expect<TimeoutCancellationException> {
            coordinator.withExclusive(100) { entered = true }
        } }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        drain.await()
        assertFalse(entered)
        assertEquals(MaintenancePhase.OPEN, coordinator.state.value.phase)
        assertEquals(1, coordinator.state.value.activePermits)
        release.complete(Unit)
        writer.join()
    }

    @Test fun `cancelled drain never enters exclusive`() = runTest {
        val coordinator = MaintenanceCoordinator()
        val release = CompletableDeferred<Unit>()
        val writer = launch { coordinator.withOperation { release.await() } }
        runCurrent()
        var entered = false
        val drain = launch { coordinator.withExclusive(1_000) { entered = true } }
        runCurrent()
        drain.cancel()
        drain.join()
        assertFalse(entered)
        assertEquals(MaintenancePhase.OPEN, coordinator.state.value.phase)
        release.complete(Unit)
        writer.join()
    }

    @Test fun `multiple concurrent writers all drain`() = runTest {
        val coordinator = MaintenanceCoordinator()
        val gates = List(8) { CompletableDeferred<Unit>() }
        val writers = gates.map { gate -> launch { coordinator.withOperation { gate.await() } } }
        runCurrent()
        assertEquals(8, coordinator.state.value.activePermits)
        val drain = async { coordinator.withExclusive(1_000) {} }
        runCurrent()
        gates.dropLast(1).forEach { it.complete(Unit) }
        runCurrent()
        assertEquals(1, coordinator.state.value.activePermits)
        assertFalse(drain.isCompleted)
        gates.last().complete(Unit)
        writers.forEach { it.join() }
        drain.await()
    }

    @Test fun `duplicate release seals authority without completing still running body`() = runTest {
        val coordinator = MaintenanceCoordinator()
        val release = CompletableDeferred<Unit>()
        val writer = launch { coordinator.withOperation { permit ->
            permit.release()
            permit.release()
            expect<InvalidOperationPermitException> { coordinator.withNestedOperation(permit) {} }
            release.await()
        } }
        runCurrent()
        assertEquals(1, coordinator.state.value.activePermits)
        val drain = async { coordinator.withExclusive(1_000) {} }
        runCurrent()
        assertFalse(drain.isCompleted)
        release.complete(Unit)
        writer.join()
        drain.await()
        assertEquals(0, coordinator.state.value.activePermits)
    }

    @Test fun `exclusive completion invalidates old generation even without a resource switch`() = runTest {
        val coordinator = MaintenanceCoordinator()
        val oldGeneration = coordinator.state.value.generation
        coordinator.withExclusive(1_000) {}
        expect<StaleMaintenanceGenerationException> { coordinator.withOperation(oldGeneration) {} }
        coordinator.withOperation(coordinator.state.value.generation) {}
    }

    @Test fun `generation cannot cross coordinators`() = runTest {
        val first = MaintenanceCoordinator()
        val second = MaintenanceCoordinator()
        expect<StaleMaintenanceGenerationException> { second.withOperation(first.state.value.generation) {} }
    }

    @Test fun `two exclusive requests cannot both own maintenance`() = runTest {
        val coordinator = MaintenanceCoordinator()
        val release = CompletableDeferred<Unit>()
        val first = launch { coordinator.withExclusive(1_000) { release.await() } }
        runCurrent()
        expect<MaintenanceUnavailableException> { coordinator.withExclusive(1_000) { fail("second owner") } }
        release.complete(Unit)
        first.join()
    }

    @Test fun `second drain cannot cancel or reopen the first drain`() = runTest {
        val coordinator = MaintenanceCoordinator()
        val release = CompletableDeferred<Unit>()
        val writer = launch { coordinator.withOperation { release.await() } }
        runCurrent()
        val first = async { coordinator.withExclusive(1_000) {} }
        runCurrent()
        val error = expect<MaintenanceUnavailableException> { coordinator.withExclusive(1_000) {} }
        assertEquals(MaintenancePhase.DRAINING, error.phase)
        assertEquals(MaintenancePhase.DRAINING, coordinator.state.value.phase)
        assertFalse(first.isCompleted)
        release.complete(Unit)
        writer.join()
        first.await()
    }

    @Test fun `cancelled nested body finishes its registration without releasing the parent`() = runTest {
        val coordinator = MaintenanceCoordinator()
        coordinator.withOperation { permit ->
            val nested = launch { coordinator.withNestedOperation(permit) { awaitCancellation() } }
            runCurrent()
            assertEquals(2, coordinator.state.value.activePermits)
            nested.cancel()
            nested.join()
            assertEquals(1, coordinator.state.value.activePermits)
            coordinator.withNestedOperation(permit) {}
        }
        assertEquals(0, coordinator.state.value.activePermits)
    }

    @Test fun `exclusive exception is safe only while resources are unchanged`() = runTest {
        val coordinator = MaintenanceCoordinator()
        expect<IllegalArgumentException> { coordinator.withExclusive(1_000) { throw IllegalArgumentException("unchanged") } }
        assertEquals(MaintenancePhase.OPEN, coordinator.state.value.phase)
        coordinator.withOperation {}
    }

    @Test fun `uncertain exclusive remains blocked and cannot reopen through stale handle`() = runTest {
        val coordinator = MaintenanceCoordinator()
        lateinit var handle: MaintenanceCoordinator.ExclusivePermit
        expect<IllegalStateException> {
            coordinator.withExclusive(1_000) {
                handle = it
                it.markUncertain("cannot prove resource state")
                throw IllegalStateException("uncertain")
            }
        }
        assertEquals(MaintenancePhase.BLOCKED, coordinator.state.value.phase)
        assertEquals("cannot prove resource state", coordinator.state.value.blockedReason)
        expect<MaintenanceUnavailableException> { coordinator.withOperation {} }
        expect<MaintenanceUnavailableException> { coordinator.withExclusive(1_000) {} }
        expect<InvalidOperationPermitException> { handle.markUncertain("stale") }
        assertEquals(MaintenancePhase.BLOCKED, coordinator.state.value.phase)
    }

    @Test fun `exclusive cancellation releases unchanged owner`() = runTest {
        val coordinator = MaintenanceCoordinator()
        val owner = launch { coordinator.withExclusive(1_000) { awaitCancellation() } }
        runCurrent()
        owner.cancel()
        owner.join()
        assertEquals(MaintenancePhase.OPEN, coordinator.state.value.phase)
        coordinator.withExclusive(1_000) {}
    }

    @Test fun `cancellation at exclusive publication never starts its body`() = runTest {
        val coordinator = MaintenanceCoordinator()
        lateinit var owner: Job
        var entered = false
        val observer = launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.state.first { it.phase == MaintenancePhase.EXCLUSIVE }
            owner.cancel()
        }
        owner = launch { coordinator.withExclusive(1_000) { entered = true } }
        owner.join()
        observer.join()
        assertFalse(entered)
        assertEquals(MaintenancePhase.OPEN, coordinator.state.value.phase)
        assertEquals(0, coordinator.state.value.activePermits)
    }

    @Test fun `structured child outliving body is counted until child completes`() = runTest {
        val coordinator = MaintenanceCoordinator()
        val childDone = CompletableDeferred<Unit>()
        val writer = launch { coordinator.withOperation { launch { childDone.await() } } }
        runCurrent()
        assertEquals(1, coordinator.state.value.activePermits)
        val drain = async { coordinator.withExclusive(1_000) {} }
        runCurrent()
        assertFalse(drain.isCompleted)
        childDone.complete(Unit)
        writer.join()
        drain.await()
    }

    @Test fun `explicitly registered detached child keeps drain pending after root completion`() = runTest {
        val coordinator = MaintenanceCoordinator()
        val detachedJob = Job()
        val detachedScope = CoroutineScope(coroutineContext + detachedJob)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        lateinit var child: Job
        coordinator.withOperation { permit ->
            child = detachedScope.launch {
                coordinator.withNestedOperation(permit) {
                    started.complete(Unit)
                    finish.await()
                }
            }
            started.await()
        }
        assertEquals(1, coordinator.state.value.activePermits)
        val drain = async { coordinator.withExclusive(1_000) {} }
        runCurrent()
        assertFalse(drain.isCompleted)
        finish.complete(Unit)
        child.join()
        drain.await()
        detachedJob.cancel()
    }

    @Test fun `unregistered detached callback cannot reuse an ended permit`() = runTest {
        val coordinator = MaintenanceCoordinator()
        lateinit var ended: MaintenanceCoordinator.OperationPermit
        coordinator.withOperation { ended = it }
        val detachedJob = Job()
        val scope = CoroutineScope(coroutineContext + detachedJob)
        scope.async {
            expect<InvalidOperationPermitException> { coordinator.withNestedOperation(ended) { fail("late work") } }
        }.await()
        assertEquals(0, coordinator.state.value.activePermits)
        detachedJob.cancel()
    }

    @Test fun `permit cannot cross coordinators`() = runTest {
        val first = MaintenanceCoordinator()
        val second = MaintenanceCoordinator()
        first.withOperation { permit ->
            expect<InvalidOperationPermitException> { second.withNestedOperation(permit) {} }
        }
    }

    @Test fun `cancel before admission creates no registered operation`() = runTest {
        val coordinator = MaintenanceCoordinator()
        val writer = launch { coordinator.withOperation { fail("cancelled body") } }
        writer.cancel()
        writer.join()
        assertEquals(0, coordinator.state.value.activePermits)
    }

    @Test fun `invalid timeout does not take maintenance ownership`() = runTest {
        val coordinator = MaintenanceCoordinator()
        expect<IllegalArgumentException> { coordinator.withExclusive(0) {} }
        assertEquals(MaintenancePhase.OPEN, coordinator.state.value.phase)
    }

    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
