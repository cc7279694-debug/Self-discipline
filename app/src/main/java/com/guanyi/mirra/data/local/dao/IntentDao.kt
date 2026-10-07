package com.guanyi.mirra.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IntentDao {
    @Query("""
        SELECT i.*, s.id AS session_id, s.learningItemId AS session_learningItemId,
            s.intentId AS session_intentId, s.startedAt AS session_startedAt,
            s.stableStartedAt AS session_stableStartedAt, s.endedAt AS session_endedAt,
            s.startPage AS session_startPage, s.currentPage AS session_currentPage,
            s.endPage AS session_endPage, s.endType AS session_endType,
            NULL AS session_generatedSummary, s.activeSlot AS session_activeSlot
        FROM study_intents i LEFT JOIN study_sessions s ON s.intentId = i.id
        WHERE (:fromInclusive IS NULL OR i.createdAt >= :fromInclusive)
            AND i.createdAt <= :toInclusive
            AND (:cursorCreatedAt IS NULL OR i.createdAt < :cursorCreatedAt
                OR (i.createdAt = :cursorCreatedAt AND i.id < :cursorId))
        ORDER BY i.createdAt DESC, i.id DESC LIMIT 801
    """)
    suspend fun loadTrendPage(fromInclusive: Long?, toInclusive: Long,
        cursorCreatedAt: Long?, cursorId: String?): List<com.guanyi.mirra.data.local.model.IntentTrendRow>

    @Insert suspend fun insert(intent: StudyIntentEntity)

    @Query("SELECT * FROM study_intents WHERE id = :id")
    suspend fun get(id: String): StudyIntentEntity?

    @Query("SELECT * FROM study_intents WHERE activeSlot = 1 LIMIT 1")
    suspend fun getActive(): StudyIntentEntity?

    @Query("SELECT * FROM study_intents WHERE activeSlot = 1 LIMIT 1")
    fun observeActive(): Flow<StudyIntentEntity?>

    @Query("SELECT * FROM study_intents WHERE learningItemId = :learningItemId AND activeSlot = 1 LIMIT 1")
    suspend fun getActiveForLearningItem(learningItemId: String): StudyIntentEntity?

    @Query("UPDATE study_intents SET transitionedAt = COALESCE(transitionedAt, :at) WHERE id = :id AND activeSlot = 1")
    suspend fun markTransitioned(id: String, at: Long): Int

    @Query("UPDATE study_intents SET convertedAt = :at, endedAt = :at, outcome = 'CONVERTED', activeSlot = NULL WHERE id = :id AND activeSlot = 1")
    suspend fun markConverted(id: String, at: Long): Int

    @Query("UPDATE study_intents SET convertedAt = NULL, endedAt = :at, outcome = 'ABANDONED', activeSlot = NULL WHERE id = :id AND activeSlot = 1")
    suspend fun markAbandoned(id: String, at: Long): Int

    @Query("UPDATE study_intents SET convertedAt = NULL, endedAt = :at, outcome = 'TIMEOUT', activeSlot = NULL WHERE id = :id AND activeSlot = 1")
    suspend fun markTimedOut(id: String, at: Long): Int
}
