package com.guanyi.mirra.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/** Immutable original wrist facts; independent of phone Session and book lifetimes. */
@Entity(tableName = "wearable_completed_records", primaryKeys = ["installationId", "sessionId"],
    indices = [Index("receivedAt")])
data class WearableCompletedRecordEntity(
    val installationId: String,
    val sessionId: String,
    val canonical: String,
    val digest: String,
    val bytes: Int,
    val receivedAt: Long,
)
