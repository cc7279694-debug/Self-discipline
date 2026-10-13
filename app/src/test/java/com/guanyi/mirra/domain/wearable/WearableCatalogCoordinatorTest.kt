package com.guanyi.mirra.domain.wearable

import com.guanyi.mirra.platform.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearableCatalogCoordinatorTest {
    private class Settings : WearableSettingsStore {
        private var selected = true
        private var catalogConsent = false
        private var ids = emptyList<String>()
        override val enabled get() = selected
        override val catalogEnabled get() = catalogConsent
        override val catalogSelectedIds get() = ids
        override fun setEnabled(value: Boolean) { selected = value }
        override fun setCatalogEnabled(value: Boolean) { catalogConsent = value }
        override fun setCatalogSelectedIds(value: List<String>) { ids = value }
    }
    private class Transport : WearTransport {
        @Volatile var callback: WearTransportListener? = null
        val sent = CopyOnWriteArrayList<JsonObject>()
        var afterSend: ((JsonObject) -> Unit)? = null
        var sendsBlocked: CompletableDeferred<Unit>? = null
        override suspend fun connect(requestAuthorization: Boolean) = WearConnection("node", 1, true)
        override suspend fun send(nodeId: String, payload: ByteArray) {
            val value = WearableProtocol.decodeMessage(payload)
            sent += value
            afterSend?.invoke(value)
            sendsBlocked?.await()
        }
        override fun setListener(listener: WearTransportListener?) { callback = listener }
        override suspend fun close() { callback = null }
        fun message(value: JsonObject, node: String = "node") { callback?.onMessage(node, value.toString().toByteArray()) }
    }
    private class Rig(scope: CoroutineScope, monotonic: () -> Long = { System.nanoTime() / 1_000_000 }) {
        val settings = Settings()
        val transport = Transport()
        var sourceReads = 0
        var readBarrier: CompletableDeferred<Unit>? = null
        var failRead = false
        val catalog = WearableBookCatalog(books = listOf(WearableCatalogBook("book", "中文书", 100, 42, "读第一段。", true)))
        val reader = object : WearableCatalogSource {
            override suspend fun read(selectedIds: List<String>): WearableBookCatalog {
                sourceReads++; readBarrier?.await()
                if (failRead) throw WearableSourceBusy()
                return catalog
            }
            override suspend fun choices() = listOf(WearableCatalogChoice("book", "主线", true), WearableCatalogChoice("other", "其他", false))
        }
        var nextId = 0
        val coordinator = WearableCoordinator(scope, transport, settings, { 1_000 }, { "id-${++nextId}" }, monotonic)
        suspend fun bind() = coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) }, catalog = reader)
        fun hello(version: Int = 3, id: String = "hello-$version") = transport.message(buildJsonObject {
            put("protocolVersion", version); put("type", "HELLO"); put("clientInstanceId", "client"); put("requestId", id)
            put("capabilities", buildJsonArray { add("READ_ONLY_SNAPSHOT"); if (version == 3) add("BOOK_CATALOG") })
        })
        fun stream() = transport.sent.last { it.getValue("type").jsonPrimitive.content == "HELLO_ACK" }.getValue("streamId").jsonPrimitive.content
        fun get(id: String = "get", known: String? = null) = transport.message(buildJsonObject {
            put("protocolVersion", 3); put("type", "GET_CATALOG"); put("clientInstanceId", "client"); put("requestId", id)
            put("streamId", stream()); put("knownRevision", known?.let(::JsonPrimitive) ?: JsonNull)
        })
        fun ack(request: String = "get", revision: String = WearableCatalogProtocol.encode(catalog).revision,
            stream: String = stream(), client: String = "client", node: String = "node") = transport.message(buildJsonObject {
            put("protocolVersion", 3); put("type", "CATALOG_ACK"); put("clientInstanceId", client); put("requestId", request)
            put("streamId", stream); put("revision", revision); put("code", "PERSISTED")
        }, node)
    }
    private suspend fun TestScope.ready(enabled: Boolean = true, monotonic: (() -> Long)? = null): Rig {
        val rig = Rig(backgroundScope, monotonic ?: { testScheduler.currentTime }); rig.bind(); runCurrent(); rig.hello(); runCurrent()
        if (enabled) rig.coordinator.setCatalogEnabledFromUser(true)
        return rig
    }
    @Test fun `old consent never silently authorizes expanded book payload`() = runTest {
        val rig = ready(false); rig.get(); runCurrent()
        assertEquals(0, rig.sourceReads)
        assertEquals("CATALOG_DISABLED", rig.transport.sent.last().getValue("code").jsonPrimitive.content)
        assertNull(rig.coordinator.state.value.lastCatalogAt)
    }
    @Test fun `SDK send is not durable sync and exact matching ACK alone confirms`() = runTest {
        val rig = ready(); rig.get(); runCurrent()
        assertEquals("AWAITING_ACK", rig.coordinator.state.value.catalogStatus)
        assertNull(rig.coordinator.state.value.lastCatalogAt)
        rig.ack(revision = "catalog-00000000-1"); rig.ack(request = "old"); rig.ack(client = "other"); rig.ack(node = "other"); runCurrent()
        assertNull(rig.coordinator.state.value.lastCatalogAt)
        rig.ack(); runCurrent()
        assertEquals("SYNCED", rig.coordinator.state.value.catalogStatus)
        assertEquals(1_000L, rig.coordinator.state.value.lastCatalogAt)
    }
    @Test fun `ACK during final native send callback is accepted without deadlock or lost receipt`() = runTest {
        val rig = ready()
        rig.transport.afterSend = { frame -> if (frame.getValue("type").jsonPrimitive.content == "CATALOG_PART") rig.ack() }
        rig.get(); runCurrent()
        assertEquals("SYNCED", rig.coordinator.state.value.catalogStatus)
        assertNotNull(rig.coordinator.state.value.lastCatalogAt)
    }
    @Test fun `early ACK before final part is issued cannot claim a complete transfer`() = runTest {
        val rig = ready()
        rig.transport.afterSend = { frame -> if (frame.getValue("type").jsonPrimitive.content == "CATALOG_MANIFEST") rig.ack() }
        rig.get(); runCurrent()
        assertEquals("AWAITING_ACK", rig.coordinator.state.value.catalogStatus)
        assertNull(rig.coordinator.state.value.lastCatalogAt)
        rig.ack(); runCurrent(); assertEquals("SYNCED", rig.coordinator.state.value.catalogStatus)
    }
    @Test fun `known revision still sends all frozen bytes not checksum only success`() = runTest {
        val rig = ready(); rig.get(known = WearableCatalogProtocol.encode(rig.catalog).revision); runCurrent()
        assertEquals(1, rig.sourceReads)
        assertEquals(1, rig.transport.sent.count { it.getValue("type").jsonPrimitive.content == "CATALOG_PART" })
        assertNull(rig.coordinator.state.value.lastCatalogAt)
    }
    @Test fun `missing durable ACK times out and late ACK never changes unknown to synced`() = runTest {
        val rig = ready(); rig.get(); runCurrent(); advanceTimeBy(30_001); runCurrent()
        assertEquals("TIMEOUT", rig.coordinator.state.value.catalogError)
        rig.ack(); runCurrent(); assertNull(rig.coordinator.state.value.lastCatalogAt)
    }
    @Test fun `slow source read cannot extend fixed total thirty second catalog deadline`() = runTest {
        val rig = ready(); rig.readBarrier = CompletableDeferred(); rig.get(); runCurrent()
        advanceTimeBy(25_000); rig.readBarrier!!.complete(Unit); runCurrent()
        assertEquals("AWAITING_ACK", rig.coordinator.state.value.catalogStatus)
        advanceTimeBy(15_000); runCurrent(); rig.ack(); runCurrent()
        assertEquals("TIMEOUT", rig.coordinator.state.value.catalogError)
        assertNull(rig.coordinator.state.value.lastCatalogAt)
        assertEquals("ERROR", rig.coordinator.state.value.catalogStatus)
    }
    @Test fun `source timeout is explicitly reported as catalog timeout and cannot confirm`() = runTest {
        val rig = ready(); rig.readBarrier = CompletableDeferred(); rig.get(); runCurrent()
        advanceTimeBy(30_001); runCurrent()
        assertEquals("TIMEOUT", rig.coordinator.state.value.catalogError)
        assertNull(rig.coordinator.state.value.lastCatalogAt)
        rig.readBarrier!!.complete(Unit); rig.ack(); runCurrent()
        assertNull(rig.coordinator.state.value.lastCatalogAt)
    }
    @Test fun `expired receipt is rejected even when coroutine timeout timer has not run`() = runTest {
        var elapsed = 0L
        val rig = ready(monotonic = { elapsed }); rig.get(); runCurrent()
        elapsed = 30_001
        rig.ack(); runCurrent()
        assertNull(rig.coordinator.state.value.lastCatalogAt)
        assertEquals("TIMEOUT", rig.coordinator.state.value.catalogError)
    }
    @Test fun `backward process clock cannot grant a late catalog receipt`() = runTest {
        var elapsed = 100L
        val rig = ready(monotonic = { elapsed }); rig.get(); runCurrent()
        elapsed = 99
        rig.ack(); runCurrent()
        assertNull(rig.coordinator.state.value.lastCatalogAt)
        assertEquals("TIMEOUT", rig.coordinator.state.value.catalogError)
    }
    @Test fun `concurrent final-send receipts cannot regress confirmed sync into waiting`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val receipts = Executors.newSingleThreadExecutor()
        val issued = AtomicInteger()
        val rig = Rig(owner)
        try {
            rig.bind()
            withTimeout(5_000) { while (rig.transport.callback == null) delay(1) }
            rig.hello()
            withTimeout(5_000) { rig.coordinator.state.first { it.handshaken } }
            rig.coordinator.setCatalogEnabledFromUser(true)
            rig.transport.afterSend = { frame ->
                if (frame.getValue("type").jsonPrimitive.content == "CATALOG_PART") {
                    val requestId = frame.getValue("requestId").jsonPrimitive.content
                    receipts.execute { rig.ack(request = requestId); issued.incrementAndGet() }
                    Thread.yield()
                }
            }
            repeat(100) { index ->
                rig.get("concurrent-$index")
                withTimeout(5_000) {
                    while (issued.get() <= index || rig.coordinator.state.value.catalogStatus != "SYNCED") delay(1)
                }
                delay(2)
                assertEquals("SYNCED", rig.coordinator.state.value.catalogStatus)
                assertNotNull(rig.coordinator.state.value.lastCatalogAt)
            }
        } finally { rig.coordinator.close(); owner.cancel(); receipts.shutdownNow() }
    }
    @Test fun `catalog consent revoked during read never leaks captured book payload`() = runTest {
        val rig = ready(); rig.readBarrier = CompletableDeferred(); rig.get(); runCurrent()
        rig.coordinator.setCatalogEnabledFromUser(false); rig.readBarrier!!.complete(Unit); runCurrent()
        assertFalse(rig.transport.sent.any { it.getValue("type").jsonPrimitive.content == "CATALOG_MANIFEST" })
        assertNull(rig.coordinator.state.value.lastCatalogAt)
    }
    @Test fun `maintenance retires captured catalog reader and its pending receipt`() = runTest {
        val rig = ready(); rig.get(); runCurrent(); rig.coordinator.pauseSource(); runCurrent()
        rig.ack(); runCurrent()
        assertNull(rig.coordinator.state.value.lastCatalogAt)
        assertFalse(rig.coordinator.state.value.catalogNegotiated)
    }
    @Test fun `disabled companion and stale hello receipt do not revive old catalog success`() = runTest {
        val rig = ready(); rig.get(); runCurrent(); val old = rig.stream()
        rig.hello(id = "hello-new"); runCurrent(); rig.ack(stream = old); runCurrent()
        assertNull(rig.coordinator.state.value.lastCatalogAt)
        rig.coordinator.disableFromUser(); runCurrent(); rig.ack(stream = old); runCurrent()
        assertFalse(rig.coordinator.state.value.enabled)
        assertNull(rig.coordinator.state.value.lastCatalogAt)
    }
    @Test fun `source failure does not produce catalog manifest or a saved timestamp`() = runTest {
        val rig = ready(); rig.failRead = true; rig.get(); runCurrent()
        assertEquals("SOURCE_BUSY", rig.transport.sent.last().getValue("code").jsonPrimitive.content)
        assertNull(rig.coordinator.state.value.lastCatalogAt)
        assertFalse(rig.transport.sent.any { it.getValue("type").jsonPrimitive.content == "CATALOG_MANIFEST" })
    }
    @Test fun `only explicitly selected eligible extras persist with automatic mainline`() = runTest {
        val rig = ready(); rig.coordinator.loadCatalogChoicesFromUser(); runCurrent()
        rig.coordinator.setCatalogBooksFromUser(listOf("book"))
        assertEquals("INVALID_CATALOG", rig.coordinator.state.value.catalogError)
        rig.coordinator.setCatalogBooksFromUser(listOf("other"))
        assertEquals(listOf("other"), rig.settings.catalogSelectedIds)
        rig.get(); runCurrent(); rig.coordinator.setCatalogBooksFromUser(emptyList()); rig.ack(); runCurrent()
        assertNull(rig.coordinator.state.value.lastCatalogAt)
    }
    @Test fun `old v1 handshake retains exact readonly snapshot behavior`() = runTest {
        val rig = Rig(backgroundScope); rig.bind(); runCurrent(); rig.hello(1); runCurrent()
        assertFalse(rig.coordinator.state.value.catalogNegotiated)
        rig.transport.message(buildJsonObject {
            put("protocolVersion", 1); put("type", "GET_SNAPSHOT"); put("clientInstanceId", "client"); put("requestId", "snapshot")
            put("streamId", rig.stream()); put("lastSeenSessionId", JsonNull)
        }); runCurrent()
        assertEquals("SNAPSHOT", rig.transport.sent.last().getValue("type").jsonPrimitive.content)
        assertFalse("control" in rig.transport.sent.last())
    }
}
