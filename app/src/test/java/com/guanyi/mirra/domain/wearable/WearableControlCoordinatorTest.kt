package com.guanyi.mirra.domain.wearable

import com.guanyi.mirra.platform.wearable.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearableControlCoordinatorTest {
    private class Settings(controls: Boolean) : WearableSettingsStore {
        private var selected = false
        private var selectedControls = controls
        override val enabled get() = selected
        override val controlEnabled get() = selectedControls
        override fun setEnabled(value: Boolean) { selected = value }
        override fun setControlEnabled(value: Boolean) { selectedControls = value }
    }
    private class Transport : WearTransport {
        var callback: WearTransportListener? = null
        val replies = mutableListOf<JsonObject>()
        override suspend fun connect(requestAuthorization: Boolean) = WearConnection("node", 1, true)
        override suspend fun send(nodeId: String, payload: ByteArray) {
            assertEquals("node", nodeId)
            replies += WearableProtocol.decodeMessage(payload)
        }
        override fun setListener(listener: WearTransportListener?) { this.callback = listener }
        override suspend fun close() { callback = null }
        fun message(text: String, node: String = "node") { callback?.onMessage(node, text.toByteArray(Charsets.UTF_8)) }
    }
    private class Rig(scope: CoroutineScope, controls: Boolean = false) {
        val settings = Settings(controls)
        val transport = Transport()
        var body = WearableSnapshot(WearablePhase.ACTIVE, sessionId = "session", learningItemId = "book",
            currentPage = 1, totalPages = 300, bookTitle = "中文📖", segmentType = "FOCUS", coverage = "FULL",
            monitorAvailability = MonitorAvailability.AVAILABLE, elapsedMs = 100, timerKind = WearableTimerKind.SESSION_ELAPSED,
            activeSegmentId = "segment-one")
        var businessEffects = 0
        var now = 1_000L
        var nextId = 0
        var executeHook: (suspend (WearableRequest, () -> Boolean) -> WearableCommandOutcome)? = null
        val executor = object : WearableCommandExecutor {
            override fun allowedActions(snapshot: WearableSnapshot) = when (snapshot.segmentType) {
                "FOCUS" -> listOf("START_BREAK")
                "BREAK" -> listOf("FINISH_BREAK")
                else -> emptyList()
            }
            override suspend fun execute(request: WearableRequest, stillAuthorized: () -> Boolean): WearableCommandOutcome {
                executeHook?.let { return it(request, stillAuthorized) }
                if (!stillAuthorized()) return WearableCommandOutcome("DISABLED")
                businessEffects++
                body = body.copy(segmentType = if (request.action == "START_BREAK") "BREAK" else "RECOVERY",
                    activeSegmentId = "segment-${businessEffects + 1}")
                return WearableCommandOutcome("APPLIED", "session")
            }
        }
        val coordinator = WearableCoordinator(scope, transport, settings, { now }, { "id-${++nextId}" })
        suspend fun start() { coordinator.bindSource(WearableSnapshotSource { body }, executor); coordinator.enableFromUser() }
        fun hello(version: Int = 2, controls: Boolean = true, id: String = "hello") {
            val capabilities = if (version == 2 && controls) "[\"READ_ONLY_SNAPSHOT\",\"SESSION_CONTROL\"]" else "[\"READ_ONLY_SNAPSHOT\"]"
            transport.message("""{"protocolVersion":$version,"type":"HELLO","clientInstanceId":"client","requestId":"$id","capabilities":$capabilities}""")
        }
        fun stream() = transport.replies.last { it.getValue("type").jsonPrimitive.content == "HELLO_ACK" }.getValue("streamId").jsonPrimitive.content
        fun get(id: String = "snapshot", version: Int = 2) = transport.message("""{"protocolVersion":$version,"type":"GET_SNAPSHOT","clientInstanceId":"client","requestId":"$id","streamId":"${stream()}","lastSeenSessionId":null}""")
        fun snapshot() = transport.replies.last { it.getValue("type").jsonPrimitive.content == "SNAPSHOT" }
        fun command(id: String = "command", action: String = "START_BREAK", minutes: Int? = 5): String {
            val control = snapshot().getValue("control").jsonObject
            val grant = control.getValue("controlGrant").let { if (it == JsonNull) "absent-grant" else it.jsonPrimitive.content }
            return buildJsonObject {
                put("protocolVersion", 2); put("type", "COMMAND"); put("clientInstanceId", "client"); put("requestId", id)
                put("streamId", stream()); put("controlGrant", grant); put("action", action); put("expectedIntentId", JsonNull)
                put("expectedSessionId", "session"); put("expectedSegmentId", control.getValue("activeSegmentId"))
                put("expectedBookId", JsonNull); put("expectedPage", JsonNull); put("expectedFirstAction", JsonNull)
                put("breakMinutes", minutes?.let(::JsonPrimitive) ?: JsonNull)
            }.toString()
        }
        fun result() = transport.replies.last { it.getValue("type").jsonPrimitive.content == "COMMAND_RESULT" }
        fun resultCode() = result().getValue("code").jsonPrimitive.content
    }
    private suspend fun TestScope.ready(controls: Boolean = false, version: Int = 2, negotiate: Boolean = true): Rig {
        val rig = Rig(backgroundScope, controls)
        rig.start(); runCurrent(); rig.hello(version, negotiate); runCurrent(); rig.get(version = version); runCurrent()
        return rig
    }
    @Test fun `v1 companion remains read only even when phone controls are explicitly enabled`() = runTest {
        val rig = ready(controls = true, version = 1)
        val snapshot = rig.snapshot()
        assertFalse("control" in snapshot)
        assertEquals("FOCUS", snapshot.getValue("body").jsonObject.getValue("segmentType").jsonPrimitive.content)
        assertFalse(rig.coordinator.state.value.controlNegotiated)
        rig.transport.message("""{"protocolVersion":1,"type":"COMMAND","clientInstanceId":"client","requestId":"write","streamId":"${rig.stream()}","controlGrant":"fake","action":"START_BREAK"}""")
        runCurrent(); assertEquals(0, rig.businessEffects)
    }
    @Test fun `v2 default off or absent control negotiation yields no usable grant`() = runTest {
        for ((enabled, negotiated) in listOf(false to true, true to false)) {
            val rig = ready(controls = enabled, negotiate = negotiated)
            val control = rig.snapshot().getValue("control").jsonObject
            assertEquals(JsonNull, control.getValue("controlGrant"))
            assertTrue(control.getValue("allowedActions").jsonArray.isEmpty())
            rig.transport.message(rig.command()); runCurrent()
            assertEquals("DISABLED", rig.resultCode()); assertEquals(0, rig.businessEffects)
        }
    }
    @Test fun `legal command applies once and duplicate result is proven without replaying business`() = runTest {
        val rig = ready(controls = true)
        val command = rig.command()
        rig.transport.message(command); runCurrent()
        assertEquals("APPLIED", rig.resultCode()); assertEquals("BREAK", rig.body.segmentType)
        rig.transport.message(command); runCurrent()
        assertEquals("ALREADY_APPLIED", rig.resultCode()); assertEquals(1, rig.businessEffects)
        rig.get("after-break"); runCurrent()
        val body = rig.snapshot().getValue("body").jsonObject
        assertEquals("BREAK", body.getValue("segmentType").jsonPrimitive.content)
        rig.transport.message(rig.command("finish", "FINISH_BREAK", null)); runCurrent()
        assertEquals("APPLIED", rig.resultCode()); assertEquals("RECOVERY", rig.body.segmentType)
        rig.get("after-finish"); runCurrent()
        assertEquals("RECOVERY", rig.snapshot().getValue("body").jsonObject.getValue("segmentType").jsonPrimitive.content)
        assertEquals(2, rig.businessEffects)
    }
    @Test fun `request id reuse with altered payload cannot erase the original proven result`() = runTest {
        val rig = ready(controls = true); val original = rig.command()
        rig.transport.message(original); runCurrent(); assertEquals("APPLIED", rig.resultCode())
        rig.transport.message(original.replace("\"breakMinutes\":5", "\"breakMinutes\":10")); runCurrent()
        assertEquals("STALE_STATE", rig.resultCode())
        rig.transport.message(original); runCurrent()
        assertEquals("ALREADY_APPLIED", rig.resultCode()); assertEquals(1, rig.businessEffects)
    }
    @Test fun `forged grant stale segment expiry and wrong node never reach the business owner`() = runTest {
        for (bad in listOf("grant", "segment", "expiry", "node", "client", "stream")) {
            val rig = ready(controls = true); val original = rig.command()
            val command = when (bad) {
                "grant" -> original.replace(Regex("\"controlGrant\":\"[^\"]+\""), "\"controlGrant\":\"forged\"")
                "segment" -> original.replace("segment-one", "old-segment")
                "client" -> original.replace("\"clientInstanceId\":\"client\"", "\"clientInstanceId\":\"old-client\"")
                "stream" -> original.replace(Regex("\"streamId\":\"[^\"]+\""), "\"streamId\":\"old-stream\"")
                else -> original
            }
            if (bad == "expiry") rig.now = 21_000
            rig.transport.message(command, if (bad == "node") "foreign" else "node"); runCurrent()
            assertEquals(bad, 0, rig.businessEffects); assertEquals("FOCUS", rig.body.segmentType)
            if (bad in listOf("grant", "segment", "expiry")) assertEquals("STALE_STATE", rig.resultCode())
        }
    }
    @Test fun `turning controls off revokes commands while fresh read only snapshots continue`() = runTest {
        val rig = ready(controls = true); val command = rig.command()
        rig.coordinator.setControlsFromUser(false)
        rig.transport.message(command); runCurrent()
        assertEquals("DISABLED", rig.resultCode()); assertEquals(0, rig.businessEffects)
        rig.get("read-only"); runCurrent()
        assertEquals(JsonNull, rig.snapshot().getValue("control").jsonObject.getValue("controlGrant"))
        assertTrue(rig.coordinator.state.value.handshaken)
    }
    @Test fun `new handshake and storage owner cannot accept an old stream command`() = runTest {
        for (replaceOwner in listOf(false, true)) {
            val rig = ready(controls = true); val command = rig.command()
            val oldListener = checkNotNull(rig.transport.callback)
            if (replaceOwner) {
                rig.coordinator.pauseSource()
                rig.coordinator.bindSource(WearableSnapshotSource { rig.body }, object : WearableCommandExecutor {
                    override fun allowedActions(snapshot: WearableSnapshot) = listOf("START_BREAK")
                    override suspend fun execute(request: WearableRequest, stillAuthorized: () -> Boolean): WearableCommandOutcome = error("retired stream reached new owner")
                }); runCurrent()
            }
            rig.hello(id = "new-hello"); runCurrent()
            oldListener.onMessage("node", command.toByteArray()); rig.transport.message(command); runCurrent()
            assertEquals(0, rig.businessEffects); assertEquals("FOCUS", rig.body.segmentType)
        }
    }
    @Test fun `revocation while awaiting business readiness rejects the final admitted write`() = runTest {
        val rig = ready(controls = true); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        rig.executeHook = { _, authorized ->
            started.complete(Unit); release.await()
            if (!authorized()) WearableCommandOutcome("DISABLED")
            else { rig.businessEffects++; WearableCommandOutcome("APPLIED", "session") }
        }
        rig.transport.message(rig.command()); runCurrent(); assertTrue(started.isCompleted)
        rig.coordinator.setControlsFromUser(false); release.complete(Unit); runCurrent()
        assertEquals(0, rig.businessEffects)
        assertFalse(rig.transport.replies.any { it["type"]?.jsonPrimitive?.content == "COMMAND_RESULT" && it["code"]?.jsonPrimitive?.content == "APPLIED" })
        rig.get("revoked-read-only"); runCurrent()
        assertEquals("FOCUS", rig.snapshot().getValue("body").jsonObject.getValue("segmentType").jsonPrimitive.content)
        assertEquals(JsonNull, rig.snapshot().getValue("control").jsonObject.getValue("controlGrant"))
        rig.transport.message(rig.command("new-disabled")); runCurrent()
        assertEquals("DISABLED", rig.resultCode()); assertEquals(0, rig.businessEffects)
    }
    @Test fun `synchronous disconnect revokes an in flight write before async lifecycle drain`() = runTest {
        val rig = ready(controls = true)
        rig.executeHook = { _, authorized ->
            checkNotNull(rig.transport.callback).onDisconnected()
            if (!authorized()) WearableCommandOutcome("DISABLED")
            else { rig.businessEffects++; WearableCommandOutcome("APPLIED", "session") }
        }
        rig.transport.message(rig.command()); runCurrent()
        assertEquals(0, rig.businessEffects); assertEquals("FOCUS", rig.body.segmentType)
        assertFalse(rig.transport.replies.any { it["type"]?.jsonPrimitive?.content == "COMMAND_RESULT" && it["code"]?.jsonPrimitive?.content == "APPLIED" })
    }
    @Test fun `grant expiring while business readiness waits cannot pass final admission`() = runTest {
        val rig = ready(controls = true); val release = CompletableDeferred<Unit>()
        rig.executeHook = { _, authorized ->
            release.await()
            if (!authorized()) WearableCommandOutcome("STALE_STATE")
            else { rig.businessEffects++; WearableCommandOutcome("APPLIED", "session") }
        }
        rig.transport.message(rig.command()); runCurrent()
        rig.now = 21_000; release.complete(Unit); runCurrent()
        assertEquals(0, rig.businessEffects); assertEquals("STALE_STATE", rig.resultCode())
    }
    @Test fun `one in flight command has no detached queue and timeout never retransmits a committed effect`() = runTest {
        val rig = ready(controls = true); val hold = CompletableDeferred<Unit>()
        rig.executeHook = { _, authorized ->
            check(authorized()); rig.businessEffects++; rig.body = rig.body.copy(segmentType = "BREAK", activeSegmentId = "segment-two")
            hold.await(); WearableCommandOutcome("APPLIED", "session")
        }
        val command = rig.command()
        rig.transport.message(command); rig.transport.message(command.replace("\"requestId\":\"command\"", "\"requestId\":\"another\"")); runCurrent()
        assertEquals(1, rig.businessEffects)
        advanceTimeBy(12_001); runCurrent()
        assertEquals("RESULT_UNKNOWN", rig.resultCode())
        rig.transport.message(command); runCurrent()
        assertEquals("RESULT_UNKNOWN", rig.resultCode()); assertEquals(1, rig.businessEffects)
        rig.get("full-state"); runCurrent()
        assertEquals("BREAK", rig.snapshot().getValue("body").jsonObject.getValue("segmentType").jsonPrimitive.content)
    }
    @Test fun `maintenance and disabling discard old callback and grant without business writes`() = runTest {
        for (retire in listOf("pause", "disable", "close")) {
            val rig = ready(controls = true); val command = rig.command(); val listener = checkNotNull(rig.transport.callback)
            when (retire) {
                "pause" -> rig.coordinator.pauseSource()
                "disable" -> rig.coordinator.disableFromUser()
                "close" -> rig.coordinator.close()
            }
            runCurrent(); listener.onMessage("node", command.toByteArray()); rig.transport.message(command); runCurrent()
            assertEquals(retire, 0, rig.businessEffects); assertEquals("FOCUS", rig.body.segmentType)
        }
    }
    @Test fun `disabling a v2 companion echoes the negotiated version without private snapshot data`() = runTest {
        val rig = ready(controls = true)
        val stream = rig.stream()
        rig.coordinator.disableFromUser(); runCurrent()
        val disabled = rig.transport.replies.last()
        assertEquals("STATUS_ERROR", disabled.getValue("type").jsonPrimitive.content)
        assertEquals(2, disabled.getValue("protocolVersion").jsonPrimitive.int)
        assertEquals("client", disabled.getValue("clientInstanceId").jsonPrimitive.content)
        assertEquals(stream, disabled.getValue("streamId").jsonPrimitive.content)
        assertEquals("DISABLED", disabled.getValue("code").jsonPrimitive.content)
        assertEquals(setOf("protocolVersion", "type", "clientInstanceId", "requestId", "streamId", "code"), disabled.keys)
        assertFalse(disabled.toString().contains("中文"))
        assertFalse(rig.coordinator.state.value.enabled)
    }
}
