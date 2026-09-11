package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.NoteSemanticType
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteTypeSuggesterTest {
    private val suggester = RuleBasedNoteTypeSuggester()

    @Test
    fun questionMarksSuggestQuestion() {
        assertEquals(NoteSemanticType.QUESTION, suggester.suggest("为什么会这样？"))
        assertEquals(NoteSemanticType.QUESTION, suggester.suggest("Why?  "))
    }

    @Test
    fun explicitSummaryPrefixSuggestsSummary() {
        assertEquals(NoteSemanticType.SUMMARY, suggester.suggest("总结：这一章讨论选择"))
        assertEquals(NoteSemanticType.SUMMARY, suggester.suggest("总结: 这一章讨论选择"))
    }

    @Test
    fun uncertainContentDefaultsToUnderstandingAndNeverGuessesQuote() {
        assertEquals(NoteSemanticType.UNDERSTANDING, suggester.suggest("损失厌恶比收益更强"))
    }
}
