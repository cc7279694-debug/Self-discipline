package com.guanyi.mirra.domain.maintenance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StorageMaintenanceGateTest {
    @Test fun `same resource epoch still admits after unchanged exclusive refreshes token`() = runTest {
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        gate.operation { }
        val previous = gate.coordinator.state.value.generation
        gate.coordinator.withExclusive(1_000) { }
        assertNotSame(previous, gate.coordinator.state.value.generation)
        assertEquals(7, gate.operation { 7 })
    }

    @Test fun `registered writer continuation nests during draining and drain waits compensation`() = runTest {
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val resume = CompletableDeferred<Unit>()
        val compensation = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val writer = launch {
            gate.operation {
                resume.await()
                gate.writerOperation { effects += "write" }
                withContext(NonCancellable) {
                    compensation.await()
                    gate.writerOperation { effects += "compensate" }
                }
            }
        }
        runCurrent()
        val drain = async { gate.coordinator.withExclusive(1_000) { effects += "snapshot" } }
        runCurrent()
        resume.complete(Unit)
        runCurrent()
        assertEquals(listOf("write"), effects)
        assertFalse(drain.isCompleted)
        compensation.complete(Unit)
        writer.join()
        drain.await()
        assertEquals(listOf("write", "compensate", "snapshot"), effects)
    }

    @Test fun `detached job with sealed inherited context cannot get a replacement permit`() = runTest {
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val resume = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Throwable?>()
        gate.operation {
            val capturedContext = coroutineContext
            backgroundScope.launch(capturedContext.minusKey(kotlinx.coroutines.Job)) {
                resume.await()
                result.complete(runCatching { gate.writerOperation { fail("late write") } }.exceptionOrNull())
            }
        }
        gate.coordinator.withExclusive(1_000) { }
        resume.complete(Unit)
        runCurrent()
        assertTrue(result.await() is InvalidOperationPermitException)
        assertEquals(0, gate.coordinator.state.value.activePermits)
    }

    @Test fun `retired resource epoch cannot write after a replacement container opens`() = runTest {
        val old = StorageMaintenanceGate(leaseScope = backgroundScope)
        old.coordinator.withExclusive(1_000) { permit -> old.retire(permit, "restore replacement") }
        val replacement = StorageMaintenanceGate(leaseScope = backgroundScope)
        assertEquals(42, replacement.writerOperation { 42 })
        expect<RetiredStorageEpochException> { old.writerOperation { fail("old resource write") } }
        assertEquals(MaintenancePhase.BLOCKED, old.coordinator.state.value.phase)
    }

    @Test fun `live foreign gate context cannot confer a permit to another resource epoch`() = runTest {
        val first = StorageMaintenanceGate(leaseScope = backgroundScope)
        val second = StorageMaintenanceGate(leaseScope = backgroundScope)
        second.coordinator.withExclusive(1_000) { permit ->
            first.operation {
                expect<MaintenanceUnavailableException> { second.writerOperation { fail("foreign admission") } }
            }
            second.retire(permit, "end")
        }
    }

    @Test fun `outstanding camera lease prevents snapshot until callback chain finishes`() = runTest {
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val lease = gate.openLease()
        assertEquals(1, gate.coordinator.state.value.activePermits)
        var captured = false
        val drain = async { gate.coordinator.withExclusive(1_000) { captured = true } }
        runCurrent()
        assertFalse(captured)
        assertEquals(MaintenancePhase.DRAINING, gate.coordinator.state.value.phase)
        lease.operation { gate.writerOperation { assertFalse(captured) } }
        lease.releaseAndJoin()
        drain.await()
        assertTrue(captured)
        expect<InvalidOperationPermitException> { lease.operation { fail("late callback") } }
    }

    @Test fun `lease release waits a registered cancellation compensation before snapshot`() = runTest {
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val lease = gate.openLease()
        val completeCompensation = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val callback = launch {
            lease.operation {
                entered.complete(Unit)
                try { kotlinx.coroutines.awaitCancellation() }
                finally { withContext(NonCancellable) {
                    completeCompensation.await()
                    gate.writerOperation { effects += "compensation" }
                } }
            }
        }
        entered.await()
        callback.cancel()
        val drain = async { gate.coordinator.withExclusive(1_000) { effects += "snapshot" } }
        runCurrent()
        assertFalse(drain.isCompleted)
        completeCompensation.complete(Unit)
        callback.join()
        lease.releaseAndJoin()
        drain.await()
        assertEquals(listOf("compensation", "snapshot"), effects)
    }

    @Test fun `new writer is rejected while another operation drains`() = runTest {
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val lease = gate.openLease()
        val drain = async { gate.coordinator.withExclusive(1_000) { } }
        runCurrent()
        expect<MaintenanceUnavailableException> { gate.writerOperation { fail("new writer") } }
        lease.releaseAndJoin()
        drain.await()
    }

    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit): T {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected ${T::class.java.simpleName}, got $error", error is T)
        return error as T
    }
}
