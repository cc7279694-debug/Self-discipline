package com.guanyi.mirra.domain.wearable

import com.guanyi.mirra.platform.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearableRecordCoordinatorTest {
    private class Settings : WearableSettingsStore {
        override val enabled = true
        var consent = false
        override val recordSyncEnabled get() = consent
        override fun setRecordSyncEnabled(value: Boolean) { consent = value }
        override fun setEnabled(value: Boolean) { check(value) }
    }
    private class Transport : WearTransport {
        var callback: WearTransportListener? = null
        val sent = mutableListOf<JsonObject>()
        var afterSend: ((JsonObject) -> Unit)? = null
        override suspend fun connect(requestAuthorization: Boolean) = WearConnection("node", 1, true)
        override suspend fun send(nodeId: String, payload: ByteArray) {
            val message = WearableProtocol.decodeMessage(payload); sent += message; afterSend?.invoke(message)
        }
        override fun setListener(listener: WearTransportListener?) { this.callback = listener }
        override suspend fun close() { callback = null }
        fun receive(frame: WearableRecordFrame, node: String = "node") { callback?.onMessage(node, WearableRecordProtocol.encode(frame)) }
    }
    private class Receiver : WearableRecordReceiver {
        var writes = 0; var offers = 0; var existing = false
        var barrier: CompletableDeferred<Unit>? = null
        override suspend fun offer(installationId: String, sessionId: String, digest: String, bytes: Int): WearableStoredRecord? {
            offers++; return if (existing) WearableStoredRecord(installationId, sessionId, digest, bytes) else null
        }
        override suspend fun persist(canonical: String, digest: String): WearableRecordReceipt {
            writes++; barrier?.await(); val raw = WearableLocalRecord.parse(canonical, digest)
            return WearableRecordReceipt(raw.installationId, raw.sessionId, raw.digest, raw.bytes, existing)
        }
        override suspend fun count() = 0L
    }
    private inner class Rig(scope: CoroutineScope, monotonic: () -> Long) {
        val settings = Settings(); val transport = Transport(); val receiver = Receiver()
        var id = 0
        val coordinator = WearableCoordinator(scope, transport, settings, { 1000L }, { "stream-${++id}" }, monotonic)
        val golden = checkNotNull(javaClass.getResourceAsStream("/wearable/protocol-v4/record-v4.json"))
            .use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject }
        val raw = golden.getValue("canonical").jsonPrimitive.content
        val messages = golden.getValue("messages").jsonArray.map { it.jsonObject }
        fun original(type: String) = WearableRecordProtocol.decodeFrame(messages.first { it.getValue("type").jsonPrimitive.content == type })
        fun frame(type: String, request: String = "record-request") = original(type).copy(streamId = stream(), requestId = request)
        fun stream() = transport.sent.last { it.getValue("type").jsonPrimitive.content == "HELLO_ACK" }.getValue("streamId").jsonPrimitive.content
        fun hello() {
            val message = messages.first { it.getValue("type").jsonPrimitive.content == "HELLO" }
            transport.callback?.onMessage("node", message.toString().toByteArray())
        }
        suspend fun bind() = coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) }, records = receiver)
        fun send(type: String, request: String = "record-request") = transport.receive(frame(type, request))
        fun acknowledgements() = transport.sent.filter { it.getValue("type").jsonPrimitive.content == "LOCAL_RECORD_ACK" }
    }
    private suspend fun TestScope.ready(consent: Boolean = true): Rig {
        val rig = Rig(backgroundScope) { testScheduler.currentTime }
        rig.bind(); runCurrent()
        if (consent) rig.coordinator.setRecordSyncEnabledFromUser(true)
        rig.hello(); runCurrent(); return rig
    }
    @Test fun `record consent defaults off and SDK readiness cannot authorize payload`() = runTest {
        val rig = ready(false); assertFalse(rig.coordinator.state.value.recordNegotiated)
        rig.send("LOCAL_RECORD_OFFER"); runCurrent(); assertEquals(0, rig.receiver.offers); assertEquals(0, rig.receiver.writes)
        assertTrue(rig.acknowledgements().isEmpty())
    }
    @Test fun `offer always needs complete original bytes even when digest is already stored`() = runTest {
        val rig = ready(); rig.receiver.existing = true; rig.send("LOCAL_RECORD_OFFER"); runCurrent()
        assertEquals("LOCAL_RECORD_NEED", rig.transport.sent.last().getValue("type").jsonPrimitive.content)
        assertEquals(0, rig.receiver.writes); assertTrue(rig.acknowledgements().isEmpty())
        rig.send("LOCAL_RECORD_PART"); rig.send("LOCAL_RECORD_COMMIT"); runCurrent()
        assertEquals(1, rig.receiver.writes); assertEquals("ALREADY_IMPORTED", rig.acknowledgements().single().getValue("code").jsonPrimitive.content)
    }
    @Test fun `parts alone and missing parts cannot commit or acknowledge`() = runTest {
        val rig = ready(); rig.send("LOCAL_RECORD_OFFER"); runCurrent(); rig.send("LOCAL_RECORD_COMMIT"); runCurrent()
        assertEquals(0, rig.receiver.writes); assertTrue(rig.acknowledgements().isEmpty())
    }
    @Test fun `ACK waits returned DB receipt and exact context rejects late or foreign frames`() = runTest {
        val rig = ready(); rig.receiver.barrier = CompletableDeferred(); rig.send("LOCAL_RECORD_OFFER"); runCurrent()
        rig.transport.receive(rig.frame("LOCAL_RECORD_PART").copy(requestId = "old")); rig.send("LOCAL_RECORD_COMMIT"); runCurrent()
        assertEquals(0, rig.receiver.writes)
        rig.send("LOCAL_RECORD_OFFER", "next"); runCurrent(); rig.send("LOCAL_RECORD_PART", "next"); rig.send("LOCAL_RECORD_COMMIT", "next"); runCurrent()
        assertEquals(1, rig.receiver.writes); assertTrue(rig.acknowledgements().isEmpty())
        rig.receiver.barrier!!.complete(Unit); runCurrent(); assertEquals(1, rig.acknowledgements().size)
    }
    @Test fun `native callbacks during NEED send buffer bounded parts and commit without dropping or deadlock`() = runTest {
        val rig = ready(); rig.transport.afterSend = { message -> if (message.getValue("type").jsonPrimitive.content == "LOCAL_RECORD_NEED") {
            rig.send("LOCAL_RECORD_PART"); rig.send("LOCAL_RECORD_COMMIT")
        } }
        rig.send("LOCAL_RECORD_OFFER"); runCurrent(); assertEquals(1, rig.receiver.writes); assertEquals(1, rig.acknowledgements().size)
    }
    @Test fun `revoked consent and retired maintenance source suppress old successful receipts`() = runTest {
        val rig = ready(); rig.receiver.barrier = CompletableDeferred(); rig.send("LOCAL_RECORD_OFFER"); runCurrent()
        rig.send("LOCAL_RECORD_PART"); rig.send("LOCAL_RECORD_COMMIT"); runCurrent()
        rig.coordinator.setRecordSyncEnabledFromUser(false); rig.receiver.barrier!!.complete(Unit); runCurrent()
        assertTrue(rig.acknowledgements().isEmpty()); assertFalse(rig.coordinator.state.value.recordNegotiated)
        rig.coordinator.pauseSource(); runCurrent(); rig.send("LOCAL_RECORD_COMMIT"); runCurrent(); assertTrue(rig.acknowledgements().isEmpty())
    }
    @Test fun `fixed thirty second budget includes DB return and duplicate parts never extend it`() = runTest {
        val rig = ready(); rig.receiver.barrier = CompletableDeferred(); rig.send("LOCAL_RECORD_OFFER"); runCurrent()
        rig.send("LOCAL_RECORD_PART"); runCurrent(); advanceTimeBy(25_000); rig.send("LOCAL_RECORD_PART"); rig.send("LOCAL_RECORD_COMMIT"); runCurrent()
        advanceTimeBy(6_000); runCurrent(); rig.receiver.barrier!!.complete(Unit); runCurrent()
        assertTrue(rig.acknowledgements().isEmpty()); assertEquals("TIMEOUT", rig.coordinator.state.value.recordError)
    }
    @Test fun `new HELLO retires old transfer and same archived facts can be requested again`() = runTest {
        val rig = ready(); rig.send("LOCAL_RECORD_OFFER"); runCurrent(); val oldPart = rig.frame("LOCAL_RECORD_PART")
        val hello = rig.messages.first { it.getValue("type").jsonPrimitive.content == "HELLO" }
        rig.transport.callback?.onMessage("node", JsonObject(hello + ("requestId" to JsonPrimitive("hello-new"))).toString().toByteArray()); runCurrent()
        rig.transport.receive(oldPart); rig.send("LOCAL_RECORD_COMMIT"); runCurrent(); assertEquals(0, rig.receiver.writes)
        rig.send("LOCAL_RECORD_OFFER"); runCurrent(); rig.send("LOCAL_RECORD_PART"); rig.send("LOCAL_RECORD_COMMIT"); runCurrent()
        assertEquals(1, rig.acknowledgements().size)
    }
    @Test fun `maintenance retires imported confirmation before a different source dataset opens`() = runTest {
        val rig = ready(); rig.send("LOCAL_RECORD_OFFER"); runCurrent()
        rig.send("LOCAL_RECORD_PART"); rig.send("LOCAL_RECORD_COMMIT"); runCurrent()
        assertEquals("IMPORTED", rig.coordinator.state.value.recordStatus)
        assertNotNull(rig.coordinator.state.value.lastRecordAt)
        rig.coordinator.pauseSource(); runCurrent()
        assertEquals("IDLE", rig.coordinator.state.value.recordStatus)
        assertNull(rig.coordinator.state.value.lastRecordAt)
        assertNull(rig.coordinator.state.value.recordError)
        assertNull(rig.coordinator.state.value.importedRecordCount)
        rig.coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) }, records = Receiver()); runCurrent()
        assertEquals(0L, rig.coordinator.state.value.importedRecordCount)
        assertEquals("IDLE", rig.coordinator.state.value.recordStatus)
    }
    @Test fun `new HELLO clears the previous stream confirmation and reloads current source count`() = runTest {
        val rig = ready(); rig.send("LOCAL_RECORD_OFFER"); runCurrent()
        rig.send("LOCAL_RECORD_PART"); rig.send("LOCAL_RECORD_COMMIT"); runCurrent()
        assertEquals("IMPORTED", rig.coordinator.state.value.recordStatus)
        val hello = rig.messages.first { it.getValue("type").jsonPrimitive.content == "HELLO" }
        rig.transport.callback?.onMessage("node", JsonObject(hello + ("requestId" to JsonPrimitive("hello-after-import"))).toString().toByteArray()); runCurrent()
        assertEquals("IDLE", rig.coordinator.state.value.recordStatus)
        assertNull(rig.coordinator.state.value.lastRecordAt)
        assertNull(rig.coordinator.state.value.recordError)
        assertEquals(0L, rig.coordinator.state.value.importedRecordCount)
    }
}
