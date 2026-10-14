package com.guanyi.mirra.domain.wearable

import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.domain.FirstActionResolver
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.zip.CRC32

@Serializable data class WearableCatalogBook(val id: String, val title: String, val totalPages: Int?,
    val currentPage: Int, val firstAction: String?, val isMainline: Boolean)
@Serializable data class WearableBookCatalog(val schemaVersion: Int = 1, val books: List<WearableCatalogBook>)
data class WearableCatalogChoice(val id: String, val title: String, val isMainline: Boolean)
interface WearableCatalogSource {
    suspend fun read(selectedIds: List<String>): WearableBookCatalog
    suspend fun choices(): List<WearableCatalogChoice>
}
class WearableCatalogException(val code: String) : Exception(code)

/** A read-only projection. A resolved stored action is still not a per-session confirmation. */
object WearableCatalogProjection {
    fun project(items: List<LearningItemEntity>, selectedIds: List<String>): WearableBookCatalog {
        val eligible = items.filter { it.status == LearningItemStatus.IN_PROGRESS }
        val selected = selectedIds.toSet()
        val books = eligible.filter { it.isMainline || it.id in selected }
            .sortedWith(compareByDescending<LearningItemEntity> { it.isMainline }.thenBy { it.id })
            .map { item -> WearableCatalogBook(item.id, item.name, item.totalPages, item.currentPage,
                FirstActionResolver.resolve(item).takeIf { it.isNotEmpty() }, item.isMainline) }
        return WearableBookCatalog(books = books).also(WearableCatalogProtocol::validateCatalog)
    }
}

data class WearableCatalogTransfer(val revision: String, val checksum: String, val bytes: ByteArray,
    val parts: List<String>, val bookCount: Int)

