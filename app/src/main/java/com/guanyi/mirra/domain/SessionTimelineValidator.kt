package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.StudySessionEntity

enum class TimelineTrust { COMPLETE_TRUSTED, MONITORING_INCOMPLETE, STRUCTURE_INVALID, SESSION_INELIGIBLE }

data class SessionTimelineAnalysis(
    val trust: TimelineTrust,
    val effectiveFocusMillis: Long?,
    val breakCount: Int,
    val allowanceCount: Int,
    val distractionCount: Int,
)

class SessionTimelineValidator(private val policy: SegmentTimelinePolicy = SegmentTimelinePolicy()) {
    fun analyze(
        session: StudySessionEntity,
        context: SessionFocusContextEntity?,
        segments: List<SessionSegmentEntity>,
    ): SessionTimelineAnalysis {
        // Counts remain readable facts even when they cannot describe a trusted whole session.
        fun analysis(trust: TimelineTrust, focusMillis: Long? = null) = SessionTimelineAnalysis(
            trust, focusMillis,
            segments.count { it.type == SessionSegmentType.BREAK },
            segments.count { it.type == SessionSegmentType.TEMPORARY_ALLOWANCE },
            segments.count { it.type == SessionSegmentType.DISTRACTION },
        )

        val endedAt = session.endedAt
        if (session.endType != SessionEndType.NORMAL || endedAt == null || endedAt <= session.startedAt) {
            return analysis(TimelineTrust.SESSION_INELIGIBLE)
        }
        if (context == null || context.monitoringStatus != MonitoringCoverage.FULL ||
            context.monitoringLostAt != null || segments.any { it.type == SessionSegmentType.UNMONITORED }
        ) {
            return analysis(TimelineTrust.MONITORING_INCOMPLETE)
        }
        if (context.sessionId != session.id || segments.any {
                it.sessionId != session.id || it.endedAt == null || it.activeSlot != null
            }
        ) return analysis(TimelineTrust.STRUCTURE_INVALID)

        return try {
            val intervals = segments.sortedWith(compareBy<SessionSegmentEntity> { it.startedAt }.thenBy { it.id })
                .map { SegmentInterval(it.type, it.startedAt, requireNotNull(it.endedAt)) }
            policy.requireValid(session.startedAt, endedAt, intervals)
            Math.subtractExact(endedAt, session.startedAt)
            var focusMillis = 0L
            for (interval in intervals) {
                val duration = Math.subtractExact(interval.endedAt, interval.startedAt)
                if (interval.type == SessionSegmentType.FOCUS || interval.type == SessionSegmentType.DEEP_FOCUS) {
                    focusMillis = Math.addExact(focusMillis, duration)
                }
            }
            analysis(TimelineTrust.COMPLETE_TRUSTED, focusMillis)
        } catch (_: IllegalArgumentException) {
            analysis(TimelineTrust.STRUCTURE_INVALID)
        } catch (_: ArithmeticException) {
            analysis(TimelineTrust.STRUCTURE_INVALID)
        }
    }
}
