package com.guanyi.mirra.domain.wearable

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Raw wrist facts stay separate from phone Session/monitored focus authority. */
data class WearableLocalRecord(
    val installationId: String, val sessionId: String, val digest: String, val bytes: Int,
    val canonical: String, val session: JsonObject, val bookId: String, val bookTitle: String,
    val firstActionId: String, val firstActionText: String, val startPage: Int?, val endPage: Int?,
    val startedAtWallMs: Long?, val finishedAtWallMs: Long?,
    val referenceFocusMs: Long?, val referenceBreakMs: Long?, val timeUncertain: Boolean,
) {
    companion object {
        const val MAX_BYTES = 8_192
        private const val MAX_SAFE = 9_007_199_254_740_991L
        private const val MAX_GAP = 21_600_000L
        private val safeInteger = Regex("^-?(0|[1-9][0-9]{0,15})$")
        private val digestPattern = Regex("^[0-9a-f]{64}$")
        private val identifier = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val entityIdentifier = Regex("^[A-Za-z0-9_-]{1,48}$")
        private val observationKeys = listOf("wallMs", "ownerId", "monotonicMs", "monotonicVerified")
        private val sessionKeys = listOf("id", "ordinal", "bookSnapshot", "firstActionSnapshot", "confirmedAt",
            "startAt", "intervals", "finishedAt", "endPage")
        private data class Clock(val wall: Long?, val owner: String, val monotonic: Double?, val verified: Boolean)

        fun parseCanonical(raw: String): WearableLocalRecord = parse(raw, sha256(raw))

        /** Hash the original UTF-8, never a DTO's reserialized Double or transformed facts. */
        fun parse(raw: String, digest: String): WearableLocalRecord {
            require(validUnicode(raw) && digestPattern.matches(digest)) { "INVALID_RECORD" }
            val bytes = raw.toByteArray(Charsets.UTF_8)
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES && sha256(raw) == digest) { "INVALID_RECORD" }
            val tree = WearableProtocol.decodeObject(bytes, MAX_BYTES, 6)
            fields(tree, listOf("schemaVersion", "installationId", "session"))
            require(integer(tree, "schemaVersion") == 1L)
            val installation = string(tree, "installationId").also { require(identifier.matches(it)) }
            val session = tree.getValue("session").jsonObject
            fields(session, sessionKeys)
            val ordinal = integer(session, "ordinal").also { require(it in 1..MAX_SAFE) }
            val sessionId = string(session, "id")
            require(sessionId == installation + ":" + ordinal.toString())
            val book = session.getValue("bookSnapshot").jsonObject
            fields(book, listOf("id", "title", "startPage"))
            val bookId = string(book, "id").also { require(entityIdentifier.matches(it)) }
            val bookTitle = text(book, "title", 120)
            val startPage = page(book, "startPage")
            val action = session.getValue("firstActionSnapshot").jsonObject
            fields(action, listOf("id", "text"))
            val actionId = string(action, "id").also { require(entityIdentifier.matches(it)) }
            val actionText = text(action, "text", 160)
            val confirmedTree = session.getValue("confirmedAt").jsonObject
            val startTree = session.getValue("startAt").jsonObject
            clock(confirmedTree); val start = clock(startTree)
            require(confirmedTree == startTree)
            val intervals = session.getValue("intervals").jsonArray
            require(intervals.size in 1..32)
            var previousEnd: JsonObject? = null
            var focus = 0L; var breaks = 0L; var unknown = false; var uncertain = false
            intervals.forEachIndexed { index, element ->
                val interval = element.jsonObject
                fields(interval, listOf("kind", "start", "end", "plannedMinutes"))
                val kind = string(interval, "kind")
                require(kind == if (index % 2 == 0) "FOCUS" else "BREAK")
                if (kind == "FOCUS") require(interval.getValue("plannedMinutes") == JsonNull)
                else require(integer(interval, "plannedMinutes") in listOf(5L, 10L))
                val fromTree = interval.getValue("start").jsonObject
                val untilTree = interval.getValue("end").jsonObject // Closed ends are mandatory.
                require(fromTree == if (index == 0) startTree else previousEnd)
                val from = clock(fromTree); val until = clock(untilTree)
                val observed = if (from.wall == null || until.wall == null) null else until.wall - from.wall
                var validReference = observed != null && observed in 0L..MAX_GAP
                val sameOwner = from.owner == until.owner
                if (!sameOwner || !from.verified || !until.verified) uncertain = true
                if (sameOwner && from.verified && until.verified && observed != null) {
                    val monotonic = checkNotNull(until.monotonic) - checkNotNull(from.monotonic)
                    if (monotonic < 0 || monotonic > MAX_GAP || kotlin.math.abs(monotonic - observed.toDouble()) > 2_000) validReference = false
                }
                if (!validReference) { unknown = true; uncertain = true }
                else if (kind == "FOCUS") focus += checkNotNull(observed) else breaks += checkNotNull(observed)
                previousEnd = untilTree
            }
            val finishTree = session.getValue("finishedAt").jsonObject
            val finish = clock(finishTree)
            require(finishTree == previousEnd)
            val endPage = page(session, "endPage")
            // Parsed JsonPrimitive numbers keep their original lexemes. No Double DTO is
            // serialized here; canonical string/escaping/order is checked on the raw tree.
            require(tree.toString() == raw) { "RECORD_NONCANONICAL" }
            return WearableLocalRecord(installation, sessionId, digest, bytes.size, raw, session,
                bookId, bookTitle, actionId, actionText, startPage, endPage, start.wall, finish.wall,
                if (unknown) null else focus, if (unknown) null else breaks, uncertain)
        }
        fun sha256(raw: String): String {
            require(validUnicode(raw)) { "INVALID_RECORD" }
            return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        }
        private fun fields(value: JsonObject, names: List<String>) { require(value.keys.toList() == names) }
        private fun string(value: JsonObject, key: String): String = value.getValue(key).jsonPrimitive.let {
            require(it.isString && validUnicode(it.content)); it.content
        }
        private fun text(value: JsonObject, key: String, limit: Int): String = string(value, key).also {
            require(it.codePointCount(0, it.length) in 1..limit && it.none { char -> char.code in 0..31 || char.code == 127 })
        }
        private fun integer(value: JsonObject, key: String): Long = value.getValue(key).jsonPrimitive.let {
            require(!it.isString && safeInteger.matches(it.content) && it.content != "-0")
            checkNotNull(it.longOrNull).also { number -> require(number in -MAX_SAFE..MAX_SAFE) }
        }
        private fun page(value: JsonObject, key: String): Int? = if (value.getValue(key) == JsonNull) null
            else integer(value, key).also { require(it in 1..999_999) }.toInt()
        private fun clock(value: JsonObject): Clock {
            fields(value, observationKeys)
            val wall = if (value.getValue("wallMs") == JsonNull) null else integer(value, "wallMs")
            val owner = string(value, "ownerId").also { require(identifier.matches(it)) }
            val monotonic = if (value.getValue("monotonicMs") == JsonNull) null else value.getValue("monotonicMs").jsonPrimitive.let {
                require(!it.isString && canonicalNonnegativeNumber(it.content))
                checkNotNull(it.doubleOrNull).also { number -> require(number.isFinite() && number >= 0 && number <= MAX_SAFE.toDouble()) }
            }
            val token = value.getValue("monotonicVerified").jsonPrimitive
            require(!token.isString && token.booleanOrNull != null)
            val verified = checkNotNull(token.booleanOrNull)
            require(!verified || monotonic != null)
            return Clock(wall, owner, monotonic, verified)
        }
        private fun canonicalNonnegativeNumber(raw: String): Boolean {
            // ECMAScript emits decimal for [1e-6,1e21), scientific below 1e-6.
            // Keep accepted fraction bytes; never replace them with JVM Double formatting.
            if (Regex("^(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?$").matches(raw)) {
                val number = raw.toDoubleOrNull() ?: return false
                return number == 0.0 && raw == "0" || number >= 0.000001 && number < 1e21
            }
            return Regex("^[1-9](\\.[0-9]*[1-9])?e-([7-9]|[1-9][0-9]+)$").matches(raw) && raw.toDoubleOrNull()?.let { it > 0 && it < 0.000001 } == true
        }
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
}
