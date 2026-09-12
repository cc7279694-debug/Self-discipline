package com.guanyi.mirra.data.local.model

data class RecentReadingSnapshot(
    val sessionId: String,
    val learningItemId: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMillis: Long,
    val noteCount: Int,
)
