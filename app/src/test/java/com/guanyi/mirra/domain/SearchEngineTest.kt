package com.guanyi.mirra.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {
    private val engine: SearchEngine = DefaultSearchEngine()

    @Test
    fun createsAdjacentChineseTokensWithoutCrossingPunctuation() {
        assertEquals(listOf("心理", "理账", "账户"), SearchTextNormalizer.tokens("心理账户"))
        assertEquals(listOf("心理", "账户"), SearchTextNormalizer.tokens("心理，账户"))
    }

    @Test
    fun tokenizesEnglishNumbersAndMixedTextWithoutEmoji() {
        assertEquals(
            listOf("kotlin", "心理", "理账", "账户", "2026"),
            SearchTextNormalizer.tokens("Kotlin心理账户2026📚"),
        )
    }

    @Test
    fun singleChineseCharacterAndPunctuationProduceNoQuery() {
        assertNull(engine.buildQuery("心"))
        assertNull(engine.buildQuery("() * 📚"))
    }

    @Test
    fun reservedWordsAndUnsafeCharactersBecomeBoundQuotedTokens() {
        val query = engine.buildQuery("AND or NOT near hello* \"world\"")!!
        assertEquals(
            "\"and\" \"or\" \"not\" \"near\" \"hello\" \"world\"",
            query.expression,
        )
    }

    @Test
    fun buildsOneDocumentFromOrderedNonBlankParts() {
        val document = engine.buildDocument(listOf(" 正文心理账户 ", "", null, "图片 Caption"))
        assertEquals("正文心理账户\n图片 Caption", document.searchableText)
        assertEquals("正文 文心 心理 理账 账户 图片 caption", document.normalizedTokens)
    }

    @Test
    fun snippetIsCodePointSafeAndBounded() {
        val query = engine.buildQuery("心理")!!
        val snippet = engine.buildSnippet("📚这是很长的一段文字，核心是心理账户与选择。", query, maxChars = 12)
        assertTrue(snippet.codePointCount(0, snippet.length) <= 14)
        assertTrue(snippet.contains("心理"))
    }
}
