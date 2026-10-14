package com.guanyi.mirra.domain.wearable

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class WearableProtocolException : Exception("INVALID_MESSAGE")
data class WearableRequest(val protocolVersion: Int, val type: String, val clientInstanceId: String,
    val requestId: String, val streamId: String? = null, val lastSeenSessionId: String? = null,
    val capabilities: List<String> = listOf("READ_ONLY_SNAPSHOT"),
    val controlGrant: String? = null, val action: String? = null,
    val expectedIntentId: String? = null, val expectedSessionId: String? = null,
    val expectedSegmentId: String? = null, val expectedBookId: String? = null,
    val expectedPage: Int? = null, val expectedFirstAction: String? = null, val breakMinutes: Int? = null,
    val knownRevision: String? = null, val catalogRevision: String? = null, val catalogCode: String? = null,
    val record: WearableRecordFrame? = null)

/** Facts and a short-lived authorization issued by the current phone owner. */
@Serializable data class WearableControlState(
    val activeSegmentId: String? = null, val allowedActions: List<String> = emptyList(),
    val controlGrant: String? = null, val grantExpiresAtUnixMs: Long? = null,
    val expectedFirstAction: String? = null)

data class WearableCommandOutcome(val code: String, val sessionId: String? = null,
    val monitorDegraded: Boolean = false)

/** Bounded UTF-8 JSON. v1 remains read-only; v2 permits only three exact commands. */
object WearableProtocol {
    const val VERSION = 1
    const val CONTROL_VERSION = 2
    const val CATALOG_VERSION = 3
    const val RECORD_VERSION = 4
    const val MAX_BYTES = 4_096
    const val MAX_CONTROL_TTL_MS = 30_000L
    val ACTIONS = setOf("START_BREAK", "FINISH_BREAK", "CONFIRM_PREPARATION_AND_START")
    val RESULT_CODES = setOf("APPLIED", "ALREADY_APPLIED", "STALE_STATE", "REQUIRES_PHONE", "DISABLED", "UNAUTHORIZED", "BUSY", "FAILED", "RESULT_UNKNOWN")
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    private val id = Regex("^[A-Za-z0-9._:-]{1,80}$")
    private val reserved = setOf("__proto__", "prototype", "constructor")
    private val codes = setOf("SOURCE_BUSY", "DISABLED", "UNSUPPORTED_PROTOCOL", "SOURCE_UNAVAILABLE", "UNAUTHORIZED", "IDENTITY_ERROR")
    private val common = setOf("protocolVersion", "type", "clientInstanceId", "requestId")
    private val commandKeys = setOf("streamId", "controlGrant", "action", "expectedIntentId", "expectedSessionId", "expectedSegmentId", "expectedBookId", "expectedPage", "expectedFirstAction", "breakMinutes")
    private val bodyKeys = setOf("phase", "intentId", "sessionId", "learningItemId", "bookTitle", "currentPage", "totalPages", "firstAction", "bookTitleTruncated", "firstActionTruncated", "segmentType", "coverage", "monitorAvailability", "elapsedMs", "remainingMs", "timerKind", "endType")
    private val controlKeys = setOf("activeSegmentId", "allowedActions", "controlGrant", "grantExpiresAtUnixMs", "expectedFirstAction")

    fun decodeRequest(payload: ByteArray): WearableRequest {
        val tree = decodeObject(payload)
        try {
            val version = version(tree)
            val type = string(tree, "type")
            if (type in WearableRecordProtocol.TYPES) return WearableRecordProtocol.decodeRequest(tree)
            if (type in WearableCatalogProtocol.TYPES) return WearableCatalogProtocol.decodeRequest(tree)
            require(tree.keys == when (type) {
                "HELLO" -> common + "capabilities"
                "GET_SNAPSHOT" -> common + setOf("streamId", "lastSeenSessionId")
                "COMMAND" -> { require(version >= CONTROL_VERSION); common + commandKeys }
                else -> error("write command")
            })
            val client = string(tree, "clientInstanceId").also { require(id.matches(it)) }
            val request = string(tree, "requestId").also { require(id.matches(it)) }
            if (type == "HELLO") {
                return WearableRequest(version, type, client, request, capabilities = capabilities(tree, version))
            }
            val stream = string(tree, "streamId").also { require(id.matches(it)) }
            if (type == "COMMAND") {
                val result = WearableRequest(version, type, client, request, stream,
                    controlGrant = string(tree, "controlGrant"), action = string(tree, "action"),
                    expectedIntentId = nullableId(tree, "expectedIntentId"), expectedSessionId = nullableId(tree, "expectedSessionId"),
                    expectedSegmentId = nullableId(tree, "expectedSegmentId"), expectedBookId = nullableId(tree, "expectedBookId"),
                    expectedPage = nullableInt(tree, "expectedPage"), expectedFirstAction = nullableString(tree, "expectedFirstAction"),
                    breakMinutes = nullableInt(tree, "breakMinutes"))
                validateCommand(result)
                return result
            }
            return WearableRequest(version, type, client, request, stream, nullableId(tree, "lastSeenSessionId"))
        } catch (_: Exception) { throw WearableProtocolException() }
    }

