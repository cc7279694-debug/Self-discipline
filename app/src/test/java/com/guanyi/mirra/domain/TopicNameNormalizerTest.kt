package com.guanyi.mirra.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TopicNameNormalizerTest {
    @Test
    fun normalizesCompatibilityCharactersAndUnicodeWhitespace() {
        assertEquals("Kotlin 2026", TopicNameNormalizer.normalize("  Ｋｏｔｌｉｎ\u3000\n ２０２６  "))
    }

    @Test
    fun comparisonUsesLocaleIndependentLowercase() {
        assertEquals("kotlin i", TopicNameNormalizer.comparisonKey("KOTLIN I"))
    }

    @Test
    fun punctuationIsPreservedWhileSeparatorsCollapse() {
        assertEquals("心理-账户", TopicNameNormalizer.normalize(" 心理-账户 "))
        assertEquals("a b", TopicNameNormalizer.normalize("a\t\n b"))
    }
}
