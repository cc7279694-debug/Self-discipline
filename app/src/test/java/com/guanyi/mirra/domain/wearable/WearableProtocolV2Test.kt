package com.guanyi.mirra.domain.wearable

import org.junit.Assert.*
import org.junit.Test

class WearableProtocolV2Test {
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)
    private val hello = """{"protocolVersion":2,"type":"HELLO","clientInstanceId":"client-two","requestId":"hello-two","capabilities":["READ_ONLY_SNAPSHOT","SESSION_CONTROL"]}"""
    private val command = """{"protocolVersion":2,"type":"COMMAND","clientInstanceId":"client-two","requestId":"command-one","streamId":"stream-two","controlGrant":"grant-one","action":"START_BREAK","expectedIntentId":null,"expectedSessionId":"session-one","expectedSegmentId":"segment-one","expectedBookId":null,"expectedPage":null,"expectedFirstAction":null,"breakMinutes":5}"""
    @Test fun `v2 hello negotiates control and ack preserves negotiated version`() {
        val request = WearableProtocol.decodeRequest(bytes(hello))
        assertEquals(2, request.protocolVersion)
        val ack = String(WearableProtocol.helloAck(request, "stream-two"), Charsets.UTF_8)
        assertTrue(ack.contains("\"protocolVersion\":2"))
        assertTrue(ack.contains("SESSION_CONTROL"))
    }
    @Test fun `v2 accepts exact break command and rejects unsafe durations identities and extras`() {
        assertEquals("COMMAND", WearableProtocol.decodeRequest(bytes(command)).type)
        listOf(command.replace("\"protocolVersion\":2", "\"protocolVersion\":1"),
            command.replace("\"breakMinutes\":5", "\"breakMinutes\":15"),
            command.replace("\"expectedSegmentId\":\"segment-one\"", "\"expectedSegmentId\":null"),
            command.dropLast(1) + ",\"sql\":\"delete\"}").forEach { payload ->
            assertThrows(WearableProtocolException::class.java) { WearableProtocol.decodeRequest(bytes(payload)) }
        }
    }
    @Test fun `v1 hello remains read only and control capability is forbidden`() {
        val v1 = hello.replace("\"protocolVersion\":2", "\"protocolVersion\":1").replace(",\"SESSION_CONTROL\"", "")
        val request = WearableProtocol.decodeRequest(bytes(v1))
        assertEquals(1, request.protocolVersion)
        assertFalse(String(WearableProtocol.helloAck(request, "stream-one")).contains("SESSION_CONTROL"))
        assertThrows(WearableProtocolException::class.java) { WearableProtocol.decodeRequest(bytes(hello.replace("\"protocolVersion\":2", "\"protocolVersion\":1"))) }
    }
}
