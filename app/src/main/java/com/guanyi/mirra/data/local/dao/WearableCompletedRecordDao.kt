package com.guanyi.mirra.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.guanyi.mirra.data.local.entity.WearableCompletedRecordEntity

@Dao
interface WearableCompletedRecordDao {
    @Insert suspend fun insert(record: WearableCompletedRecordEntity)

    @Query("SELECT * FROM wearable_completed_records WHERE installationId=:installationId AND sessionId=:sessionId")
    suspend fun get(installationId: String, sessionId: String): WearableCompletedRecordEntity?

    @Query("SELECT CAST(canonical AS BLOB) FROM wearable_completed_records WHERE installationId=:installationId AND sessionId=:sessionId")
    suspend fun canonicalBytes(installationId: String, sessionId: String): ByteArray?

    @Query("SELECT COUNT(*) FROM wearable_completed_records")
    suspend fun count(): Long
}
