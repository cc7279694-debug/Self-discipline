package com.guanyi.mirra.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IntentDao {
    @Insert suspend fun insert(intent: StudyIntentEntity)

    @Query("SELECT * FROM study_intents WHERE id = :id")
    suspend fun get(id: String): StudyIntentEntity?

    @Query("SELECT * FROM study_intents WHERE activeSlot = 1 LIMIT 1")
    suspend fun getActive(): StudyIntentEntity?

    @Query("SELECT * FROM study_intents WHERE activeSlot = 1 LIMIT 1")
    fun observeActive(): Flow<StudyIntentEntity?>

    @Query("UPDATE study_intents SET transitionedAt = COALESCE(transitionedAt, :at) WHERE id = :id AND activeSlot = 1")
    suspend fun markTransitioned(id: String, at: Long): Int

    @Query("UPDATE study_intents SET convertedAt = :at, endedAt = :at, outcome = :outcome, activeSlot = NULL WHERE id = :id AND activeSlot = 1")
    suspend fun complete(id: String, at: Long, outcome: IntentOutcome): Int
}
