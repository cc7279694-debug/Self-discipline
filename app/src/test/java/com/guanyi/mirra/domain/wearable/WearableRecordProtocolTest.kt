package com.guanyi.mirra.domain.wearable

import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WearableRecordProtocolTest {
    private fun fixture(): JsonObject = checkNotNull(javaClass.getResourceAsStream("/wearable/protocol-v4/record-v4.json"))
        .use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject }
    private fun hash(raw: String) = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    @Test fun `shared v4 raw facts preserve SHA bytes and never assert monitored focus`() {
        val golden = fixture(); val raw = golden.getValue("canonical").jsonPrimitive.content
        val value = WearableLocalRecord.parse(raw, golden.getValue("digest").jsonPrimitive.content)
        assertEquals(raw, value.canonical); assertEquals(hash(raw), value.digest)
        assertEquals(golden.getValue("bytes").jsonPrimitive.int, value.bytes)
        assertEquals("install-a", value.installationId); assertEquals("install-a:1", value.sessionId)
        assertEquals("中文与📖", value.bookTitle); assertEquals(12, value.startPage)
        assertNull(value.endPage); assertEquals(60_000L, value.referenceFocusMs); assertTrue(value.timeUncertain)
    }
    @Test fun `all golden frames validate exact consent identity and record fields`() {
        fixture().getValue("messages").jsonArray.forEach { message ->
            val raw = message.toString().toByteArray(Charsets.UTF_8)
            assertEquals(message, WearableProtocol.decodeMessage(raw))
            if (message.jsonObject.getValue("type").jsonPrimitive.content in setOf("LOCAL_RECORD_OFFER", "LOCAL_RECORD_PART", "LOCAL_RECORD_COMMIT")) {
                val request = WearableProtocol.decodeRequest(raw)
                assertEquals(4, request.protocolVersion); assertNotNull(request.record)
                assertEquals("install-a:1", request.record!!.localSessionId)
            }
        }
    }
    @Test fun `golden long unicode unknown clock and fractional number lexemes stay byte exact`() {
        fixture().getValue("cases").jsonArray.forEach { item ->
            val value = item.jsonObject
            val raw = value.getValue("canonical").jsonPrimitive.content
            val parsed = WearableLocalRecord.parse(raw, value.getValue("digest").jsonPrimitive.content)
            assertEquals(raw, parsed.canonical); assertEquals(value.getValue("bytes").jsonPrimitive.int, parsed.bytes)
            assertEquals(value.getValue("record").jsonObject.getValue("session"), parsed.session)
            if (value.getValue("name").jsonPrimitive.content == "unknown-time-cross-owner") {
                assertNull(parsed.referenceFocusMs); assertNull(parsed.referenceBreakMs); assertTrue(parsed.timeUncertain)
            }
        }
    }
    @Test fun `canonical input rejects open ends duplicate danger unknown fields changed identity and digest`() {
        val raw = fixture().getValue("canonical").jsonPrimitive.content
        val invalid = listOf(raw + " ", raw.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            raw.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"constructor\":1"),
            raw.replace("\"id\":\"install-a:1\"", "\"id\":\"install-b:1\""),
            raw.replace("\"wallMs\":1000", "\"wallMs\":1e3"), raw.replace("\"endPage\":null", "\"endPage\":0"),
            raw.replace("\"finishedAt\":{\"wallMs\":61000,\"ownerId\":\"owner-a\",\"monotonicMs\":null,\"monotonicVerified\":false}", "\"finishedAt\":null"))
        invalid.forEach { assertThrows(Exception::class.java) { WearableLocalRecord.parseCanonical(it) } }
        assertThrows(Exception::class.java) { WearableLocalRecord.parse(raw, "0".repeat(64)) }
        assertThrows(Exception::class.java) { WearableLocalRecord.parseCanonical(" ".repeat(8193)) }
    }
    @Test fun `record replies retain exact original transfer context and safe codes`() {
        val offer = fixture().getValue("messages").jsonArray.map { it.jsonObject }.first { it.getValue("type").jsonPrimitive.content == "LOCAL_RECORD_OFFER" }
        val request = WearableProtocol.decodeRequest(offer.toString().toByteArray())
        val needed = WearableProtocol.decodeMessage(WearableRecordProtocol.need(request))
        assertEquals("NEED", needed.getValue("code").jsonPrimitive.content)
        val ack = WearableProtocol.decodeMessage(WearableRecordProtocol.ack(request, true))
        assertEquals("ALREADY_IMPORTED", ack.getValue("code").jsonPrimitive.content)
        assertEquals(offer.getValue("recordDigest"), ack.getValue("recordDigest"))
        assertEquals(offer.getValue("totalBytes"), ack.getValue("totalBytes"))
        assertThrows(Exception::class.java) { WearableRecordProtocol.error(request, "APPLIED") }
    }
}
