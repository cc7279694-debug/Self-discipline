package com.guanyi.mirra.domain.wearable

import org.junit.Assert.*
import org.junit.Test

class WearableProtocolTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)
    private val hello = """{"protocolVersion":1,"type":"HELLO","clientInstanceId":"client-one","requestId":"request-one","capabilities":["READ_ONLY_SNAPSHOT"]}"""
    @Test fun `hello parses exact read only request`() {
        assertEquals("HELLO", WearableProtocol.decodeRequest(bytes(hello)).type)
    }
    @Test fun `protocol version must be JSON integer not a string`() {
        assertThrows(WearableProtocolException::class.java) {
            WearableProtocol.decodeRequest(bytes(hello.replace("\"protocolVersion\":1", "\"protocolVersion\":\"1\"")))
        }
    }
    @Test fun `business writes and nested unsafe input rejected`() {
        listOf(hello.replace("HELLO", "START"), hello.replace("1,", "3,"),
            hello.dropLast(1) + ",\"__proto__\":{}}", "[".repeat(80) + "]".repeat(80),
            hello.dropLast(1) + ",\"type\":\"HELLO\"}").forEach {
            assertThrows(WearableProtocolException::class.java) { WearableProtocol.decodeRequest(bytes(it)) }
        }
    }
    @Test fun `budget is UTF8 bytes and malformed encoding rejected`() {
        assertThrows(WearableProtocolException::class.java) { WearableProtocol.decodeRequest(bytes("中".repeat(1_366))) }
        assertThrows(WearableProtocolException::class.java) { WearableProtocol.decodeRequest(byteArrayOf(0xC3.toByte(), 0x28)) }
    }
    @Test fun `snapshot uses whitelist and preserves nulls and Chinese`() {
        val body = WearableSnapshot(WearablePhase.IDLE, bookTitle = "中文 📖")
        val text = String(WearableProtocol.snapshot(WearableRequest(1, "GET_SNAPSHOT", "client", "req", "stream"),
            "stream", 1, 1_000, body), Charsets.UTF_8)
        assertTrue(text.contains("中文 📖"))
        assertTrue(text.contains("\"elapsedMs\":null"))
        assertFalse(text.contains("packageName"))
        assertFalse(text.contains("generatedSummary"))
    }
    @Test fun `display truncation preserves complete Unicode code point`() {
        val value = "📖".repeat(122)
        val body = WearableSnapshot(WearablePhase.IDLE, bookTitle = value)
        val trimmed = WearableProtocol.displayCopy(body)
        assertEquals(120, trimmed.bookTitle!!.codePointCount(0, trimmed.bookTitle.length))
        assertTrue(trimmed.bookTitleTruncated)
        assertEquals(value, body.bookTitle)
    }
}
