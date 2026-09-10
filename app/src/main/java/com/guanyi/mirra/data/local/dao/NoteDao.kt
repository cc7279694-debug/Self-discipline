package com.guanyi.mirra.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.guanyi.mirra.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Upsert suspend fun upsert(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE sessionId = :sessionId ORDER BY createdAt, id")
    fun observeForSession(sessionId: String): Flow<List<NoteEntity>>

    @Query("SELECT COUNT(*) FROM notes WHERE sessionId = :sessionId AND content != ''")
    suspend fun countForSession(sessionId: String): Int
}