    /** Exact message validator used by shared wire fixtures without Android/SDK dependencies. */
    fun decodeMessage(payload: ByteArray): JsonObject = try {
        val tree = decodeObject(payload)
        val version = version(tree)
        listOf("clientInstanceId", "requestId").forEach { require(id.matches(string(tree, it))) }
        val type = string(tree, "type")
        if (type in WearableRecordProtocol.TYPES) {
            WearableRecordProtocol.validateMessage(tree)
        } else if (type in WearableCatalogProtocol.TYPES) {
            WearableCatalogProtocol.validateMessage(tree)
        } else if (type in setOf("HELLO", "GET_SNAPSHOT", "COMMAND")) {
            decodeRequest(payload)
        } else {
            require(tree.keys == when (type) {
                "HELLO_ACK" -> common + setOf("streamId", "capabilities") + if (version == RECORD_VERSION) setOf("recordSyncEnabled") else emptySet()
                "SNAPSHOT" -> common + setOf("streamId", "sequence", "sourceObservedAtUnixMs", "body") + if (version >= CONTROL_VERSION) setOf("control") else emptySet()
                "STATUS_ERROR" -> common + "code" + if ("streamId" in tree) setOf("streamId") else emptySet()
                "COMMAND_RESULT" -> { require(version >= CONTROL_VERSION); common + setOf("streamId", "action", "code", "sessionId", "monitorDegraded") }
                else -> error("unknown reply")
            })
            if ("streamId" in tree) require(id.matches(string(tree, "streamId")))
            when (type) {
                "HELLO_ACK" -> {
                    capabilities(tree, version)
                    if (version == RECORD_VERSION) tree.getValue("recordSyncEnabled").jsonPrimitive.let {
                        require(!it.isString && it.booleanOrNull != null)
                    }
                }
                "STATUS_ERROR" -> require(string(tree, "code") in codes)
                "SNAPSHOT" -> {
                    require(safeLong(tree, "sequence") >= 1)
                    val observedAt = safeLong(tree, "sourceObservedAtUnixMs").also { require(it >= 1) }
                    require(tree.getValue("body").jsonObject.keys == bodyKeys)
                    validateBodyTokens(tree.getValue("body").jsonObject)
                    val body = json.decodeFromJsonElement<WearableSnapshot>(tree.getValue("body"))
                    validateWireBody(body)
                    if (version >= CONTROL_VERSION) {
                        require(tree.getValue("control").jsonObject.keys == controlKeys)
                        validateControlTokens(tree.getValue("control").jsonObject)
                        validateControl(json.decodeFromJsonElement(tree.getValue("control")), body, observedAt)
                    }
                }
                "COMMAND_RESULT" -> validateOutcome(string(tree, "action"), WearableCommandOutcome(string(tree, "code"),
                    nullableId(tree, "sessionId"), tree.getValue("monitorDegraded").jsonPrimitive.let { require(!it.isString); checkNotNull(it.booleanOrNull) }))
            }
        }
        tree
    } catch (_: Exception) { throw WearableProtocolException() }

    // Serialization may coerce primitive tokens; wire types are checked before DTO decoding.
    private fun validateBodyTokens(body: JsonObject) {
        listOf("intentId", "sessionId", "learningItemId").forEach { nullableId(body, it) }
        listOf("phase", "monitorAvailability", "timerKind").forEach { string(body, it) }
        listOf("bookTitle", "firstAction", "endType", "segmentType", "coverage").forEach {
            if (body.getValue(it) != JsonNull) string(body, it)
        }
        listOf("currentPage", "totalPages", "elapsedMs", "remainingMs").forEach {
            if (body.getValue(it) != JsonNull) safeLong(body, it)
        }
        listOf("bookTitleTruncated", "firstActionTruncated").forEach {
            val token = body.getValue(it).jsonPrimitive
            require(!token.isString && token.booleanOrNull != null)
        }
    }
    private fun validateControlTokens(control: JsonObject) {
        listOf("activeSegmentId", "controlGrant").forEach { nullableId(control, it) }
        control.getValue("allowedActions").jsonArray.forEach { require(it.jsonPrimitive.isString) }
        if (control.getValue("grantExpiresAtUnixMs") != JsonNull) safeLong(control, "grantExpiresAtUnixMs")
        if (control.getValue("expectedFirstAction") != JsonNull) string(control, "expectedFirstAction")
    }

