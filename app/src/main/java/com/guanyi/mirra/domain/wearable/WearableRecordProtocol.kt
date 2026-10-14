package com.guanyi.mirra.domain.wearable

import kotlinx.serialization.json.*

data class WearableRecordFrame(
    val type: String, val clientInstanceId: String, val requestId: String, val streamId: String,
    val sourceInstallationId: String, val localSessionId: String, val recordDigest: String, val totalBytes: Int,
    val schemaVersion: Int? = null, val partCount: Int? = null, val index: Int? = null,
    val data: String? = null, val checksum: String? = null, val code: String? = null,
)

/** v4 adds a bounded full-record transfer to the same application-owned channel. */
object WearableRecordProtocol {
    const val VERSION = 4
    const val MAX_RECORD_BYTES = 8_192
    const val MAX_PART_BYTES = 1_024
    const val MAX_PARTS = 8
    const val DEADLINE_MS = 30_000L
    val TYPES = setOf("LOCAL_RECORD_OFFER", "LOCAL_RECORD_NEED", "LOCAL_RECORD_PART", "LOCAL_RECORD_COMMIT", "LOCAL_RECORD_ACK", "LOCAL_RECORD_ERROR")
    val ERRORS = setOf("DISABLED", "BUSY", "INVALID_RECORD", "CONFLICT", "TIMEOUT", "DB_FAILED", "RESULT_UNKNOWN", "RECORD_TOO_LARGE", "SOURCE_UNAVAILABLE", "PERSIST_FAILED")
    private val common = setOf("protocolVersion", "type", "clientInstanceId", "requestId", "streamId", "sourceInstallationId", "localSessionId", "recordDigest", "totalBytes")
    private val wireId = Regex("^[A-Za-z0-9._:-]{1,80}$")
    private val installationId = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val ordinal = Regex("^[1-9][0-9]{0,15}$")
    private val digest = Regex("^[0-9a-f]{64}$")
    private val crc = Regex("^[0-9a-f]{8}$")
    fun validateMessage(tree: JsonObject) { decodeFrame(tree) }
    fun decodeFrame(tree: JsonObject): WearableRecordFrame {
        fun text(key: String) = tree.getValue(key).jsonPrimitive.let { require(it.isString && validUnicode(it.content)); it.content }
        fun number(key: String) = tree.getValue(key).jsonPrimitive.let { require(!it.isString); checkNotNull(it.intOrNull) }
        require(number("protocolVersion") == VERSION)
        val type = text("type")
        val extra = when (type) {
            "LOCAL_RECORD_OFFER" -> setOf("schemaVersion", "partCount")
            "LOCAL_RECORD_NEED", "LOCAL_RECORD_ACK", "LOCAL_RECORD_ERROR" -> setOf("code")
            "LOCAL_RECORD_PART" -> setOf("index", "data", "checksum")
            "LOCAL_RECORD_COMMIT" -> emptySet()
            else -> error("RECORD_TYPE")
        }
        require(tree.keys == common + extra)
        val client = text("clientInstanceId").also { require(wireId.matches(it)) }
        val request = text("requestId").also { require(wireId.matches(it)) }
        val stream = text("streamId").also { require(wireId.matches(it)) }
        val installation = text("sourceInstallationId").also { require(installationId.matches(it)) }
        val session = text("localSessionId")
        require(session.startsWith(installation + ":"))
        val suffix = session.substring(installation.length + 1)
        val ordinalNumber = suffix.toLongOrNull()
        require(ordinal.matches(suffix) && ordinalNumber != null && ordinalNumber in 1..WearableProjection.MAX_SAFE_INTEGER && ordinalNumber.toString() == suffix)
        val hash = text("recordDigest").also { require(digest.matches(it)) }
        val bytes = number("totalBytes").also { require(it in 1..MAX_RECORD_BYTES) }
        var schema: Int? = null; var count: Int? = null; var index: Int? = null
        var data: String? = null; var checksum: String? = null; var code: String? = null
        when (type) {
            "LOCAL_RECORD_OFFER" -> {
                schema = number("schemaVersion").also { require(it == 1) }
                count = number("partCount").also { require(it in 1..MAX_PARTS && it >= (bytes + MAX_PART_BYTES - 1) / MAX_PART_BYTES && it <= bytes) }
            }
            "LOCAL_RECORD_PART" -> {
                index = number("index").also { require(it in 0 until MAX_PARTS) }
                data = text("data").also { require(it.toByteArray(Charsets.UTF_8).size in 1..MAX_PART_BYTES) }
                checksum = text("checksum").also { require(crc.matches(it) && WearableCatalogProtocol.checksum(checkNotNull(data).toByteArray(Charsets.UTF_8)) == it) }
            }
            "LOCAL_RECORD_NEED" -> code = text("code").also { require(it == "NEED") }
            "LOCAL_RECORD_ACK" -> code = text("code").also { require(it in setOf("IMPORTED", "ALREADY_IMPORTED")) }
            "LOCAL_RECORD_ERROR" -> code = text("code").also { require(it in ERRORS) }
        }
        return WearableRecordFrame(type, client, request, stream, installation, session, hash, bytes, schema, count, index, data, checksum, code)
    }
    fun decodeRequest(tree: JsonObject): WearableRequest {
        val value = decodeFrame(tree)
        require(value.type in setOf("LOCAL_RECORD_OFFER", "LOCAL_RECORD_PART", "LOCAL_RECORD_COMMIT", "LOCAL_RECORD_ERROR"))
        return WearableRequest(VERSION, value.type, value.clientInstanceId, value.requestId, value.streamId, record = value)
    }
    fun encode(value: WearableRecordFrame): ByteArray {
        val tree = buildJsonObject {
            put("protocolVersion", VERSION); put("type", value.type); put("clientInstanceId", value.clientInstanceId)
            put("requestId", value.requestId); put("streamId", value.streamId); put("sourceInstallationId", value.sourceInstallationId)
            put("localSessionId", value.localSessionId); put("recordDigest", value.recordDigest); put("totalBytes", value.totalBytes)
            when (value.type) {
                "LOCAL_RECORD_OFFER" -> { put("schemaVersion", checkNotNull(value.schemaVersion)); put("partCount", checkNotNull(value.partCount)) }
                "LOCAL_RECORD_PART" -> { put("index", checkNotNull(value.index)); put("data", checkNotNull(value.data)); put("checksum", checkNotNull(value.checksum)) }
                "LOCAL_RECORD_NEED", "LOCAL_RECORD_ACK", "LOCAL_RECORD_ERROR" -> put("code", checkNotNull(value.code))
            }
        }
        validateMessage(tree)
        return tree.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= WearableProtocol.MAX_BYTES) }
    }
    fun reply(request: WearableRequest, type: String, code: String? = null): ByteArray {
        val frame = checkNotNull(request.record)
        require(request.protocolVersion == VERSION && request.clientInstanceId == frame.clientInstanceId && request.requestId == frame.requestId && request.streamId == frame.streamId)
        require(type in setOf("LOCAL_RECORD_NEED", "LOCAL_RECORD_ACK", "LOCAL_RECORD_ERROR"))
        return encode(frame.copy(type = type, schemaVersion = null, partCount = null, index = null, data = null, checksum = null, code = code))
    }
    fun need(request: WearableRequest): ByteArray = reply(request, "LOCAL_RECORD_NEED", "NEED")
    fun ack(request: WearableRequest, alreadyImported: Boolean): ByteArray = reply(request, "LOCAL_RECORD_ACK", if (alreadyImported) "ALREADY_IMPORTED" else "IMPORTED")
    fun error(request: WearableRequest, code: String): ByteArray = reply(request, "LOCAL_RECORD_ERROR", code)
    private fun validUnicode(raw: String): Boolean {
        var at = 0
        while (at < raw.length) {
            val char = raw[at++]
            if (char.isHighSurrogate()) { if (at == raw.length || !raw[at++].isLowSurrogate()) return false }
            else if (char.isLowSurrogate()) return false
        }
        return true
    }
}
