package com.guanyi.mirra.domain.wearable

import com.guanyi.mirra.platform.wearable.WearConnection
import com.guanyi.mirra.platform.wearable.WearTransport
import com.guanyi.mirra.platform.wearable.WearTransportException
import com.guanyi.mirra.platform.wearable.WearTransportListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearableCoordinatorRecoveryTest {
    @Test fun `transient startup failures recover without granting permission or inventing a handshake`() = runTest {
        for (category in listOf("NO_DEVICE", "SERVICE_UNAVAILABLE", "TIMEOUT")) {
            var reads = 0
            val transport = Transport().apply { failuresRemaining = 2; failureCategory = category }
            val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true))
            coordinator.bindSource(WearableSnapshotSource { reads++; WearableSnapshot(WearablePhase.IDLE) })
            runCurrent()
            assertEquals(category, coordinator.state.value.errorCategory)
            advanceTimeBy(7_001); runCurrent()
            assertTrue("$category must recover when the same transport becomes available", coordinator.state.value.serviceAvailable)
            assertTrue(coordinator.state.value.authorized)
            assertNull(coordinator.state.value.errorCategory)
            assertEquals(listOf(false, false, false), transport.grantsRequested)
            assertFalse(coordinator.state.value.handshaken)
            assertNull(coordinator.state.value.lastSnapshotAt)
            assertEquals(0, reads)
            assertTrue(transport.sent.isEmpty())
            coordinator.close()
        }
    }

    @Test fun `only the first user attempt may open authorization`() = runTest {
        val transport = Transport().apply { failuresRemaining = 2 }
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(false))
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        coordinator.enableFromUser(); runCurrent()
        advanceTimeBy(7_001); runCurrent()
        assertTrue(coordinator.state.value.authorized)
        assertEquals(listOf(true, false, false), transport.grantsRequested)
        assertFalse(coordinator.state.value.handshaken)
    }

    @Test fun `a device appearing after two minutes is found with a bounded low frequency cadence`() = runTest {
        val transport = Transport().apply {
            clock = { testScheduler.currentTime }
            unavailableUntil = 125_000
        }
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true))
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertFalse(coordinator.state.value.serviceAvailable)
        assertTrue("retry cadence must not flood an unavailable SDK", transport.attemptTimes.size <= 6)
        advanceTimeBy(60_001); runCurrent()
        assertTrue("a later device must not remain stuck after the initial retry burst", coordinator.state.value.serviceAvailable)
        assertTrue(transport.attemptTimes.size > 1)
        assertTrue(transport.attemptTimes.zipWithNext().all { (before, after) -> after - before >= 2_000 })
        assertTrue(transport.attemptTimes.takeLast(2).zipWithNext().all { (before, after) -> after - before >= 60_000 })
        val attempts = transport.grantsRequested.size
        advanceTimeBy(180_000); runCurrent()
        assertEquals("successful readiness stops retries", attempts, transport.grantsRequested.size)
        assertEquals(1, transport.peakConnections)
        assertFalse(coordinator.state.value.handshaken)
    }

    @Test fun `non transient failures stop permanently without automatic authorization`() = runTest {
        for (category in listOf("NOT_AUTHORIZED", "AUTHORIZATION_DENIED", "SIGNING_IDENTITY_MISMATCH",
            "MULTIPLE_DEVICES", "WRONG_DEVICE", "INVALID_DEVICE", "CLEANUP_PENDING",
            "COUNTERPART_APP_MISSING", "SDK_ERROR")) {
            val transport = Transport().apply { failuresRemaining = Int.MAX_VALUE; failureCategory = category }
            val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true))
            coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
            runCurrent()
            advanceTimeBy(240_000); runCurrent()
            assertEquals(category, coordinator.state.value.errorCategory)
            assertEquals("$category must not be retried", listOf(false), transport.grantsRequested)
            assertFalse(coordinator.state.value.handshaken)
            assertNull(coordinator.state.value.lastSnapshotAt)
            coordinator.close()
        }
    }

    @Test fun `a permanent failure reached during recovery stops the same retry cycle`() = runTest {
        val transport = Transport().apply { failuresRemaining = Int.MAX_VALUE }
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true))
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        runCurrent()
        transport.failureCategory = "NOT_AUTHORIZED"
        advanceTimeBy(2_001); runCurrent()
        assertEquals("NOT_AUTHORIZED", coordinator.state.value.errorCategory)
        advanceTimeBy(180_000); runCurrent()
        assertEquals(listOf(false, false), transport.grantsRequested)
        assertFalse(coordinator.state.value.authorized)
    }

    @Test fun `disable source pause and close cancel the pending recovery owner`() = runTest {
        for (retirement in listOf("disable", "pause", "close")) {
            val transport = Transport().apply { failuresRemaining = Int.MAX_VALUE }
            val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true))
            coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
            runCurrent()
            advanceTimeBy(2_001); runCurrent()
            assertEquals(2, transport.grantsRequested.size)
            when (retirement) {
                "disable" -> coordinator.disableFromUser()
                "pause" -> coordinator.pauseSource()
                "close" -> coordinator.close()
            }
            runCurrent()
            transport.failuresRemaining = 0
            advanceTimeBy(180_000); runCurrent()
            assertEquals("$retirement must retire old recovery", 2, transport.grantsRequested.size)
            assertFalse(coordinator.state.value.handshaken)
            assertNull(coordinator.state.value.lastSnapshotAt)
        }
    }

    @Test fun `manual reconnect cancels an in flight retry before replacing its owner`() = runTest {
        val barrier = CompletableDeferred<Unit>()
        val transport = Transport().apply { failuresRemaining = 1; secondAttemptBarrier = barrier }
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true))
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        runCurrent()
        advanceTimeBy(2_001); runCurrent()
        assertEquals(2, transport.grantsRequested.size)
        coordinator.reconnectFromUser(); runCurrent()
        assertTrue(coordinator.state.value.authorized)
        assertEquals(listOf(false, false, true), transport.grantsRequested)
        assertEquals(1, transport.peakConnections)
        barrier.complete(Unit); runCurrent()
        advanceTimeBy(180_000); runCurrent()
        assertEquals(3, transport.grantsRequested.size)
        assertFalse(coordinator.state.value.handshaken)
    }

    @Test fun `cancelled application scope cannot leave an automatic retry behind`() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        try {
            val transport = Transport().apply { failuresRemaining = Int.MAX_VALUE }
            val coordinator = WearableCoordinator(owner, transport, Settings(true))
            coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
            runCurrent()
            advanceTimeBy(2_001); runCurrent()
            assertEquals(2, transport.grantsRequested.size)
            owner.cancel()
            advanceTimeBy(180_000); runCurrent()
            assertEquals(2, transport.grantsRequested.size)
            assertFalse(coordinator.state.value.serviceAvailable)
        } finally { owner.cancel() }
    }

    @Test fun `source rebinding retires the old retry and exposes only the new source after a real request`() = runTest {
        var oldReads = 0
        var newReads = 0
        val transport = Transport().apply { failuresRemaining = Int.MAX_VALUE }
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true), { 1_000 }, { "stream" })
        coordinator.bindSource(WearableSnapshotSource { oldReads++; WearableSnapshot(WearablePhase.IDLE) })
        runCurrent()
        advanceTimeBy(2_001); runCurrent()
        assertEquals(2, transport.grantsRequested.size)
        transport.failuresRemaining = 0
        coordinator.bindSource(WearableSnapshotSource {
            newReads++
            WearableSnapshot(WearablePhase.IDLE, learningItemId = "new-book", bookTitle = "New source title")
        })
        runCurrent()
        assertTrue(coordinator.state.value.serviceAvailable)
        assertFalse(coordinator.state.value.handshaken)
        advanceTimeBy(180_000); runCurrent()
        assertEquals(3, transport.grantsRequested.size)
        assertEquals(0, oldReads)
        assertEquals(0, newReads)
        val current = checkNotNull(transport.callback)
        current.onMessage("node", HELLO.toByteArray()); runCurrent()
        current.onMessage("node", """{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"fresh","streamId":"stream","lastSeenSessionId":null}""".toByteArray())
        runCurrent()
        assertEquals(0, oldReads)
        assertEquals(1, newReads)
        assertTrue(transport.sent.any { String(it, Charsets.UTF_8).contains("New source title") })
        assertEquals(1_000L, coordinator.state.value.lastSnapshotAt)
    }

    @Test fun `a permanent SDK failure after service loss cannot be bypassed by availability callbacks`() = runTest {
        val transport = Transport()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true))
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        runCurrent()
        val oldListener = checkNotNull(transport.callback)
        transport.failuresRemaining = Int.MAX_VALUE
        transport.failureCategory = "SIGNING_IDENTITY_MISMATCH"
        oldListener.onDisconnected(); runCurrent()
        assertEquals("SIGNING_IDENTITY_MISMATCH", coordinator.state.value.errorCategory)
        assertNull(transport.callback)
        val current = transport.callback ?: oldListener
        repeat(5) { oldListener.onAvailable(); current.onAvailable(); runCurrent() }
        advanceTimeBy(180_000); runCurrent()
        assertEquals(listOf(false, false), transport.grantsRequested)
        assertFalse(coordinator.state.value.serviceAvailable)
        assertFalse(coordinator.state.value.authorized)
        assertFalse(coordinator.state.value.handshaken)
    }

    @Test fun `an ungranted connection ends recovery without repeated authorization`() = runTest {
        val transport = Transport().apply { authorized = false }
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true))
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        runCurrent()
        advanceTimeBy(180_000); runCurrent()
        assertEquals("NOT_AUTHORIZED", coordinator.state.value.errorCategory)
        assertFalse(coordinator.state.value.authorized)
        assertFalse(coordinator.state.value.handshaken)
        assertEquals(listOf(false), transport.grantsRequested)
    }

    @Test fun `availability callbacks cannot reset an active recovery backoff`() = runTest {
        val transport = Transport()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true))
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        runCurrent()
        val oldListener = checkNotNull(transport.callback)
        transport.failuresRemaining = Int.MAX_VALUE
        transport.failureCategory = "SERVICE_UNAVAILABLE"
        oldListener.onDisconnected(); runCurrent()
        val recoveringListener = checkNotNull(transport.callback)
        assertEquals(2, transport.grantsRequested.size)
        repeat(10) {
            recoveringListener.onAvailable()
            oldListener.onAvailable()
            advanceTimeBy(100); runCurrent()
        }
        assertEquals("availability must neither bypass nor reset backoff", 2, transport.grantsRequested.size)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(3, transport.grantsRequested.size)
        assertFalse(coordinator.state.value.handshaken)
    }

    @Test fun `repeated disconnects cannot replace an already active recovery owner`() = runTest {
        val transport = Transport()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true))
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        runCurrent()
        transport.failuresRemaining = Int.MAX_VALUE
        checkNotNull(transport.callback).onDisconnected(); runCurrent()
        assertEquals(2, transport.grantsRequested.size)
        repeat(10) {
            checkNotNull(transport.callback).onDisconnected()
            advanceTimeBy(100); runCurrent()
        }
        assertEquals("repeated loss must not turn backoff into immediate SDK calls", 2, transport.grantsRequested.size)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(3, transport.grantsRequested.size)
        assertFalse(coordinator.state.value.handshaken)
    }

    @Test fun `permanent channel errors remain latched across later native events and source pause`() = runTest {
        for (category in listOf("SIGNING_IDENTITY_MISMATCH", "NOT_AUTHORIZED", "CLEANUP_PENDING")) {
            val transport = Transport()
            val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true))
            coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
            runCurrent()
            val original = checkNotNull(transport.callback)
            original.onError(category); runCurrent()
            assertEquals(category, coordinator.state.value.errorCategory)
            val facade = transport.callback ?: original
            facade.onMessage("node", HELLO.toByteArray())
            facade.onDisconnected()
            facade.onAvailable()
            runCurrent()
            assertEquals("later events must not erase $category", category, coordinator.state.value.errorCategory)
            assertEquals(listOf(false), transport.grantsRequested)
            assertTrue(transport.sent.isEmpty())
            assertFalse(coordinator.state.value.handshaken)
            coordinator.pauseSource(); runCurrent()
            (transport.callback ?: facade).onMessage("node", HELLO.toByteArray())
            (transport.callback ?: facade).onDisconnected()
            runCurrent()
            assertEquals(category, coordinator.state.value.errorCategory)
            assertTrue(transport.sent.isEmpty())
            assertEquals(listOf(false), transport.grantsRequested)
            coordinator.close()
        }
    }

    @Test fun `an explicit reconnect or a fresh source owner may retire a permanent channel stop`() = runTest {
        for (reset in listOf("reconnect", "enable", "fresh-source")) {
            val transport = Transport()
            val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true))
            coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
            runCurrent()
            val original = checkNotNull(transport.callback)
            original.onError("NOT_AUTHORIZED"); runCurrent()
            when (reset) {
                "reconnect" -> coordinator.reconnectFromUser()
                "enable" -> coordinator.enableFromUser()
                "fresh-source" -> coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
            }
            runCurrent()
            assertTrue(coordinator.state.value.authorized)
            assertNull(coordinator.state.value.errorCategory)
            assertEquals(if (reset == "fresh-source") listOf(false, false) else listOf(false, true), transport.grantsRequested)
            original.onDisconnected(); original.onAvailable(); runCurrent()
            assertEquals(2, transport.grantsRequested.size)
            assertFalse(coordinator.state.value.handshaken)
            checkNotNull(transport.callback).onMessage("node", HELLO.toByteArray()); runCurrent()
            assertTrue(coordinator.state.value.handshaken)
            coordinator.close()
        }
    }

    @Test fun `a late HELLO send success cannot clear a permanent error before lifecycle retirement runs`() = runTest {
        val transport = Transport()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true))
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        runCurrent()
        val original = checkNotNull(transport.callback)
        transport.beforeSendReturns = { original.onError("SIGNING_IDENTITY_MISMATCH") }
        original.onMessage("node", HELLO.toByteArray())
        // The native success returns before the queued lifecycle drain can advance the epoch.
        assertEquals("SIGNING_IDENTITY_MISMATCH", coordinator.state.value.errorCategory)
        assertFalse(coordinator.state.value.handshaken)
        assertNull(coordinator.state.value.lastSnapshotAt)
        runCurrent()
        assertEquals("SIGNING_IDENTITY_MISMATCH", coordinator.state.value.errorCategory)
    }

    @Test fun `an SDK ready result cannot transiently publish success after a permanent native event`() = runTest {
        val transport = Transport()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true))
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        runCurrent()
        val original = checkNotNull(transport.callback)
        val observed = mutableListOf<WearableCompanionState>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.state.collect { observed += it }
        }
        observed.clear()
        transport.beforeConnectReturns = { transport.callback?.onError("SIGNING_IDENTITY_MISMATCH") }
        original.onDisconnected(); runCurrent()
        assertEquals("SIGNING_IDENTITY_MISMATCH", coordinator.state.value.errorCategory)
        assertFalse("even a brief UI success after a permanent error is forbidden", observed.any { it.serviceAvailable && it.authorized })
        assertFalse(coordinator.state.value.handshaken)
        observer.cancel()
    }

    @Test fun `service loss recovers automatically but never restores an old stream`() = runTest {
        val transport = Transport()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(true), { 1_000 }, { "stream" })
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        runCurrent()
        val oldListener = checkNotNull(transport.callback)
        oldListener.onMessage("node", HELLO.toByteArray())
        runCurrent()
        assertTrue(coordinator.state.value.handshaken)
        transport.failuresRemaining = 2
        transport.failureCategory = "SERVICE_UNAVAILABLE"
        oldListener.onDisconnected(); runCurrent()
        assertFalse(coordinator.state.value.handshaken)
        assertNull(coordinator.state.value.lastSnapshotAt)
        advanceTimeBy(7_001); runCurrent()
        assertTrue(coordinator.state.value.serviceAvailable)
        assertFalse(coordinator.state.value.handshaken)
        val attempts = transport.grantsRequested.size
        oldListener.onAvailable()
        oldListener.onMessage("node", HELLO.toByteArray())
        runCurrent()
        assertEquals(attempts, transport.grantsRequested.size)
        assertFalse(coordinator.state.value.handshaken)
        assertEquals(listOf(false, false, false, false), transport.grantsRequested)
    }

    private class Settings(private var selected: Boolean) : WearableSettingsStore {
        override val enabled get() = selected
        override fun setEnabled(value: Boolean) { selected = value }
    }

    /** The external SDK boundary is controlled; coordinator ownership and state remain real. */
    private class Transport : WearTransport {
        var failuresRemaining = 0
        var authorized = true
        var failureCategory = "NO_DEVICE"
        var clock: () -> Long = { 0 }
        var unavailableUntil = 0L
        var secondAttemptBarrier: CompletableDeferred<Unit>? = null
        var beforeConnectReturns: (() -> Unit)? = null
        var beforeSendReturns: (() -> Unit)? = null
        var callback: WearTransportListener? = null
        var activeConnections = 0
        var peakConnections = 0
        val grantsRequested = mutableListOf<Boolean>()
        val attemptTimes = mutableListOf<Long>()
        val sent = mutableListOf<ByteArray>()
        override suspend fun connect(requestAuthorization: Boolean): WearConnection {
            grantsRequested += requestAuthorization
            attemptTimes += clock()
            activeConnections++
            peakConnections = maxOf(peakConnections, activeConnections)
            try {
                if (grantsRequested.size == 2) secondAttemptBarrier?.await()
                if (failuresRemaining > 0 || clock() < unavailableUntil) {
                    if (failuresRemaining > 0) failuresRemaining--
                    throw WearTransportException(failureCategory)
                }
                beforeConnectReturns?.invoke()
                return WearConnection("node", 1, authorized, "Band")
            } finally { activeConnections-- }
        }
        override suspend fun send(nodeId: String, payload: ByteArray) {
            sent += payload.copyOf()
            beforeSendReturns?.invoke()
        }
        override fun setListener(listener: WearTransportListener?) { callback = listener }
        override suspend fun close() { callback = null }
    }

    private companion object {
        const val HELLO = """{"protocolVersion":1,"type":"HELLO","clientInstanceId":"client","requestId":"hello","capabilities":["READ_ONLY_SNAPSHOT"]}"""
    }
}
