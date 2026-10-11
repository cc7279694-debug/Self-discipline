package com.guanyi.mirra.domain.wearable

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class WearableProtocolException : Exception("INVALID_MESSAGE")
data class WearableRequest(val protocolVersion: Int, val type: String, val clientInstanceId: String,
    val requestId: String, val streamId: String? = null, val lastSeenSessionId: String? = null)

/** Bounded UTF-8 JSON, exact field sets and a read-only command allowlist. */
object WearableProtocol {
    const val MAX_BYTES = 4_096
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    private val id = Regex("^[A-Za-z0-9._:-]{1,80}$")
    private val reserved = setOf("__proto__", "prototype", "constructor")
    private val codes = setOf("SOURCE_BUSY", "DISABLED", "UNSUPPORTED_PROTOCOL", "SOURCE_UNAVAILABLE", "UNAUTHORIZED", "IDENTITY_ERROR")

    fun decodeRequest(payload: ByteArray): WearableRequest {
        val tree = decodeObject(payload)
        try {
            val version = tree["protocolVersion"]?.jsonPrimitive
            require(version != null && !version.isString && version.intOrNull == 1)
            val type = string(tree, "type")
            val common = setOf("protocolVersion", "type", "clientInstanceId", "requestId")
            require(tree.keys == when (type) {
                "HELLO" -> common + "capabilities"
                "GET_SNAPSHOT" -> common + setOf("streamId", "lastSeenSessionId")
                else -> error("write command")
            })
            val client = string(tree, "clientInstanceId").also { require(id.matches(it)) }
            val request = string(tree, "requestId").also { require(id.matches(it)) }
            if (type == "HELLO") {
                require(tree["capabilities"] == buildJsonArray { add("READ_ONLY_SNAPSHOT") })
                return WearableRequest(1, type, client, request)
            }
            val stream = string(tree, "streamId").also { require(id.matches(it)) }
            val previous = tree["lastSeenSessionId"].let {
                if (it == JsonNull) null else string(tree, "lastSeenSessionId").also { value -> require(id.matches(value)) }
            }
            return WearableRequest(1, type, client, request, stream, previous)
        } catch (_: Exception) { throw WearableProtocolException() }
    }

