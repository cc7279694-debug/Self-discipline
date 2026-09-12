package com.guanyi.mirra.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.guanyi.mirra.data.local.entity.SearchFtsEntity
import com.guanyi.mirra.data.local.model.SearchDocumentRow
import com.guanyi.mirra.data.local.model.SearchHitRow

@Dao
interface SearchFtsDao {
    @Insert suspend fun insert(document: SearchFtsEntity)

    @Insert suspend fun insertAll(documents: List<SearchFtsEntity>)

    @Query("DELETE FROM search_fts WHERE entityType = :type AND entityId = :id")
    suspend fun delete(type: String, id: String): Int

    @Query("DELETE FROM search_fts")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM search_fts")
    suspend fun count(): Int

    @Query("SELECT entityType, entityId, searchableText, normalizedTokens FROM search_fts")
    suspend fun dump(): List<SearchDocumentRow>

    @Query("""
        SELECT entityType, entityId, searchableText,
            CASE entityType
                WHEN 'NOTE' THEN (SELECT updatedAt FROM notes WHERE id = entityId)
                WHEN 'LEARNING_ITEM' THEN (SELECT updatedAt FROM learning_items WHERE id = entityId)
                WHEN 'TOPIC' THEN (SELECT createdAt FROM topics WHERE id = entityId)
                WHEN 'SESSION' THEN (SELECT COALESCE(endedAt, startedAt) FROM study_sessions WHERE id = entityId)
            END AS sourceTimestamp
        FROM search_fts
        WHERE search_fts MATCH :matchQuery
        ORDER BY sourceTimestamp DESC, entityType ASC, entityId ASC
        LIMIT :limit
    """)
    suspend fun search(matchQuery: String, limit: Int): List<SearchHitRow>
}