/** v3 catalog frames share the existing strict UTF-8 envelope and 4096-byte channel budget. */
object WearableCatalogProtocol {
    const val MAX_BOOKS = 12
    const val MAX_CATALOG_BYTES = 16_384
    const val MAX_PART_BYTES = 1_024
    const val MAX_PARTS = 32
    const val DEADLINE_MS = 30_000L
    val TYPES = setOf("GET_CATALOG", "CATALOG_MANIFEST", "CATALOG_PART", "CATALOG_ACK", "CATALOG_ERROR")
    val ERRORS = setOf("CATALOG_DISABLED", "CATALOG_TOO_LARGE", "SOURCE_UNAVAILABLE", "SOURCE_BUSY",
        "INVALID_CATALOG", "TIMEOUT", "CONFLICT", "PERSIST_FAILED", "RESULT_UNKNOWN")
    private val id = Regex("^[A-Za-z0-9_-]{1,48}$")
    private val wireId = Regex("^[A-Za-z0-9._:-]{1,80}$")
    private val revision = Regex("^catalog-[0-9a-f]{8}-[1-9][0-9]{0,4}$")
    private val crc = Regex("^[0-9a-f]{8}$")
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    private val common = setOf("protocolVersion", "type", "clientInstanceId", "requestId", "streamId")
    fun validBookId(value: String) = id.matches(value)
    fun validateCatalog(catalog: WearableBookCatalog) {
        if (catalog.schemaVersion != 1 || catalog.books.size > MAX_BOOKS ||
            catalog.books.map { it.id }.distinct().size != catalog.books.size ||
            catalog.books.count { it.isMainline } > 1) throw WearableCatalogException("INVALID_CATALOG")
        catalog.books.forEach { book ->
            if (!validBookId(book.id) || !validText(book.title, 120) ||
                book.totalPages != null && book.totalPages !in 1..999_999 ||
                book.currentPage !in 0..999_999 || book.totalPages != null && book.currentPage > book.totalPages ||
                book.firstAction != null && !validText(book.firstAction, 160)) throw WearableCatalogException("INVALID_CATALOG")
        }
    }
    private fun validText(value: String, max: Int): Boolean {
        if (value.isBlank() || value.codePointCount(0, value.length) > max) return false
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            val point = if (char.isHighSurrogate()) {
                if (index == value.length || !value[index].isLowSurrogate()) return false
                Character.toCodePoint(char, value[index++])
            } else { if (char.isLowSurrogate()) return false; char.code }
            if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT.toInt()) return false
        }
        return true
    }
    fun checksum(bytes: ByteArray): String = CRC32().apply { update(bytes) }.value.toString(16).padStart(8, '0')
    fun encode(catalog: WearableBookCatalog): WearableCatalogTransfer {
        validateCatalog(catalog)
        val text = json.encodeToString(catalog)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CATALOG_BYTES) throw WearableCatalogException("CATALOG_TOO_LARGE")
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var byteCount = 0
        var offset = 0
        while (offset < text.length) {
            val point = text.codePointAt(offset)
            val value = String(Character.toChars(point))
            val size = value.toByteArray(Charsets.UTF_8).size
            if (byteCount + size > MAX_PART_BYTES) { parts += current.toString(); current.setLength(0); byteCount = 0 }
            current.append(value); byteCount += size; offset += Character.charCount(point)
        }
        if (current.isNotEmpty()) parts += current.toString()
        if (parts.isEmpty() || parts.size > MAX_PARTS) throw WearableCatalogException("CATALOG_TOO_LARGE")
        val checksum = checksum(bytes)
        return WearableCatalogTransfer("catalog-$checksum-${bytes.size}", checksum, bytes, parts, catalog.books.size)
    }
    fun decodeRequest(tree: JsonObject): WearableRequest {
        validateMessage(tree)
        require(tree.getValue("type").jsonPrimitive.content in setOf("GET_CATALOG", "CATALOG_ACK", "CATALOG_ERROR"))
        return WearableRequest(tree.getValue("protocolVersion").jsonPrimitive.int, tree.getValue("type").jsonPrimitive.content,
            tree.getValue("clientInstanceId").jsonPrimitive.content, tree.getValue("requestId").jsonPrimitive.content,
            tree.getValue("streamId").jsonPrimitive.content,
            knownRevision = tree["knownRevision"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
            catalogRevision = tree["revision"]?.jsonPrimitive?.content,
            catalogCode = tree["code"]?.jsonPrimitive?.content)
    }
    fun validateMessage(tree: JsonObject) {
        fun text(key: String) = tree.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
        fun number(key: String) = tree.getValue(key).jsonPrimitive.let { require(!it.isString); checkNotNull(it.intOrNull) }
        fun validRevision(value: String): Boolean = revision.matches(value) &&
            value.substringAfterLast('-').toIntOrNull()?.let { it in 1..MAX_CATALOG_BYTES } == true
        require(number("protocolVersion") in 3..4)
        listOf("clientInstanceId", "requestId", "streamId").forEach { require(wireId.matches(text(it))) }
        val type = text("type")
        require(tree.keys == common + when (type) {
            "GET_CATALOG" -> setOf("knownRevision")
            "CATALOG_MANIFEST" -> setOf("revision", "schemaVersion", "totalBytes", "partCount", "bookCount", "checksum")
            "CATALOG_PART" -> setOf("revision", "index", "data", "checksum")
            "CATALOG_ACK" -> setOf("revision", "code")
            "CATALOG_ERROR" -> setOf("code")
            else -> error("catalog type")
        })
        if (type == "GET_CATALOG") require(tree.getValue("knownRevision") == JsonNull || validRevision(text("knownRevision")))
        if ("revision" in tree) require(validRevision(text("revision")))
        when (type) {
            "CATALOG_MANIFEST" -> {
                require(number("schemaVersion") == 1 && number("totalBytes") in 1..MAX_CATALOG_BYTES &&
                    number("partCount") in 1..MAX_PARTS && number("bookCount") in 0..MAX_BOOKS && crc.matches(text("checksum")))
                val bytes = number("totalBytes")
                require(text("revision") == "catalog-${text("checksum")}-${bytes}" &&
                    number("partCount") >= (bytes + MAX_PART_BYTES - 1) / MAX_PART_BYTES && number("partCount") <= bytes)
            }
            "CATALOG_PART" -> require(number("index") in 0 until MAX_PARTS &&
                text("data").toByteArray(Charsets.UTF_8).size in 1..MAX_PART_BYTES && crc.matches(text("checksum")))
            "CATALOG_ACK" -> require(text("code") == "PERSISTED")
            "CATALOG_ERROR" -> require(text("code") in ERRORS)
        }
    }
    private fun frame(request: WearableRequest, stream: String, type: String, fields: JsonObjectBuilder.() -> Unit): ByteArray {
        require(request.protocolVersion in 3..4)
        val tree = buildJsonObject {
            put("protocolVersion", request.protocolVersion); put("type", type); put("clientInstanceId", request.clientInstanceId)
            put("requestId", request.requestId); put("streamId", stream); fields()
        }
        validateMessage(tree)
        return json.encodeToString(tree).toByteArray(Charsets.UTF_8).also { require(it.size <= WearableProtocol.MAX_BYTES) }
    }
    fun manifest(request: WearableRequest, stream: String, value: WearableCatalogTransfer): ByteArray = frame(request, stream, "CATALOG_MANIFEST") {
        put("revision", value.revision); put("schemaVersion", 1); put("totalBytes", value.bytes.size)
        put("partCount", value.parts.size); put("bookCount", value.bookCount); put("checksum", value.checksum)
    }
    fun part(request: WearableRequest, stream: String, value: WearableCatalogTransfer, index: Int): ByteArray = frame(request, stream, "CATALOG_PART") {
        put("revision", value.revision); put("index", index); put("data", value.parts[index])
        put("checksum", checksum(value.parts[index].toByteArray(Charsets.UTF_8)))
    }
    fun error(request: WearableRequest, stream: String, code: String): ByteArray = frame(request, stream, "CATALOG_ERROR") { put("code", code) }
}
