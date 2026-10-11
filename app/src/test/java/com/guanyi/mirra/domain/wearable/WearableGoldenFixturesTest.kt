package com.guanyi.mirra.domain.wearable

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WearableGoldenFixturesTest {
    private val names = listOf("hello", "hello_ack", "get_snapshot", "idle", "preparing", "active", "break", "unmonitored", "saving", "ended", "status_error")
    @Test fun `both runtimes share exact v1 golden wire examples`() {
        val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }
        names.forEach { name ->
            val bytes = checkNotNull(javaClass.getResourceAsStream("/wearable/protocol-v1/$name.json")).use { it.readBytes() }
            val tree = WearableProtocol.decodeObject(bytes)
            val type = tree.getValue("type").jsonPrimitive.content
            val request = WearableRequest(1, type, tree.getValue("clientInstanceId").jsonPrimitive.content,
                tree.getValue("requestId").jsonPrimitive.content, tree["streamId"]?.jsonPrimitive?.content)
            assertEquals(1, tree.getValue("protocolVersion").jsonPrimitive.int)
            when (type) {
                "HELLO", "GET_SNAPSHOT" -> assertEquals(type, WearableProtocol.decodeRequest(bytes).type)
                "HELLO_ACK" -> assertEquals(tree, WearableProtocol.decodeObject(WearableProtocol.helloAck(request, checkNotNull(request.streamId))))
                "STATUS_ERROR" -> assertEquals(tree, WearableProtocol.decodeObject(WearableProtocol.status(request,
                    tree.getValue("code").jsonPrimitive.content, request.streamId)))
                "SNAPSHOT" -> {
                    val body = json.decodeFromJsonElement<WearableSnapshot>(tree.getValue("body"))
                    WearableProtocol.validateBody(body)
                    val reply = WearableProtocol.snapshot(request, checkNotNull(request.streamId),
                        tree.getValue("sequence").jsonPrimitive.long, tree.getValue("sourceObservedAtUnixMs").jsonPrimitive.long, body)
                    assertEquals(name, tree, WearableProtocol.decodeObject(reply))
                }
                else -> fail("Unknown golden message")
            }
        }
    }
}
