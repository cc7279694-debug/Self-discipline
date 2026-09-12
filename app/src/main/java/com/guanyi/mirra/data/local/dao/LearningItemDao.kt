package com.guanyi.mirra.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LearningItemDao {
    @Insert suspend fun insert(item: LearningItemEntity)

    @Query("SELECT * FROM learning_items ORDER BY mainlineSlot DESC, updatedAt DESC")
    fun observeAll(): Flow<List<LearningItemEntity>>

    @Query("SELECT * FROM learning_items WHERE id = :id")
    fun observe(id: String): Flow<LearningItemEntity?>

    @Query("SELECT * FROM learning_items WHERE id = :id")
    suspend fun get(id: String): LearningItemEntity?

    @Query("SELECT * FROM learning_items WHERE mainlineSlot = 1 LIMIT 1")
    fun observeMainline(): Flow<LearningItemEntity?>

    @Query("UPDATE learning_items SET mainlineSlot = NULL, updatedAt = :updatedAt WHERE mainlineSlot IS NOT NULL")
    suspend fun clearMainline(updatedAt: Long)

    @Query("UPDATE learning_items SET mainlineSlot = 1, updatedAt = :updatedAt WHERE id = :id AND status = 'IN_PROGRESS'")
    suspend fun assignMainline(id: String, updatedAt: Long): Int

    @Query("""
        UPDATE learning_items
        SET status = 'PAUSED', mainlineSlot = NULL, completedAt = NULL, updatedAt = :updatedAt
        WHERE id = :id AND status = 'IN_PROGRESS'
    """)
    suspend fun markPaused(id: String, updatedAt: Long): Int

    @Query("""
        UPDATE learning_items
        SET status = 'IN_PROGRESS', mainlineSlot = NULL, completedAt = NULL, updatedAt = :updatedAt
        WHERE id = :id AND status = 'PAUSED'
    """)
    suspend fun markInProgress(id: String, updatedAt: Long): Int

    @Query("""
        UPDATE learning_items
        SET status = 'COMPLETED', mainlineSlot = NULL, completedAt = :completedAt, updatedAt = :completedAt
        WHERE id = :id AND status = 'IN_PROGRESS'
    """)
    suspend fun markCompleted(id: String, completedAt: Long): Int

    @Query("UPDATE learning_items SET firstAction = :firstAction, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateFirstAction(id: String, firstAction: String, updatedAt: Long): Int

    @Query("UPDATE learning_items SET currentPage = MAX(currentPage, :page), updatedAt = :updatedAt WHERE id = :id")
    suspend fun advanceProgress(id: String, page: Int, updatedAt: Long): Int
}
