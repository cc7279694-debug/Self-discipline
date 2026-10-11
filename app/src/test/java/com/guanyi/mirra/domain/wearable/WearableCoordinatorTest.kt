package com.guanyi.mirra.domain.wearable

import com.guanyi.mirra.platform.wearable.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearableCoordinatorTest {
    private class Transport : WearTransport {
        var node = "node"
        var authorized = true
        var appInstalled = true
        var appFailure: Exception? = null
        var appBarrier: CompletableDeferred<Unit>? = null
        var ignoreAppCancellation = false
        val appQueries = mutableListOf<String>()
        override suspend fun isWearAppInstalled(nodeId: String): Boolean {
            appQueries += nodeId
            if (ignoreAppCancellation) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { appBarrier?.await() }
            else appBarrier?.await()
            appFailure?.let { throw it }
            return appInstalled
        }
        var callback: WearTransportListener? = null
        var connects = 0
        val grantsRequested = mutableListOf<Boolean>()
        val sent = mutableListOf<String>()
        override suspend fun connect(requestAuthorization: Boolean): WearConnection { connects++; grantsRequested += requestAuthorization; return WearConnection(node, 1, authorized) }
        override suspend fun send(nodeId: String, payload: ByteArray) { sent += String(payload, Charsets.UTF_8) }
        override fun setListener(listener: WearTransportListener?) { this.callback = listener }
        override suspend fun close() { callback = null }
        fun message(text: String, node: String = "node") { callback?.onMessage(node, text.toByteArray(Charsets.UTF_8)) }
    }
    private class Settings : WearableSettingsStore {
        private var selected = false
        override val enabled get() = selected
        override fun setEnabled(value: Boolean) { selected = value }
    }
    private val hello = """{"protocolVersion":1,"type":"HELLO","clientInstanceId":"client","requestId":"hello","capabilities":["READ_ONLY_SNAPSHOT"]}"""
    @Test fun `manual app check is disabled until an authorized connection exists`() = runTest {
        val transport = Transport()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings())
        coordinator.checkWearAppFromUser(); runCurrent()
        assertTrue(transport.appQueries.isEmpty())
        assertEquals(0, transport.connects)
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        transport.authorized = false
        coordinator.enableFromUser(); runCurrent()
        coordinator.checkWearAppFromUser(); runCurrent()
        assertTrue(transport.appQueries.isEmpty())
        assertFalse(coordinator.state.value.appCheckInProgress)
    }

    @Test fun `app presence is independent of handshake and confirmed sync`() = runTest {
        val transport = Transport()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(), { 1_000 }, newId = { "stream" })
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        coordinator.enableFromUser(); runCurrent()
        coordinator.checkWearAppFromUser(); runCurrent()
        assertEquals(true, coordinator.state.value.appInstalled)
        assertFalse(coordinator.state.value.handshaken)
        assertNull(coordinator.state.value.lastSnapshotAt)
        transport.message(hello); runCurrent()
        transport.message("""{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"get","streamId":"stream","lastSeenSessionId":null}"""); runCurrent()
        val before = coordinator.state.value
        transport.appInstalled = false
        coordinator.checkWearAppFromUser(); runCurrent()
        assertEquals(before.copy(appInstalled = false), coordinator.state.value)
        assertEquals(listOf("node", "node"), transport.appQueries)
        assertEquals(1, transport.connects)
        assertEquals(2, transport.sent.size)
    }

    @Test fun `overlapping app check and snapshot preserve both independent results`() = runTest {
        for (snapshotFirst in listOf(true, false)) {
            val checkFinished = CompletableDeferred<Unit>()
            val sourceFinished = CompletableDeferred<Unit>()
            val transport = Transport().apply { appBarrier = checkFinished }
            val coordinator = WearableCoordinator(backgroundScope, transport, Settings(), { 1_000 }, newId = { "stream" })
            coordinator.bindSource(WearableSnapshotSource { sourceFinished.await(); WearableSnapshot(WearablePhase.IDLE) })
            coordinator.enableFromUser(); runCurrent(); transport.message(hello); runCurrent()
            val listener = transport.callback
            coordinator.checkWearAppFromUser(); runCurrent()
            transport.message("""{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"get","streamId":"stream","lastSeenSessionId":null}"""); runCurrent()
            assertTrue(coordinator.state.value.appCheckInProgress)
            assertNull(coordinator.state.value.lastSnapshotAt)
            if (snapshotFirst) sourceFinished.complete(Unit) else checkFinished.complete(Unit)
            runCurrent()
            if (snapshotFirst) {
                assertTrue(coordinator.state.value.appCheckInProgress)
                assertEquals(1_000L, coordinator.state.value.lastSnapshotAt)
            } else {
                assertEquals(true, coordinator.state.value.appInstalled)
                assertFalse(coordinator.state.value.appCheckInProgress)
                assertNull(coordinator.state.value.lastSnapshotAt)
            }
            sourceFinished.complete(Unit); checkFinished.complete(Unit); runCurrent()
            assertEquals(true, coordinator.state.value.appInstalled)
            assertFalse(coordinator.state.value.appCheckInProgress)
            assertTrue(coordinator.state.value.handshaken)
            assertEquals(1_000L, coordinator.state.value.lastSnapshotAt)
            assertSame(listener, transport.callback)
            assertEquals(1, transport.connects)
            assertEquals(2, transport.sent.size)
        }
    }

    @Test fun `manual app check is single flight and bounded`() = runTest {
        val transport = Transport().apply { appBarrier = CompletableDeferred() }
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings())
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        coordinator.enableFromUser(); runCurrent()
        coordinator.checkWearAppFromUser(); coordinator.checkWearAppFromUser(); runCurrent()
        assertTrue(coordinator.state.value.appCheckInProgress)
        assertEquals(listOf("node"), transport.appQueries)
        advanceTimeBy(3_001); runCurrent()
        assertEquals("TIMEOUT", coordinator.state.value.appCheckError)
        assertNull(coordinator.state.value.appInstalled)
        assertFalse(coordinator.state.value.appCheckInProgress)
        assertTrue(coordinator.state.value.authorized)
        assertTrue(coordinator.state.value.serviceAvailable)
        assertEquals(1, transport.connects)
        transport.appBarrier?.complete(Unit); runCurrent()
        assertEquals("TIMEOUT", coordinator.state.value.appCheckError)
    }

    @Test fun `manual check exposes fixed errors without private native text`() = runTest {
        val transport = Transport()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings())
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        coordinator.enableFromUser(); runCurrent()
        for ((failure, expected) in listOf(
            WearTransportException("SIGNING_IDENTITY_MISMATCH") to "SIGNING_IDENTITY_MISMATCH",
            WearTransportException("NOT_AUTHORIZED") to "NOT_AUTHORIZED",
            WearTransportException("WRONG_DEVICE") to "WRONG_DEVICE",
            WearTransportException("private-native-message") to "SDK_ERROR",
            IllegalStateException("private-native-message") to "SDK_ERROR",
        )) {
            transport.appFailure = failure
            coordinator.checkWearAppFromUser(); runCurrent()
            assertEquals(expected, coordinator.state.value.appCheckError)
            assertFalse(coordinator.state.value.toString().contains("private-native-message"))
            assertFalse(coordinator.state.value.handshaken)
            assertNull(coordinator.state.value.lastSnapshotAt)
        }
    }

    @Test fun `lifecycle retirement rejects a late check from the old node without reconnecting listeners`() = runTest {
        for (retire in listOf("disable", "reconnect", "pause", "close", "ui-stop", "disconnect")) {
            val transport = Transport().apply { appBarrier = CompletableDeferred(); ignoreAppCancellation = true }
            val coordinator = WearableCoordinator(backgroundScope, transport, Settings())
            coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
            coordinator.enableFromUser(); runCurrent()
            val listener = transport.callback
            coordinator.checkWearAppFromUser(); runCurrent()
            when (retire) {
                "disable" -> coordinator.disableFromUser()
                "reconnect" -> { transport.node = "replacement"; coordinator.reconnectFromUser() }
                "pause" -> coordinator.pauseSource()
                "close" -> coordinator.close()
                "ui-stop" -> coordinator.cancelWearAppCheck()
                "disconnect" -> checkNotNull(transport.callback).onDisconnected()
            }
            runCurrent()
            val after = coordinator.state.value
            assertFalse(after.appCheckInProgress)
            assertNull(after.appInstalled)
            transport.appBarrier?.complete(Unit); runCurrent()
            assertEquals(after, coordinator.state.value)
            assertEquals(if (retire == "reconnect") 2 else 1, transport.connects)
            assertEquals(listOf("node"), transport.appQueries)
            if (retire == "ui-stop") assertSame(listener, transport.callback)
        }
    }

    @Test fun `default off does not initialize SDK or read source`() = runTest {
        val transport = Transport()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(), { 1_000 })
        coordinator.bindSource(WearableSnapshotSource { fail("off read"); WearableSnapshot(WearablePhase.IDLE) })
        runCurrent()
        assertEquals(0, transport.connects)
        assertNull(transport.callback)
    }
    @Test fun `handshake selected node only and duplicate request cannot reread`() = runTest {
        val transport = Transport()
        var reads = 0
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(), { 1_000 }, newId = { "stream" })
        coordinator.bindSource(WearableSnapshotSource { reads++; WearableSnapshot(WearablePhase.IDLE) })
        coordinator.enableFromUser()
        runCurrent()
        transport.message(hello, "foreign"); runCurrent(); assertEquals(0, transport.sent.size)
        transport.message(hello); runCurrent(); assertTrue(transport.sent.single().contains("HELLO_ACK"))
        val get = """{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"get","streamId":"stream","lastSeenSessionId":null}"""
        transport.message(get); runCurrent()
        transport.message(get); runCurrent()
        assertEquals(1, reads)
        assertTrue(transport.sent.last().contains("SNAPSHOT"))
        coordinator.disableFromUser(); runCurrent()
        assertFalse(coordinator.state.value.enabled)
        assertNull(transport.callback)
    }
    @Test fun `maintenance cancels slow read and never sends old personal data`() = runTest {
        val transport = Transport()
        val waiting = CompletableDeferred<Unit>()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(), { 1_000 }, newId = { "stream" })
        coordinator.bindSource(WearableSnapshotSource { waiting.await(); WearableSnapshot(WearablePhase.IDLE, bookTitle = "private old") })
        coordinator.enableFromUser(); runCurrent(); transport.message(hello); runCurrent()
        transport.message("""{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"get","streamId":"stream","lastSeenSessionId":null}""")
        runCurrent(); coordinator.pauseSource(); runCurrent(); waiting.complete(Unit); runCurrent()
        assertFalse(transport.sent.any { it.contains("private old") })
        assertNull(coordinator.state.value.lastSnapshotAt)
    }
    @Test fun `foreign last seen and old client cannot cause history reads`() = runTest {
        val transport = Transport(); val priorIds = mutableListOf<String?>()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(), { 1_000 }, newId = { "stream" })
        coordinator.bindSource(WearableSnapshotSource { priorIds += it; WearableSnapshot(WearablePhase.IDLE) })
        coordinator.enableFromUser(); runCurrent(); transport.message(hello); runCurrent()
        transport.message("""{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"get","streamId":"stream","lastSeenSessionId":"foreign-session"}""")
        runCurrent(); assertEquals(listOf<String?>(null), priorIds)
        transport.message("""{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"old-client","requestId":"get2","streamId":"stream","lastSeenSessionId":null}""")
        runCurrent(); assertEquals(listOf<String?>(null), priorIds)
    }
    @Test fun `source timeout cannot send late snapshot or block next hello`() = runTest {
        val transport = Transport(); val late = CompletableDeferred<Unit>()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(), { 1_000 }, newId = { "stream" })
        coordinator.bindSource(WearableSnapshotSource { late.await(); WearableSnapshot(WearablePhase.IDLE) })
        coordinator.enableFromUser(); runCurrent(); transport.message(hello); runCurrent()
        transport.message("""{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"get","streamId":"stream","lastSeenSessionId":null}""")
        runCurrent(); advanceTimeBy(3_001); runCurrent(); late.complete(Unit); runCurrent()
        assertFalse(transport.sent.any { it.contains("\"SNAPSHOT\"") })
        transport.message(hello.replace("hello", "hello2")); runCurrent()
        assertEquals(2, transport.sent.size)
    }
    @Test fun `paused source status echoes request ownership without personal body`() = runTest {
        val transport = Transport()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(), { 1_000 }, newId = { "stream" })
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        coordinator.enableFromUser(); runCurrent(); transport.message(hello); runCurrent()
        coordinator.pauseSource(); runCurrent()
        transport.message("""{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"get","streamId":"stream","lastSeenSessionId":null}""")
        runCurrent()
        assertTrue(transport.sent.last().contains("\"streamId\":\"stream\""))
        assertTrue(transport.sent.last().contains("SOURCE_BUSY"))
        assertFalse(transport.sent.last().contains("bookTitle"))
    }
    @Test fun `wrist reentry new client can follow the same phone session`() = runTest {
        val transport = Transport(); val ids = mutableListOf<String?>()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(), { 1_000 }, newId = { "stream" })
        coordinator.bindSource(WearableSnapshotSource { prior ->
            ids += prior
            WearableSnapshot(WearablePhase.ACTIVE, sessionId = "followed", learningItemId = "book",
                segmentType = "FOCUS", coverage = "FULL")
        })
        coordinator.enableFromUser(); runCurrent(); transport.message(hello); runCurrent()
        transport.message("""{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"get","streamId":"stream","lastSeenSessionId":null}"""); runCurrent()
        transport.message(hello.replace("\"client\"", "\"newclient\"").replace("\"hello\"", "\"hello2\"")); runCurrent()
        assertTrue(transport.sent.last().contains("\"type\":\"HELLO_ACK\""))
        assertTrue(transport.sent.last().contains("\"clientInstanceId\":\"newclient\""))
        transport.message("""{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"newclient","requestId":"get2","streamId":"stream","lastSeenSessionId":"followed"}"""); runCurrent()
        assertEquals(listOf(null, "followed"), ids)
        assertTrue(transport.sent.last().contains("\"type\":\"SNAPSHOT\""))
    }
    @Test fun `fast official service return reconnects once without granting or snapshot freshness`() = runTest {
        val transport = Transport()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(), { 1_000 }, newId = { "stream" })
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        coordinator.enableFromUser(); runCurrent(); transport.message(hello); runCurrent()
        val oldCallback = checkNotNull(transport.callback)
        oldCallback.onDisconnected(); oldCallback.onAvailable(); runCurrent()
        assertEquals(listOf(true, false), transport.grantsRequested)
        assertFalse(coordinator.state.value.handshaken)
        assertNull(coordinator.state.value.lastSnapshotAt)
        transport.message(hello.replace("\"hello\"", "\"returned\"")); runCurrent()
        assertTrue(coordinator.state.value.handshaken)
        assertNull(coordinator.state.value.lastSnapshotAt)
        checkNotNull(transport.callback).onAvailable(); runCurrent()
        assertEquals(2, transport.connects)
        coordinator.disableFromUser(); runCurrent(); oldCallback.onAvailable(); runCurrent()
        assertEquals(2, transport.connects)
    }
    @Test fun `new source cannot reveal old followed historical session`() = runTest {
        val transport = Transport(); var newSourcePrior: String? = "not-called"
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(), { 1_000 }, newId = { "stream" })
        coordinator.bindSource(WearableSnapshotSource {
            WearableSnapshot(WearablePhase.ACTIVE, sessionId = "old-followed", learningItemId = "book", segmentType = "FOCUS", coverage = "FULL")
        })
        coordinator.enableFromUser(); runCurrent(); transport.message(hello); runCurrent()
        transport.message("""{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"get","streamId":"stream","lastSeenSessionId":null}"""); runCurrent()
        coordinator.pauseSource(); coordinator.bindSource(WearableSnapshotSource { prior -> newSourcePrior = prior; WearableSnapshot(WearablePhase.IDLE) }); runCurrent()
        transport.message(hello.replace("hello", "hello2")); runCurrent()
        transport.message("""{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"get2","streamId":"stream","lastSeenSessionId":"old-followed"}"""); runCurrent()
        assertNull(newSourcePrior)
    }
    @Test fun `encoding failure cannot mark sync or authorize followed history`() = runTest {
        val transport = Transport(); val priorIds = mutableListOf<String?>()
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(), { 1_000 }, newId = { "stream" })
        coordinator.bindSource(WearableSnapshotSource { prior ->
            priorIds += prior
            when {
                priorIds.size == 1 -> WearableSnapshot(WearablePhase.ACTIVE, sessionId = "not-emitted",
                    learningItemId = "book", currentPage = 0, totalPages = 300, segmentType = "FOCUS", coverage = "FULL")
                prior != null -> WearableSnapshot(WearablePhase.ENDED, sessionId = prior,
                    learningItemId = "book", currentPage = 46, totalPages = 300, endType = "NORMAL")
                else -> WearableSnapshot(WearablePhase.IDLE)
            }
        })
        coordinator.enableFromUser(); runCurrent(); transport.message(hello); runCurrent()
        transport.message("""{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"invalid","streamId":"stream","lastSeenSessionId":null}"""); runCurrent()
        val failedState = coordinator.state.value
        assertTrue(transport.sent.last().contains("\"type\":\"STATUS_ERROR\""))
        assertTrue(transport.sent.last().contains("SOURCE_UNAVAILABLE"))
        assertFalse(transport.sent.last().contains("\"body\""))
        transport.message("""{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"next","streamId":"stream","lastSeenSessionId":"not-emitted"}"""); runCurrent()
        assertNull(failedState.lastSnapshotAt)
        assertEquals("SOURCE_UNAVAILABLE", failedState.errorCategory)
        assertEquals(listOf<String?>(null, null), priorIds)
        assertTrue(transport.sent.last().contains("\"phase\":\"IDLE\""))
    }
    @Test fun `complete snapshot freshness uses its observed time despite a later clock jump`() = runTest {
        val transport = Transport(); var clockReads = 0
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(),
            clock = { if (clockReads++ == 0) 1_000 else 0 }, newId = { "stream" })
        coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) })
        coordinator.enableFromUser(); runCurrent(); transport.message(hello); runCurrent()
        transport.message("""{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"get","streamId":"stream","lastSeenSessionId":null}"""); runCurrent()
        assertTrue(transport.sent.last().contains("\"type\":\"SNAPSHOT\""))
        assertTrue(transport.sent.last().contains("\"sourceObservedAtUnixMs\":1000"))
        assertEquals(1_000L, coordinator.state.value.lastSnapshotAt)
        assertEquals(1, clockReads)
    }
    @Test fun `source errors retain only confirmed sync time and recovery clears diagnosis`() = runTest {
        val transport = Transport(); var readCount = 0; var observedTime = 1_000L
        val coordinator = WearableCoordinator(backgroundScope, transport, Settings(), { observedTime }, newId = { "stream" })
        coordinator.bindSource(WearableSnapshotSource {
            when (readCount++) { 1 -> throw WearableSourceBusy(); 2 -> throw WearableSourceUnavailable() }
            WearableSnapshot(WearablePhase.IDLE)
        })
        coordinator.enableFromUser(); runCurrent(); transport.message(hello); runCurrent()
        fun get(id: String) = transport.message("""{"protocolVersion":1,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"$id","streamId":"stream","lastSeenSessionId":null}""")
        get("confirmed"); runCurrent()
        assertEquals(1_000L, coordinator.state.value.lastSnapshotAt)
        observedTime = 2_000
        get("busy"); runCurrent(); val busyState = coordinator.state.value
        assertTrue(transport.sent.last().contains("SOURCE_BUSY"))
        get("unavailable"); runCurrent(); val unavailableState = coordinator.state.value
        assertTrue(transport.sent.last().contains("SOURCE_UNAVAILABLE"))
        observedTime = 3_000
        get("recovered"); runCurrent()
        assertEquals("SOURCE_BUSY", busyState.errorCategory)
        assertEquals("SOURCE_UNAVAILABLE", unavailableState.errorCategory)
        assertEquals(1_000L, busyState.lastSnapshotAt)
        assertEquals(1_000L, unavailableState.lastSnapshotAt)
        assertEquals(3_000L, coordinator.state.value.lastSnapshotAt)
        assertNull(coordinator.state.value.errorCategory)
    }
}
