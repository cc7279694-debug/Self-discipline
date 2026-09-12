package com.guanyi.mirra.data.search

import androidx.room.withTransaction
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.SearchFtsEntity
import com.guanyi.mirra.domain.SearchEngine

data class SearchIndexHealth(
    val rebuilt: Boolean,
    val expectedCount: Int,
    val actualCount: Int,
)

class SearchIndexRebuilder(
    private val database: MirraDatabase,
    private val engine: SearchEngine,
) {
    /**
     * A count comparison is deliberately only a lightweight startup health check.
     * Equal counts do not prove that derived index content is current; explicit
     * rebuild remains the repair path for stale content.
     */
    suspend fun ensureConsistent(): SearchIndexHealth {
        val expected = expectedDocumentCount()
        val actual = database.searchFtsDao().count()
        if (expected == actual) return SearchIndexHealth(false, expected, actual)
        rebuild()
        return SearchIndexHealth(true, expected, actual)
    }

    suspend fun rebuild() {
        database.withTransaction {
            val imagesByNote = database.imageAssetDao().listAll().groupBy { it.noteId }
            val documents = buildList {
                database.learningItemDao().listAll().forEach { item ->
                    add(document(SearchDocumentType.LEARNING_ITEM, item.id, listOf(item.name)))
                }
                database.noteDao().listAll().forEach { note ->
                    add(document(SearchDocumentType.NOTE, note.id, listOf(note.content) + imagesByNote[note.id].orEmpty().map { it.caption }))
                }
                database.topicDao().listAll().forEach { topic ->
                    add(document(SearchDocumentType.TOPIC, topic.id, listOf(topic.name)))
                }
                database.sessionDao().listAll().forEach { session ->
                    session.generatedSummary?.trim()?.takeIf(String::isNotEmpty)?.let { summary ->
                        add(document(SearchDocumentType.SESSION, session.id, listOf(summary)))
                    }
                }
            }
            database.searchFtsDao().clear()
            if (documents.isNotEmpty()) database.searchFtsDao().insertAll(documents)
        }
    }

    private suspend fun expectedDocumentCount(): Int =
        database.learningItemDao().listAll().size +
            database.noteDao().listAll().size +
            database.topicDao().listAll().size +
            database.sessionDao().listAll().count { !it.generatedSummary.isNullOrBlank() }

    private fun document(type: SearchDocumentType, id: String, parts: List<String?>): SearchFtsEntity {
        val built = engine.buildDocument(parts)
        return SearchFtsEntity(type.name, id, built.searchableText, built.normalizedTokens)
    }
}
