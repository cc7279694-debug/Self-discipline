package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.TopicEntity

data class TopicSuggestion(
    val topic: TopicEntity,
    val reason: String,
)

class TopicSuggester {
    fun suggest(
        noteContent: String,
        existingTopics: List<TopicEntity>,
        linkedTopicIds: Set<String>,
        limit: Int = 5,
    ): List<TopicSuggestion> {
        val content = TopicNameNormalizer.comparisonKey(noteContent)
        return existingTopics.asSequence()
            .filterNot { it.id in linkedTopicIds }
            .map { it to TopicNameNormalizer.comparisonKey(it.name) }
            .filter { (_, name) -> name.codePointCount(0, name.length) >= 2 && content.contains(name) }
            .sortedWith(
                compareByDescending<Pair<TopicEntity, String>> { (_, name) -> name.codePointCount(0, name.length) }
                    .thenBy { (_, name) -> name }
                    .thenBy { (topic) -> topic.id },
            )
            .take(limit.coerceAtLeast(0))
            .map { (topic) -> TopicSuggestion(topic, "笔记正文包含此 Topic") }
            .toList()
    }
}