    /** Shared fixtures also exercise the reply parser/strict DTO shape without SDK dependencies. */
    fun decodeObject(payload: ByteArray, maximumBytes: Int = MAX_BYTES, maximumDepth: Int = 4): JsonObject = try {
        require(maximumBytes in 1..8_192 && maximumDepth in 1..6)
        require(payload.isNotEmpty() && payload.size <= maximumBytes)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString()
        checkJsonKeys(text, maximumDepth)
        json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) { throw WearableProtocolException() }

    fun helloAck(request: WearableRequest, stream: String, recordSyncEnabled: Boolean = false): ByteArray = envelope(request, "HELLO_ACK", stream) {
        validateCapabilities(request.capabilities, request.protocolVersion)
        put("capabilities", buildJsonArray { request.capabilities.forEach(::add) })
        if (request.protocolVersion == RECORD_VERSION) put("recordSyncEnabled", recordSyncEnabled)
    }
    fun status(request: WearableRequest, code: String, stream: String? = null): ByteArray {
        require(code in codes)
        return envelope(request, "STATUS_ERROR", stream) { put("code", code) }
    }
    fun snapshot(request: WearableRequest, stream: String, sequence: Long, observedAt: Long,
        body: WearableSnapshot, control: WearableControlState = WearableControlState()): ByteArray {
        require(sequence in 1..WearableProjection.MAX_SAFE_INTEGER && observedAt in 1..WearableProjection.MAX_SAFE_INTEGER)
        val display = displayCopy(body)
        validateBody(display)
        if (request.protocolVersion >= CONTROL_VERSION) { validateWireBody(display); validateControl(control, display, observedAt) }
        return envelope(request, "SNAPSHOT", stream) {
            put("sequence", sequence); put("sourceObservedAtUnixMs", observedAt)
            put("body", json.encodeToJsonElement(display))
            if (request.protocolVersion >= CONTROL_VERSION) put("control", json.encodeToJsonElement(control))
        }
    }
    fun commandResult(request: WearableRequest, stream: String, outcome: WearableCommandOutcome): ByteArray {
        require(request.protocolVersion >= CONTROL_VERSION && request.type == "COMMAND")
        val action = checkNotNull(request.action)
        validateOutcome(action, outcome)
        return envelope(request, "COMMAND_RESULT", stream) {
            put("action", action); put("code", outcome.code)
            put("sessionId", outcome.sessionId?.let(::JsonPrimitive) ?: JsonNull)
            put("monitorDegraded", outcome.monitorDegraded)
        }
    }
    fun commandResult(request: WearableRequest, stream: String, code: String, sessionId: String? = null,
        monitorDegraded: Boolean = false): ByteArray = commandResult(request, stream, WearableCommandOutcome(code, sessionId, monitorDegraded))
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
    private fun validateWireBody(body: WearableSnapshot) {
        validateBody(body)
        listOf(body.bookTitle to 120, body.firstAction to 180, body.endType to 40).forEach { (text, max) ->
            require(text == null || validUnicode(text) && text.codePointCount(0, text.length) <= max)
        }
        if (body.phase == WearablePhase.IDLE) require(body.timerKind == WearableTimerKind.NONE)
        if (body.phase in setOf(WearablePhase.SAVING, WearablePhase.ENDED)) require(body.timerKind in setOf(WearableTimerKind.NONE, WearableTimerKind.FINAL_SESSION_DURATION))
    }
    private fun envelope(request: WearableRequest, type: String, stream: String?, extra: JsonObjectBuilder.() -> Unit): ByteArray {
        require(request.protocolVersion in VERSION..RECORD_VERSION)
        val value = buildJsonObject {
            put("protocolVersion", request.protocolVersion); put("type", type)
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
    private fun version(tree: JsonObject): Int {
        val value = tree.getValue("protocolVersion").jsonPrimitive
        require(!value.isString)
        return checkNotNull(value.intOrNull).also { require(it in VERSION..RECORD_VERSION) }
    }
    private fun nullableString(tree: JsonObject, key: String): String? = if (tree.getValue(key) == JsonNull) null else string(tree, key)
    private fun nullableId(tree: JsonObject, key: String): String? = nullableString(tree, key)?.also { require(id.matches(it)) }
    private fun nullableInt(tree: JsonObject, key: String): Int? {
        if (tree.getValue(key) == JsonNull) return null
        val value = tree.getValue(key).jsonPrimitive
        require(!value.isString)
        return checkNotNull(value.intOrNull)
    }
    private fun safeLong(tree: JsonObject, key: String): Long {
        val value = tree.getValue(key).jsonPrimitive
        require(!value.isString)
        return checkNotNull(value.longOrNull).also { require(it in 0..WearableProjection.MAX_SAFE_INTEGER) }
    }
    private fun capabilities(tree: JsonObject, version: Int): List<String> = tree.getValue("capabilities").jsonArray.map {
        require(it.jsonPrimitive.isString && validUnicode(it.jsonPrimitive.content)); it.jsonPrimitive.content
    }.also { validateCapabilities(it, version) }
    private fun validateCapabilities(values: List<String>, version: Int) {
        require(if (version == RECORD_VERSION)
            values == listOf("READ_ONLY_SNAPSHOT", "BOOK_CATALOG", "LOCAL_RECORD_SYNC") ||
                values == listOf("READ_ONLY_SNAPSHOT", "SESSION_CONTROL", "BOOK_CATALOG", "LOCAL_RECORD_SYNC")
        else if (version == CATALOG_VERSION)
            values == listOf("READ_ONLY_SNAPSHOT", "BOOK_CATALOG") ||
                values == listOf("READ_ONLY_SNAPSHOT", "SESSION_CONTROL", "BOOK_CATALOG")
        else values == listOf("READ_ONLY_SNAPSHOT") ||
            version == CONTROL_VERSION && values == listOf("READ_ONLY_SNAPSHOT", "SESSION_CONTROL"))
    }
    private fun validActionText(value: String) = validUnicode(value) && value.isNotBlank() && value.codePointCount(0, value.length) <= 180
    fun validateCommand(request: WearableRequest) {
        require(request.protocolVersion in CONTROL_VERSION..RECORD_VERSION && request.type == "COMMAND" && request.controlGrant?.let(id::matches) == true && request.action in ACTIONS)
        listOf(request.expectedIntentId, request.expectedSessionId, request.expectedSegmentId, request.expectedBookId).forEach { require(it == null || id.matches(it)) }
        if (request.action == "CONFIRM_PREPARATION_AND_START") {
            require(request.expectedIntentId != null && request.expectedBookId != null && request.expectedPage != null && request.expectedPage >= 1 && request.expectedFirstAction?.let(::validActionText) == true)
            require(request.expectedSessionId == null && request.expectedSegmentId == null && request.breakMinutes == null)
        } else {
            require(request.expectedSessionId != null && request.expectedSegmentId != null && request.expectedBookId == null && request.expectedPage == null && request.expectedFirstAction == null)
            require(if (request.action == "START_BREAK") request.breakMinutes in setOf(5, 10) else request.breakMinutes == null)
        }
    }
    fun validateControl(control: WearableControlState, body: WearableSnapshot, observedAt: Long) {
        listOf(control.activeSegmentId, control.controlGrant).forEach { require(it == null || id.matches(it)) }
        require(control.expectedFirstAction == null || validActionText(control.expectedFirstAction))
        require(control.allowedActions.size <= ACTIONS.size && control.allowedActions.toSet().size == control.allowedActions.size && control.allowedActions.all { it in ACTIONS })
        if (control.allowedActions.isEmpty()) {
            require(control.controlGrant == null && control.grantExpiresAtUnixMs == null && control.expectedFirstAction == null)
        } else {
            require(control.controlGrant != null && control.grantExpiresAtUnixMs != null && control.grantExpiresAtUnixMs in 1..WearableProjection.MAX_SAFE_INTEGER && control.grantExpiresAtUnixMs > observedAt && control.grantExpiresAtUnixMs - observedAt <= MAX_CONTROL_TTL_MS)
            control.allowedActions.forEach { action ->
                if (action == "CONFIRM_PREPARATION_AND_START") {
                    require(body.phase == WearablePhase.PREPARING && !body.firstActionTruncated && body.firstAction?.let(::validActionText) == true && control.expectedFirstAction == body.firstAction && control.activeSegmentId == null && body.currentPage != null)
                } else {
                    require(body.phase == WearablePhase.ACTIVE && control.activeSegmentId != null && control.expectedFirstAction == null)
                    require(if (action == "START_BREAK") body.segmentType in setOf("FOCUS", "DEEP_FOCUS", "UNMONITORED") else body.segmentType == "BREAK")
                }
            }
        }
        require(body.phase == WearablePhase.ACTIVE || control.activeSegmentId == null)
    }
    private fun validateOutcome(action: String, outcome: WearableCommandOutcome) {
        require(action in ACTIONS && outcome.code in RESULT_CODES && (outcome.sessionId == null || id.matches(outcome.sessionId)))
        val committed = outcome.code in setOf("APPLIED", "ALREADY_APPLIED")
        require(!committed || outcome.sessionId != null)
        require(!outcome.monitorDegraded || committed && action == "CONFIRM_PREPARATION_AND_START" && outcome.sessionId != null)
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
    private fun checkJsonKeys(text: String, maximumDepth: Int = 4) {
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
            require(depth <= maximumDepth); whitespace(); require(offset < text.length)
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
