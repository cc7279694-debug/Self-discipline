package com.guanyi.mirra.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.local.model.RecentReadingSnapshot
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

    @Query("""
        SELECT s.id AS sessionId, s.learningItemId AS learningItemId,
            s.startedAt AS startedAt, s.endedAt AS endedAt,
            MAX(0, s.endedAt - s.startedAt) AS durationMillis,
            COUNT(n.id) AS noteCount
        FROM study_sessions s
        LEFT JOIN notes n ON n.sessionId = s.id
        WHERE s.learningItemId = :learningItemId
            AND s.endType = 'NORMAL'
            AND s.endedAt IS NOT NULL
        GROUP BY s.id
        ORDER BY s.endedAt DESC, s.id DESC
        LIMIT 1
    """)
    fun observeLatestNormalReading(learningItemId: String): Flow<RecentReadingSnapshot?>

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
