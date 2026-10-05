package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.DndLifecycle
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionEndType

enum class HumanReadingSegment { READING, BREAK, TEMPORARY_USE, DISTRACTION, RECOVERING, UNMONITORED }

data class HumanReadingInterval(val type: HumanReadingSegment, val startedAt: Long, val endedAt: Long, val appLabel: String?)
data class ReadingSegmentCounts(val breaks: Int, val allowances: Int, val distractions: Int)

data class ReadingRecordProjection(
    val sessionId: String, val startPage: Int, val endPage: Int?, val pagesRead: Long,
    val totalDurationMillis: Long?, val noteCount: Int, val endType: SessionEndType?,
    val trust: TimelineTrust, val monitoringStatus: MonitoringCoverage?, val effectiveFocusMillis: Long?,
    val wholeSessionCounts: ReadingSegmentCounts?, val timeline: List<HumanReadingInterval>,
    val dndLifecycle: DndLifecycle?,
)
