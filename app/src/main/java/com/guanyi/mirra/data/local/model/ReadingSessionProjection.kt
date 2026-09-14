package com.guanyi.mirra.data.local.model

import com.guanyi.mirra.data.local.entity.SessionEndType

data class ReadingSessionProjection(
    val sessionId: String,
    val learningItemId: String,
    val startedAt: Long,
    val endedAt: Long?,
    val startPage: Int,
    val endPage: Int?,
    val endType: SessionEndType?,
    val noteCount: Int,
)
