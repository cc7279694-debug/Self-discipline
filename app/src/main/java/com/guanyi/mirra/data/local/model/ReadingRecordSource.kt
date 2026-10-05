package com.guanyi.mirra.data.local.model

import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.SessionRiskAppSnapshotEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity

data class ReadingRecordSource(
    val session: StudySessionEntity,
    val context: SessionFocusContextEntity?,
    val segments: List<SessionSegmentEntity>,
    val riskSnapshots: List<SessionRiskAppSnapshotEntity>,
    val noteCount: Int,
)
