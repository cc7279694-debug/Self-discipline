package com.guanyi.mirra.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert suspend fun insert(session: StudySessionEntity)

    @Query("SELECT * FROM study_sessions WHERE id = :id")
    suspend fun get(id: String): StudySessionEntity?

    @Query("SELECT * FROM study_sessions WHERE id = :id")
    fun observe(id: String): Flow<StudySessionEntity?>

    @Query("SELECT * FROM study_sessions WHERE activeSlot = 1 LIMIT 1")
    suspend fun getActive(): StudySessionEntity?

    @Query("SELECT * FROM study_sessions WHERE activeSlot = 1 LIMIT 1")
    fun observeActive(): Flow<StudySessionEntity?>

    @Query("SELECT * FROM study_sessions WHERE learningItemId = :learningItemId AND activeSlot = 1 LIMIT 1")
    suspend fun getActiveForLearningItem(learningItemId: String): StudySessionEntity?

    @Query("SELECT * FROM study_sessions WHERE learningItemId = :learningItemId AND generatedSummary IS NOT NULL ORDER BY endedAt DESC LIMIT 1")
    fun observeLatestSummaryForItem(learningItemId: String): Flow<StudySessionEntity?>

    @Query("UPDATE study_sessions SET currentPage = MAX(currentPage, :page) WHERE id = :id AND activeSlot = 1")
    suspend fun advanceCurrentPage(id: String, page: Int): Int

    @Query("""
        UPDATE study_sessions
        SET endedAt = :endedAt, currentPage = MAX(currentPage, :endPage),
            endPage = MAX(currentPage, :endPage),
            endType = :endType, generatedSummary = :summary, activeSlot = NULL
        WHERE id = :id AND activeSlot = 1
    """)
    suspend fun finish(
        id: String,
        endedAt: Long,
        endPage: Int,
        endType: SessionEndType,
        summary: String?,
    ): Int
}
