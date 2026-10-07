package com.guanyi.mirra.data.local.model

import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionEndType

data class GlobalReadingHistoryRow(
    val sessionId: String,
    val learningItemName: String,
    val startedAt: Long,
    val endedAt: Long,
    val startPage: Int,
    val endPage: Int?,
    val endType: SessionEndType?,
    val monitoringStatus: MonitoringCoverage?,
)
