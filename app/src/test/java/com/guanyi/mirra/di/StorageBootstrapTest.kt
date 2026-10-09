package com.guanyi.mirra.di

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StorageBootstrapTest {
    @Test fun pendingRecoveryReturnsToCallerWhileIoIsStillWaiting() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val serial = Mutex()
        val enteredRecovery = CountDownLatch(1)
        val finishRecovery = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val constructed = AtomicBoolean(false)
        val opened = AtomicBoolean(false)
        val callerFailure = AtomicReference<Throwable?>()
        val worker = AtomicReference<Job?>()
        val caller = thread(name = "storage-bootstrap-caller") {
            try {
                worker.set(startStorageBootstrap(scope, serial, pendingRecovery = { true },
                    recover = {
                        enteredRecovery.countDown()
                        check(finishRecovery.await(5, TimeUnit.SECONDS)) { "Test recovery release was not received" }
                    },
                    construct = { constructed.set(true); Unit },
                    open = { opened.set(true) }, close = {}, blocked = {},
                ))
            } catch (failure: Throwable) { callerFailure.set(failure) }
            finally { returned.countDown() }
        }
        try {
            assertTrue("The test must reach the paused recovery boundary", enteredRecovery.await(5, TimeUnit.SECONDS))
            assertTrue("Pending journal recovery must return to its caller before IO finishes", returned.await(1, TimeUnit.SECONDS))
            assertNull(callerFailure.get())
            assertFalse("No owner may be constructed before journal recovery finishes", constructed.get())
            assertFalse("Storage may not open before journal recovery finishes", opened.get())
            assertTrue("Startup must retain the restore serial boundary during recovery", serial.isLocked)
        } finally {
            finishRecovery.countDown()
            caller.join(5_000)
            runBlocking { worker.get()?.join(); scope.coroutineContext[Job]?.cancelAndJoin() }
            assertFalse("The captured caller must terminate after recovery is released", caller.isAlive)
        }
        assertTrue(constructed.get())
        assertTrue(opened.get())
        assertFalse(serial.isLocked)
    }

    @Test fun pendingRecoveryDefersConstructionAndKeepsSerialUntilStartupFinishes() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val serial = Mutex()
        val startup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        try {
            val job = startStorageBootstrap(scope, serial, pendingRecovery = { true },
                recover = { events += "recover" }, construct = { events += "construct"; Unit },
                open = { events += "startup"; startup.await(); events += "open" },
                close = { events += "close" }, blocked = { events += "blocked" },
            )
            assertEquals("Pending recovery must be queued instead of executing on the caller", emptyList<String>(), events)
            assertTrue(serial.isLocked)
            runCurrent()
            assertEquals(listOf("recover", "construct", "startup"), events)
            assertTrue(serial.isLocked)
            assertFalse(job.isCompleted)
            startup.complete(Unit)
            runCurrent()
            assertEquals(listOf("recover", "construct", "startup", "open"), events)
            assertTrue(job.isCompleted)
            assertFalse(serial.isLocked)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun ordinaryStartupConstructsSynchronouslyAndSkipsJournalRecovery() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val serial = Mutex()
        val events = mutableListOf<String>()
        try {
            val job = startStorageBootstrap(scope, serial, pendingRecovery = { false },
                recover = { events += "recover" }, construct = { events += "construct"; Unit },
                open = { events += "open" }, close = { events += "close" }, blocked = { events += "blocked" },
            )
            assertEquals("Existing callers must have their constructed generation after ordinary startup returns", listOf("construct"), events)
            runCurrent()
            assertEquals(listOf("construct", "open"), events)
            assertTrue(job.isCompleted)
            assertFalse(serial.isLocked)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun failedRecoveryClosesBeforeBlockingAndNeverConstructsOrOpens() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val serial = Mutex()
        val events = mutableListOf<String>()
        try {
            val job = startStorageBootstrap(scope, serial, pendingRecovery = { true },
                recover = { throw IOException("Unresolved journal") },
                construct = { events += "construct"; Unit }, open = { events += "open" },
                close = { events += "close" }, blocked = { events += "blocked" },
            )
            runCurrent()
            assertEquals(listOf("close", "blocked"), events)
            assertTrue(job.isCompleted)
            assertFalse(serial.isLocked)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun failedConstructionClosesPartialOwnersBeforeBlockingAndReleasesSerial() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val serial = Mutex()
        val events = mutableListOf<String>()
        try {
            val job = startStorageBootstrap<Unit>(scope, serial, pendingRecovery = { false },
                recover = {}, construct = { events += "construct"; throw IOException("Partial owner failed") },
                open = { events += "open" }, close = { events += "close" }, blocked = { events += "blocked" },
            )
            runCurrent()
            assertEquals(listOf("construct", "close", "blocked"), events)
            assertTrue(job.isCompleted)
            assertFalse(serial.isLocked)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun failedStartupAndCleanupStillBlockAndUnlockExactlyOnce() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val serial = Mutex()
        val events = mutableListOf<String>()
        try {
            val job = startStorageBootstrap(scope, serial, pendingRecovery = { false },
                recover = {}, construct = { events += "construct"; Unit },
                open = { throw IOException("Startup failed") },
                close = { events += "close"; throw IOException("Owner closure failed") },
                blocked = { events += "blocked" },
            )
            runCurrent()
            assertEquals(listOf("construct", "close", "blocked"), events)
            assertTrue(job.isCompleted)
            assertTrue("Another attempt must be able to acquire the released boundary", serial.tryLock())
            job.join()
            assertTrue("Completed bootstrap must not unlock a subsequent owner's boundary", serial.isLocked)
            serial.unlock()
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun duplicateBootstrapCannotConstructAnotherOwnerWhileSerialIsHeld() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val serial = Mutex()
        val startup = CompletableDeferred<Unit>()
        var owners = 0
        try {
            val job = startStorageBootstrap(scope, serial, pendingRecovery = { false },
                recover = {}, construct = { owners++; Unit }, open = { startup.await() }, close = {}, blocked = {},
            )
            runCurrent()
            try {
                startStorageBootstrap(scope, serial, pendingRecovery = { false },
                    recover = {}, construct = { owners++; Unit }, open = {}, close = {}, blocked = {},
                )
                fail("Concurrent bootstrap must not acquire the same serial boundary")
            } catch (_: IllegalStateException) { }
            assertEquals(1, owners)
            startup.complete(Unit)
            runCurrent()
            assertTrue(job.isCompleted)
            assertFalse(serial.isLocked)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun cancellationBeforeFirstDispatchClosesAndBlocksWithoutConstructingOrOpening() = runTest {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + StandardTestDispatcher(testScheduler))
        val serial = Mutex()
        val events = mutableListOf<String>()
        try {
            val job = startStorageBootstrap(scope, serial, pendingRecovery = { true },
                recover = { events += "recover" }, construct = { events += "construct"; Unit },
                open = { events += "open" }, close = { events += "close" }, blocked = { events += "blocked" },
            )
            assertTrue(serial.isLocked)
            owner.cancel()
            runCurrent()
            assertEquals(listOf("close", "blocked"), events)
            assertTrue(job.isCancelled)
            assertTrue(job.isCompleted)
            assertFalse(serial.isLocked)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun failedPendingQueryClosesAndBlocksWithoutConstructingOrOpening() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val serial = Mutex()
        val events = mutableListOf<String>()
        try {
            val job = startStorageBootstrap(scope, serial,
                pendingRecovery = { throw IOException("Journal state cannot be inspected") },
                recover = { events += "recover" }, construct = { events += "construct"; Unit },
                open = { events += "open" }, close = { events += "close" }, blocked = { events += "blocked" },
            )
            assertTrue(serial.isLocked)
            runCurrent()
            assertEquals(listOf("close", "blocked"), events)
            assertTrue(job.isCompleted)
            assertFalse(serial.isLocked)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }
}
