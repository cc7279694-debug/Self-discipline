package com.guanyi.mirra.data.local.model

import androidx.room.Embedded
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity

/** A single indexed LEFT JOIN, with no note bodies or generated summary text loaded. */
data class IntentTrendRow(
    @Embedded val intent: StudyIntentEntity,
    @Embedded(prefix = "session_") val session: StudySessionEntity?,
)
