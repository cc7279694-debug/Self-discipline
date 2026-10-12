package com.guanyi.mirra.domain.wearable

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WearableProtocolV2GoldenFixturesTest {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }
    private val names = listOf("hello", "hello_ack", "get_snapshot", "idle", "preparing", "active", "break", "unmonitored", "saving", "ended", "status_error", "start_break", "finish_break", "confirm_start", "command_applied", "command_degraded", "command_unknown")
    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/wearable/protocol-v2/$name.json")).use { it.readBytes() }
    @Test fun `all v2 shared literal fixtures validate and replies reproduce exact fields`() {
        names.forEach { name ->
            val tree = WearableProtocol.decodeMessage(fixture(name))
            val type = tree.getValue("type").jsonPrimitive.content
            assertEquals(2, tree.getValue("protocolVersion").jsonPrimitive.int)
            if (type in setOf("HELLO", "GET_SNAPSHOT", "COMMAND")) {
                val request = WearableProtocol.decodeRequest(fixture(name))
                assertEquals(type, request.type)
                if (type == "COMMAND") assertEquals(tree.getValue("action").jsonPrimitive.content, request.action)
                return@forEach
            }
            val stream = tree["streamId"]?.jsonPrimitive?.content
            val request = WearableRequest(2, type, tree.getValue("clientInstanceId").jsonPrimitive.content,
                tree.getValue("requestId").jsonPrimitive.content, stream,
                capabilities = tree["capabilities"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("READ_ONLY_SNAPSHOT"),
                action = tree["action"]?.jsonPrimitive?.content)
            val reply = when (type) {
                "HELLO_ACK" -> WearableProtocol.helloAck(request, checkNotNull(stream))
                "STATUS_ERROR" -> WearableProtocol.status(request, tree.getValue("code").jsonPrimitive.content, stream)
                "SNAPSHOT" -> WearableProtocol.snapshot(request, checkNotNull(stream), tree.getValue("sequence").jsonPrimitive.long,
                    tree.getValue("sourceObservedAtUnixMs").jsonPrimitive.long,
                    json.decodeFromJsonElement<WearableSnapshot>(tree.getValue("body")),
                    json.decodeFromJsonElement<WearableControlState>(tree.getValue("control")))
                "COMMAND_RESULT" -> WearableProtocol.commandResult(request.copy(type = "COMMAND"), checkNotNull(stream),
                    WearableCommandOutcome(tree.getValue("code").jsonPrimitive.content,
                        tree.getValue("sessionId").let { if (it == JsonNull) null else it.jsonPrimitive.content },
                        tree.getValue("monitorDegraded").jsonPrimitive.boolean))
                else -> error("unknown golden message")
            }
            assertEquals(name, tree, WearableProtocol.decodeMessage(reply))
        }
    }
    @Test fun `v2 grant rejects altered first action truncated action expired grant and closed phase`() {
        val preparation = String(fixture("preparing"), Charsets.UTF_8)
        val invalid = listOf(preparation.replace("\"grantExpiresAtUnixMs\": 1791676830000", "\"grantExpiresAtUnixMs\": 1791676800000"),
            preparation.replace("\"firstActionTruncated\": false", "\"firstActionTruncated\": true"),
            preparation.replace("\"expectedFirstAction\": \"翻到第43页，读第一段📖\"", "\"expectedFirstAction\": \"另一动作\""),
            preparation.replace("\"activeSegmentId\": null", "\"activeSegmentId\": \"segment-old\""),
            preparation.replace("\"grantExpiresAtUnixMs\": 1791676830000", "\"grantExpiresAtUnixMs\": 1791676830001"))
        invalid.forEach { assertThrows(WearableProtocolException::class.java) { WearableProtocol.decodeMessage(it.toByteArray()) } }
    }
    @Test fun `v2 reply fields are required and unknown or duplicate fields cannot be defaulted away`() {
        val snapshot = String(fixture("idle"), Charsets.UTF_8)
        listOf(snapshot.replace("    \"allowedActions\": [],\n", ""),
            snapshot.replace("    \"intentId\": null,\n", ""),
            snapshot.replace("\"protocolVersion\": 2", "\"protocolVersion\": 2,\"protocolVersion\":1"),
            snapshot.dropLast(2) + ",\"sql\":\"delete\"}\n").forEach {
            assertThrows(WearableProtocolException::class.java) { WearableProtocol.decodeMessage(it.toByteArray()) }
        }
    }
    @Test fun `unproven or failed results never claim committed monitoring downgrade`() {
        val degraded = String(fixture("command_degraded"), Charsets.UTF_8)
        listOf(degraded.replace("\"code\": \"APPLIED\"", "\"code\": \"FAILED\""),
            degraded.replace("\"sessionId\": \"session-golden\"", "\"sessionId\": null"),
            degraded.replace("CONFIRM_PREPARATION_AND_START", "FINISH_BREAK")).forEach {
            assertThrows(WearableProtocolException::class.java) { WearableProtocol.decodeMessage(it.toByteArray()) }
        }
    }
    @Test fun `v2 typed fields reject primitive coercion despite serializer defaults`() {
        val snapshot = String(fixture("active"), Charsets.UTF_8)
        listOf(snapshot.replace("\"learningItemId\": \"book-golden\"", "\"learningItemId\": 123"),
            snapshot.replace("\"currentPage\": 42", "\"currentPage\": \"42\""),
            snapshot.replace("\"bookTitleTruncated\": false", "\"bookTitleTruncated\": \"false\""),
            snapshot.replace("\"controlGrant\": \"grant-golden\"", "\"controlGrant\": 123"),
            snapshot.replace("\"grantExpiresAtUnixMs\": 1791676830000", "\"grantExpiresAtUnixMs\": \"1791676830000\"")).forEach {
            assertThrows(WearableProtocolException::class.java) { WearableProtocol.decodeMessage(it.toByteArray()) }
        }
    }
}
