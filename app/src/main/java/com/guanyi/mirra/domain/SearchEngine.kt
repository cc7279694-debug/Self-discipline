package com.guanyi.mirra.domain

import java.text.Normalizer
import java.util.Locale

data class BuiltSearchDocument(
    val searchableText: String,
    val normalizedTokens: String,
)

data class MatchQuery(
    val expression: String,
    val tokens: List<String>,
)

interface SearchEngine {
    fun buildDocument(parts: List<String?>): BuiltSearchDocument
    fun buildQuery(rawQuery: String): MatchQuery?
    fun buildSnippet(searchableText: String, query: MatchQuery, maxChars: Int = 96): String
}

object SearchTextNormalizer {
    fun tokens(raw: String): List<String> {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var currentKind = Kind.NONE

        fun flush() {
            if (current.isEmpty()) return
            val value = current.toString()
            when (currentKind) {
                Kind.HAN -> {
                    val points = value.codePointValues()
                    if (points.size == 2) result += value
                    if (points.size > 2) {
                        for (index in 0 until points.lastIndex) {
                            result += buildString {
                                appendCodePoint(points[index])
                                appendCodePoint(points[index + 1])
                            }
                        }
                    }
                }
                Kind.WORD -> result += value
                Kind.NONE -> Unit
            }
            current.clear()
        }

        normalized.codePointValues().forEach { codePoint ->
            val kind = when {
                isHanCodePoint(codePoint) -> Kind.HAN
                Character.isLetterOrDigit(codePoint) -> Kind.WORD
                else -> Kind.NONE
            }
            if (kind == Kind.NONE) {
                flush()
                currentKind = Kind.NONE
            } else {
                if (kind != currentKind) flush()
                currentKind = kind
                current.appendCodePoint(codePoint)
            }
        }
        flush()
        return result
    }

    private enum class Kind { NONE, HAN, WORD }
}

class DefaultSearchEngine : SearchEngine {
    override fun buildDocument(parts: List<String?>): BuiltSearchDocument {
        val text = parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString("\n")
        return BuiltSearchDocument(text, SearchTextNormalizer.tokens(text).joinToString(" "))
    }

    override fun buildQuery(rawQuery: String): MatchQuery? {
        val tokens = SearchTextNormalizer.tokens(rawQuery)
        if (tokens.isEmpty()) return null
        return MatchQuery(
            // FTS4 treats adjacent terms as AND. Quoting every normalized token keeps
            // operators and punctuation as data while remaining portable on Android.
            expression = tokens.joinToString(" ") { token -> "\"${token.replace("\"", "\"\"")}\"" },
            tokens = tokens,
        )
    }

    override fun buildSnippet(searchableText: String, query: MatchQuery, maxChars: Int): String {
        if (searchableText.isEmpty() || maxChars <= 0) return ""
        val normalized = Normalizer.normalize(searchableText, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val firstToken = query.tokens.firstOrNull()
        val matchIndex = firstToken?.let(normalized::indexOf)?.takeIf { it >= 0 } ?: 0
        val matchCodePoint = normalized.codePointCount(0, matchIndex)
        val allPoints = searchableText.codePointValues()
        if (allPoints.size <= maxChars) return searchableText
        val start = (matchCodePoint - maxChars / 3).coerceIn(0, (allPoints.size - maxChars).coerceAtLeast(0))
        val end = (start + maxChars).coerceAtMost(allPoints.size)
        val core = buildString {
            for (index in start until end) appendCodePoint(allPoints[index])
        }
        return buildString {
            if (start > 0) append('…')
            append(core)
            if (end < allPoints.size) append('…')
        }
    }
}

internal fun String.codePointValues(): IntArray {
    val result = IntArray(codePointCount(0, length))
    var charIndex = 0
    var outputIndex = 0
    while (charIndex < length) {
        val point = Character.codePointAt(this, charIndex)
        result[outputIndex++] = point
        charIndex += Character.charCount(point)
    }
    return result
}

internal fun isHanCodePoint(value: Int): Boolean =
    value in 0x3400..0x4DBF ||
        value in 0x4E00..0x9FFF ||
        value in 0xF900..0xFAFF ||
        value in 0x20000..0x2EBEF ||
        value in 0x30000..0x323AF
