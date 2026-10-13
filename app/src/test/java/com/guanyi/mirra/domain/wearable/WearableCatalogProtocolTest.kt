package com.guanyi.mirra.domain.wearable

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import com.guanyi.mirra.data.local.entity.*

class WearableCatalogProtocolTest {
    @Test fun `v3 catalog negotiation preserves explicit capability order`() {
        val hello = """{"protocolVersion":3,"type":"HELLO","clientInstanceId":"client","requestId":"hello","capabilities":["READ_ONLY_SNAPSHOT","BOOK_CATALOG"]}"""
        val request = WearableProtocol.decodeRequest(hello.toByteArray())
        assertEquals(3, request.protocolVersion)
        assertEquals(listOf("READ_ONLY_SNAPSHOT", "BOOK_CATALOG"), request.capabilities)
        val ack = WearableProtocol.decodeMessage(WearableProtocol.helloAck(request, "stream"))
        assertEquals(request.capabilities, ack.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content })
    }
    @Test fun `v3 online snapshot keeps v2 control field and no catalog payload leak`() {
        val request = WearableRequest(3, "GET_SNAPSHOT", "client", "get", "stream")
        val message = WearableProtocol.decodeMessage(WearableProtocol.snapshot(request, "stream", 1, 1_000,
            WearableSnapshot(WearablePhase.IDLE)))
        assertTrue("control" in message)
        assertFalse("books" in message)
    }
    private fun book(id: String = "book", title: String = "中文书📖", action: String? = "翻到当前页，读第一段。") =
        WearableCatalogBook(id, title, 300, 42, action, false)
    @Test fun `phone and band golden frames agree on exact bytes CRC and revision`() {
        val golden = checkNotNull(javaClass.getResourceAsStream("/wearable/protocol-v3/catalog-v3.json"))
            .use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject }
        val catalog = Json.decodeFromJsonElement<WearableBookCatalog>(golden.getValue("catalog"))
        val encoded = WearableCatalogProtocol.encode(catalog)
        assertEquals(golden.getValue("canonical").jsonPrimitive.content, encoded.bytes.toString(Charsets.UTF_8))
        val request = WearableProtocol.decodeRequest(golden.getValue("request").toString().toByteArray())
        golden.getValue("frames").jsonArray.forEach { frame ->
            val tree = WearableProtocol.decodeMessage(frame.toString().toByteArray())
            val type = tree.getValue("type").jsonPrimitive.content
            val generated = when (type) {
                "CATALOG_MANIFEST" -> WearableCatalogProtocol.manifest(request, checkNotNull(request.streamId), encoded)
                "CATALOG_PART" -> WearableCatalogProtocol.part(request, checkNotNull(request.streamId), encoded, tree.getValue("index").jsonPrimitive.int)
                else -> null
            }
            if (generated != null) assertEquals(tree, WearableProtocol.decodeMessage(generated))
        }
    }
    @Test fun `only mainline and explicitly selected IN_PROGRESS books are projected with stable order`() {
        fun item(id: String, status: LearningItemStatus = LearningItemStatus.IN_PROGRESS, main: Boolean = false,
            action: String = "翻到当前页，读第一段。") = LearningItemEntity(id, id, status, 200, 1,
                if (main) 1 else null, action, 1, 1, null)
        val items = listOf(item("b"), item("main", main = true), item("a"), item("paused", LearningItemStatus.PAUSED),
            item("completed", LearningItemStatus.COMPLETED), item("unknown-action", action = "开始学习"))
        val actual = WearableCatalogProjection.project(items, listOf("b", "paused", "completed", "unknown-action"))
        assertEquals(listOf("main", "b", "unknown-action"), actual.books.map { it.id })
        assertNull(actual.books.last().firstAction)
        assertEquals(items, items.toList())
    }
    @Test fun `long semantic content is rejected without truncation or omitted books`() {
        listOf(book(title = "中".repeat(121)), book(action = "读".repeat(161)), book(id = "bad.id"),
            book(title = "bad\u200btitle"), book(action = "a\n读"), book(title = "\ud800"),
            book().copy(totalPages = 0), book().copy(currentPage = 301)).forEach {
            assertThrows(WearableCatalogException::class.java) { WearableCatalogProtocol.encode(WearableBookCatalog(books = listOf(it))) }
        }
    }
    @Test fun `maximum Unicode fields fit bounded parts including heavily quoted strings`() {
        val books = (1..12).map { book("book-$it", "📖".repeat(120), "读" + "\"".repeat(159)) }
        val encoded = WearableCatalogProtocol.encode(WearableBookCatalog(books = books))
        val request = WearableRequest(3, "GET_CATALOG", "client", "get", "stream")
        assertTrue(encoded.bytes.size <= 16_384)
        assertTrue(encoded.parts.size <= 32)
        assertEquals(encoded.bytes.toString(Charsets.UTF_8), encoded.parts.joinToString(""))
        encoded.parts.indices.forEach {
            assertTrue(encoded.parts[it].toByteArray().size <= 1_024)
            assertTrue(WearableCatalogProtocol.part(request, "stream", encoded, it).size <= 4_096)
        }
    }
    @Test fun `duplicate ids and multiple mainlines cannot encode a misleading complete catalog`() {
        listOf(listOf(book(), book()), listOf(book("a").copy(isMainline = true), book("b").copy(isMainline = true)),
            (1..13).map { book("book-$it") }).forEach { books ->
            assertThrows(WearableCatalogException::class.java) { WearableCatalogProtocol.encode(WearableBookCatalog(books = books)) }
        }
    }
    @Test fun `v3 catalog envelopes reject unknown duplicate and coerced fields`() {
        val text = """{"protocolVersion":3,"type":"GET_CATALOG","clientInstanceId":"client","requestId":"get","streamId":"stream","knownRevision":null}"""
        listOf(text.replace("\"protocolVersion\":3", "\"protocolVersion\":\"3\""),
            text.replace("\"knownRevision\":null", "\"knownRevision\":null,\"knownRevision\":null"),
            text.replace("\"knownRevision\":null", "\"knownRevision\":null,\"extra\":1"),
            text.replace("\"knownRevision\":null", "\"knownRevision\":\"bogus\"")).forEach {
            assertThrows(WearableProtocolException::class.java) { WearableProtocol.decodeRequest(it.toByteArray()) }
        }
    }
    @Test fun `manifest revision and part capacity must agree with exact declared payload`() {
        val request = WearableRequest(3, "GET_CATALOG", "client", "get", "stream")
        val transfer = WearableCatalogProtocol.encode(WearableBookCatalog(books = listOf(book())))
        val valid = WearableProtocol.decodeMessage(WearableCatalogProtocol.manifest(request, "stream", transfer))
        val invalid = listOf(
            JsonObject(valid + ("revision" to JsonPrimitive("catalog-00000000-${transfer.bytes.size}"))),
            JsonObject(valid + ("revision" to JsonPrimitive("catalog-${transfer.checksum}-${transfer.bytes.size + 1}"))),
            JsonObject(valid + mapOf("totalBytes" to JsonPrimitive(2_049), "revision" to JsonPrimitive("catalog-${transfer.checksum}-2049"))),
            JsonObject(valid + mapOf("totalBytes" to JsonPrimitive(1), "partCount" to JsonPrimitive(2), "revision" to JsonPrimitive("catalog-${transfer.checksum}-1"))))
        invalid.forEach { frame -> assertThrows(WearableProtocolException::class.java) { WearableProtocol.decodeMessage(frame.toString().toByteArray()) } }
        val ack = """{"protocolVersion":3,"type":"CATALOG_ACK","clientInstanceId":"client","requestId":"get","streamId":"stream","revision":"catalog-00000000-99999","code":"PERSISTED"}"""
        assertThrows(WearableProtocolException::class.java) { WearableProtocol.decodeRequest(ack.toByteArray()) }
    }
}