    /** Shared fixtures also exercise the reply parser/strict DTO shape without SDK dependencies. */
    fun decodeObject(payload: ByteArray): JsonObject = try {
        require(payload.isNotEmpty() && payload.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString()
        checkJsonKeys(text)
        json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) { throw WearableProtocolException() }

    fun helloAck(request: WearableRequest, stream: String): ByteArray = envelope(request, "HELLO_ACK", stream) {
        put("capabilities", buildJsonArray { add("READ_ONLY_SNAPSHOT") })
    }
    fun status(request: WearableRequest, code: String, stream: String? = null): ByteArray {
        require(code in codes)
        return envelope(request, "STATUS_ERROR", stream) { put("code", code) }
    }
    fun snapshot(request: WearableRequest, stream: String, sequence: Long, observedAt: Long,
        body: WearableSnapshot): ByteArray {
        require(sequence in 1..WearableProjection.MAX_SAFE_INTEGER && observedAt in 1..WearableProjection.MAX_SAFE_INTEGER)
        val display = displayCopy(body)
        validateBody(display)
        return envelope(request, "SNAPSHOT", stream) {
            put("sequence", sequence); put("sourceObservedAtUnixMs", observedAt)
            put("body", json.encodeToJsonElement(display))
        }
    }
    fun displayCopy(body: WearableSnapshot): WearableSnapshot {
        fun copy(value: String?, max: Int): String? = value?.let {
            require(validUnicode(it)); val points = it.codePointCount(0, it.length)
            if (points <= max) it else it.substring(0, it.offsetByCodePoints(0, max))
        }
        return body.copy(bookTitle = copy(body.bookTitle, 120), firstAction = copy(body.firstAction, 180),
            bookTitleTruncated = body.bookTitleTruncated || (body.bookTitle?.codePointCount(0, body.bookTitle.length) ?: 0) > 120,
            firstActionTruncated = body.firstActionTruncated || (body.firstAction?.codePointCount(0, body.firstAction.length) ?: 0) > 180)
    }
    fun validateBody(body: WearableSnapshot) {
        listOf(body.intentId, body.sessionId, body.learningItemId).forEach { require(it == null || id.matches(it)) }
        require(body.currentPage == null || body.totalPages != null && body.currentPage in 1..body.totalPages)
        require(body.totalPages == null || body.totalPages >= 1)
        listOf(body.elapsedMs, body.remainingMs).forEach { require(it == null || it in 0..WearableProjection.MAX_SAFE_INTEGER) }
        require(body.segmentType == null || body.segmentType in setOf("FOCUS", "DEEP_FOCUS", "BREAK", "TEMPORARY_ALLOWANCE", "DISTRACTION", "RECOVERY", "UNMONITORED"))
        require(body.coverage == null || body.coverage in setOf("FULL", "PARTIAL", "NONE"))
        when (body.phase) {
            WearablePhase.IDLE -> require(body.intentId == null && body.sessionId == null && body.segmentType == null)
            WearablePhase.PREPARING -> require(body.intentId != null && body.learningItemId != null && body.sessionId == null && body.segmentType == null)
            WearablePhase.ACTIVE -> require(body.sessionId != null && body.learningItemId != null && body.segmentType != null && body.coverage != null)
            WearablePhase.SAVING, WearablePhase.ENDED -> require(body.sessionId != null && body.learningItemId != null && body.segmentType == null)
        }
    }
    private fun envelope(request: WearableRequest, type: String, stream: String?, extra: JsonObjectBuilder.() -> Unit): ByteArray {
        val value = buildJsonObject {
            put("protocolVersion", 1); put("type", type)
            put("clientInstanceId", request.clientInstanceId); put("requestId", request.requestId)
            if (stream != null) { require(id.matches(stream)); put("streamId", stream) }
            extra()
        }
        return json.encodeToString(value).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    }
    private fun string(tree: JsonObject, key: String): String {
        val value = checkNotNull(tree[key]).jsonPrimitive
        require(value.isString && validUnicode(value.content))
        return value.content
    }
    private fun validUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isHighSurrogate()) { if (index == value.length || !value[index++].isLowSurrogate()) return false }
            else if (char.isLowSurrogate()) return false
        }
        return true
    }
    /** Detect duplicate/escaped dangerous keys before a JSON map could silently overwrite them. */
    private fun checkJsonKeys(text: String) {
        var offset = 0
        fun whitespace() { while (offset < text.length && text[offset].isWhitespace()) offset++ }
        fun quoted(): String {
            require(text[offset] == '"'); val start = offset++
            while (offset < text.length) {
                when (text[offset++]) { '\\' -> { require(offset < text.length); offset++ }; '"' -> return json.decodeFromString<String>(text.substring(start, offset)) }
            }
            error("unterminated")
        }
        fun value(depth: Int) {
            require(depth <= 4); whitespace(); require(offset < text.length)
            when (text[offset]) {
                '{' -> {
                    offset++; whitespace(); val seen = mutableSetOf<String>()
                    if (offset < text.length && text[offset] == '}') { offset++; return }
                    while (true) {
                        whitespace(); val key = quoted(); require(key !in reserved && seen.add(key)); whitespace()
                        require(text[offset++] == ':'); value(depth + 1); whitespace()
                        when (text[offset++]) { '}' -> return; ',' -> Unit; else -> error("separator") }
                    }
                }
                '[' -> {
                    offset++; whitespace(); if (offset < text.length && text[offset] == ']') { offset++; return }
                    while (true) { value(depth + 1); whitespace(); when (text[offset++]) { ']' -> return; ',' -> Unit; else -> error("separator") } }
                }
                '"' -> require(validUnicode(quoted()))
                else -> { val start = offset; while (offset < text.length && text[offset] !in ",]} \t\r\n") offset++; require(offset > start) }
            }
        }
        value(0); whitespace(); require(offset == text.length)
    }
}
