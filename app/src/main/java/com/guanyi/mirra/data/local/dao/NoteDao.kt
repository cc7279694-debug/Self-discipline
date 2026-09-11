package com.guanyi.mirra.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.guanyi.mirra.data.local.entity.NoteEntity
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.local.model.NoteListItem
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Insert suspend fun insert(note: NoteEntity)

    @Upsert suspend fun upsert(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: String): Flow<NoteEntity?>

    @Query("""
        SELECT notes.*, learning_items.name AS learningItemName
        FROM notes
        INNER JOIN learning_items ON learning_items.id = notes.learningItemId
        WHERE (:semanticType IS NULL OR notes.semanticType = :semanticType)
          AND (:learningItemId IS NULL OR notes.learningItemId = :learningItemId)
        ORDER BY notes.createdAt DESC, notes.id DESC
    """)
    fun observeAll(
        semanticType: NoteSemanticType?,
        learningItemId: String?,
    ): Flow<List<NoteListItem>>

    @Query("SELECT * FROM notes WHERE sessionId = :sessionId ORDER BY createdAt, id")
    fun observeForSession(sessionId: String): Flow<List<NoteEntity>>

    @Query("SELECT COUNT(*) FROM notes WHERE sessionId = :sessionId AND content != ''")
    suspend fun countForSession(sessionId: String): Int

    @Query("""
        UPDATE notes
        SET content = :content, semanticType = :semanticType, pageNumber = :pageNumber, updatedAt = :updatedAt
        WHERE id = :id
    """)
    suspend fun updateContent(
        id: String,
        content: String,
        semanticType: NoteSemanticType,
        pageNumber: Int?,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String): Int
}
