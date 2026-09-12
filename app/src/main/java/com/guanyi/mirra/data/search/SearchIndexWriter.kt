package com.guanyi.mirra.data.search

import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.SearchFtsEntity
import com.guanyi.mirra.domain.SearchEngine

enum class SearchDocumentType { NOTE, LEARNING_ITEM, TOPIC, SESSION }

class SearchIndexWriter(
    private val database: MirraDatabase,
    private val engine: SearchEngine,
) {
    suspend fun reindexLearningItem(itemId: String) {
        val type = SearchDocumentType.LEARNING_ITEM.name
        database.searchFtsDao().delete(type, itemId)
        val item = database.learningItemDao().get(itemId) ?: return
        database.searchFtsDao().insert(engine.buildDocument(listOf(item.name)).toEntity(type, item.id))
    }

    suspend fun reindexNote(noteId: String) {
        val type = SearchDocumentType.NOTE.name
        database.searchFtsDao().delete(type, noteId)
        val note = database.noteDao().get(noteId) ?: return
        val captions = database.imageAssetDao().listForNote(noteId).map { it.caption }
        database.searchFtsDao().insert(
            engine.buildDocument(listOf(note.content) + captions).toEntity(type, note.id),
        )
    }

    suspend fun reindexTopic(topicId: String) {
        val type = SearchDocumentType.TOPIC.name
        database.searchFtsDao().delete(type, topicId)
        val topic = database.topicDao().get(topicId) ?: return
        val document = engine.buildDocument(listOf(topic.name))
        database.searchFtsDao().insert(document.toEntity(type, topic.id))
    }

    suspend fun remove(type: SearchDocumentType, id: String) {
        database.searchFtsDao().delete(type.name, id)
    }

    suspend fun reindexSession(sessionId: String) {
        val type = SearchDocumentType.SESSION.name
        database.searchFtsDao().delete(type, sessionId)
        val session = database.sessionDao().get(sessionId) ?: return
        val summary = session.generatedSummary?.trim()?.takeIf(String::isNotEmpty) ?: return
        database.searchFtsDao().insert(engine.buildDocument(listOf(summary)).toEntity(type, session.id))
    }

    internal fun com.guanyi.mirra.domain.BuiltSearchDocument.toEntity(type: String, id: String) = SearchFtsEntity(
        entityType = type,
        entityId = id,
        searchableText = searchableText,
        normalizedTokens = normalizedTokens,
    )
}
