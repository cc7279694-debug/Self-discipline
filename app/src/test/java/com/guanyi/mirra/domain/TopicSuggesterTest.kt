package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.TopicEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TopicSuggesterTest {
    private val suggester = TopicSuggester()

    @Test
    fun suggestsOnlyExplicitUnlinkedMultiCharacterNames() {
        val topics = listOf(topic("1", "心理"), topic("2", "心理账户"), topic("3", "心"), topic("4", "Kotlin"))

        val result = suggester.suggest("这段讨论心理账户，也提到 KOTLIN。", topics, setOf("1"))

        assertEquals(listOf("Kotlin", "心理账户"), result.map { it.topic.name })
    }

    @Test
    fun usesLongestThenStableNameOrderAndLimitsResults() {
        val topics = listOf("甲乙丙", "乙丙", "甲乙", "alpha", "beta", "gamma").mapIndexed { index, name ->
            topic(index.toString(), name)
        }
        val result = suggester.suggest("甲乙丙 alpha beta gamma", topics, emptySet(), limit = 5)

        assertEquals(listOf("alpha", "gamma", "beta", "甲乙丙", "乙丙"), result.map { it.topic.name })
    }

    private fun topic(id: String, name: String) = TopicEntity(id, name, 1L)
}
