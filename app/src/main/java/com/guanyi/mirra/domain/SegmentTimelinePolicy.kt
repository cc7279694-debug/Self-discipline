package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentType

data class SegmentInterval(
    val type: SessionSegmentType,
    val startedAt: Long,
    val endedAt: Long,
)

class SegmentTimelinePolicy {
    fun requireValid(sessionStartedAt: Long, sessionEndedAt: Long, segments: List<SegmentInterval>) {
        require(sessionEndedAt >= sessionStartedAt) { "Session 时间倒序" }
        require(segments.isNotEmpty()) { "Session 缺少 Segment" }
        require(segments.first().startedAt == sessionStartedAt) { "Segment 未覆盖 Session 起点" }
        segments.forEachIndexed { index, segment ->
            require(segment.endedAt > segment.startedAt) { "零时长或倒序 Segment" }
            require(segment.startedAt >= sessionStartedAt && segment.endedAt <= sessionEndedAt) { "Segment 越界" }
            if (index > 0) {
                require(segments[index - 1].endedAt == segment.startedAt) { "Segment 存在空洞或重叠" }
            }
        }
        require(segments.last().endedAt == sessionEndedAt) { "Segment 未覆盖 Session 终点" }
    }

    fun isFullyTrusted(
        sessionStartedAt: Long,
        sessionEndedAt: Long,
        segments: List<SegmentInterval>,
        coverage: MonitoringCoverage,
    ): Boolean {
        if (coverage != MonitoringCoverage.FULL) return false
        if (segments.any { it.type == SessionSegmentType.UNMONITORED }) return false
        return runCatching { requireValid(sessionStartedAt, sessionEndedAt, segments) }.isSuccess
    }
}
