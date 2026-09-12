package com.guanyi.mirra.data.repository

import android.database.sqlite.SQLiteException
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.search.SearchDocumentType
import com.guanyi.mirra.data.search.SearchIndexRebuilder
import com.guanyi.mirra.data.local.model.SearchHitRow
import com.guanyi.mirra.domain.MatchQuery
import com.guanyi.mirra.domain.SearchEngine
import com.guanyi.mirra.domain.codePointValues
import com.guanyi.mirra.domain.isHanCodePoint

data class SearchResult(
    val type: SearchDocumentType,
    val id: String,
    val title: String,
    val snippet: String,
    val learningItemName: String? = null,
    val pageNumber: Int? = null,
    val timestamp: Long? = null,
)

data class SearchResponse(
    val items: List<SearchResult>,
    val hint: String? = null,
)

interface SearchRepository {
    suspend fun search(rawQuery: String, limit: Int = 60): SearchResponse
    suspend fun rebuildIndex()
}

class DefaultSearchRepository(
    private val database: MirraDatabase,
    private val engine: SearchEngine,
    private val rebuilder: SearchIndexRebuilder,
    private val executeSearch: suspend (String, Int) -> List<SearchHitRow> = { query, limit ->
        database.searchFtsDao().search(query, limit)
    },
    private val repairIndex: suspend () -> Unit = { rebuilder.rebuild() },
) : SearchRepository {
    override suspend fun search(rawQuery: String, limit: Int): SearchResponse {
        val query = engine.buildQuery(rawQuery)
            ?: return SearchResponse(emptyList(), singleHanHint(rawQuery))
        return try {
            searchOnce(query, limit, repairOrphans = true)
        } catch (_: SQLiteException) {
            repairIndex()
            searchOnce(query, limit, repairOrphans = false)
        }
    }

    override suspend fun rebuildIndex() = rebuilder.rebuild()

    private suspend fun searchOnce(
        query: MatchQuery,
        limit: Int,
        repairOrphans: Boolean,
    ): SearchResponse {
        val hits = executeSearch(query.expression, limit.coerceIn(1, 60))
            .distinctBy { it.entityType to it.entityId }
        val idsByType = hits.groupBy { it.entityType }.mapValues { (_, rows) -> rows.map { it.entityId } }
        val notesById = idsByType[SearchDocumentType.NOTE.name].orEmpty().let { ids ->
            if (ids.isEmpty()) emptyMap() else database.noteDao().getByIds(ids).associateBy { it.id }
        }
        val topicsById = idsByType[SearchDocumentType.TOPIC.name].orEmpty().let { ids ->
            if (ids.isEmpty()) emptyMap() else database.topicDao().getByIds(ids).associateBy { it.id }
        }
        val sessionsById = idsByType[SearchDocumentType.SESSION.name].orEmpty().let { ids ->
            if (ids.isEmpty()) emptyMap() else database.sessionDao().getByIds(ids).associateBy { it.id }
        }
        val requestedItemIds = buildSet {
            addAll(idsByType[SearchDocumentType.LEARNING_ITEM.name].orEmpty())
            addAll(notesById.values.map { it.learningItemId })
            addAll(sessionsById.values.map { it.learningItemId })
        }.toList()
        val itemsById = if (requestedItemIds.isEmpty()) emptyMap() else {
            database.learningItemDao().getByIds(requestedItemIds).associateBy { it.id }
        }
        var orphanFound = false
        val hydrated = hits.mapNotNull { hit ->
            val type = runCatching { SearchDocumentType.valueOf(hit.entityType) }.getOrNull()
            val result = when (type) {
                SearchDocumentType.NOTE -> notesById[hit.entityId]?.let { note ->
                    SearchResult(
                        type = type,
                        id = note.id,
                        title = itemsById[note.learningItemId]?.name ?: "笔记",
                        snippet = engine.buildSnippet(hit.searchableText, query),
                        learningItemName = itemsById[note.learningItemId]?.name,
                        pageNumber = note.pageNumber,
                        timestamp = note.updatedAt,
                    )
                }
                SearchDocumentType.LEARNING_ITEM -> itemsById[hit.entityId]?.let { item ->
                    SearchResult(type, item.id, item.name, item.name, timestamp = item.updatedAt)
                }
                SearchDocumentType.TOPIC -> topicsById[hit.entityId]?.let { topic ->
                    SearchResult(type, topic.id, topic.name, topic.name, timestamp = topic.createdAt)
                }
                SearchDocumentType.SESSION -> sessionsById[hit.entityId]?.let { session ->
                    val itemName = itemsById[session.learningItemId]?.name
                    SearchResult(
                        type,
                        session.id,
                        itemName ?: "阅读总结",
                        engine.buildSnippet(hit.searchableText, query),
                        learningItemName = itemName,
                        timestamp = session.endedAt ?: session.startedAt,
                    )
                }
                null -> null
            }
            if (result == null) orphanFound = true
            result
        }
        if (orphanFound && repairOrphans) {
            rebuilder.rebuild()
            return searchOnce(query, limit, repairOrphans = false)
        }
        return SearchResponse(hydrated)
    }

    private fun singleHanHint(raw: String): String? {
        val codePoints = raw.trim().codePointValues()
        return if (
            codePoints.size == 1 &&
            isHanCodePoint(codePoints[0])
        ) "请输入至少两个连续中文字符" else null
    }
}
