package com.guanyi.mirra.data.local.model

import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity

/** Read-only business facts; missing facts are never synthesized into monitoring evidence. */
data class EffectiveReadingSource(
    val sessions: List<StudySessionEntity>,
    val contexts: Map<String, SessionFocusContextEntity>,
    val segments: Map<String, List<SessionSegmentEntity>>,
)
