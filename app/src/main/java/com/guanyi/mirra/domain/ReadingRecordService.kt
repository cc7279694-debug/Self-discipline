package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.model.ReadingRecordSource
import com.guanyi.mirra.data.local.entity.SessionSegmentType

class ReadingRecordService(private val validator: SessionTimelineValidator = SessionTimelineValidator()) {
    fun project(source: ReadingRecordSource): ReadingRecordProjection {
        val session = source.session
        val analysis = validator.analyze(session, source.context, source.segments)
        val trusted = analysis.trust == TimelineTrust.COMPLETE_TRUSTED
        val duration = session.endedAt?.let { end ->
            try { Math.subtractExact(end, session.startedAt).takeIf { it >= 0 } }
            catch (_: ArithmeticException) { null }
        }
        val labels = source.riskSnapshots.associate { it.packageName to it.labelSnapshot.trim() }
        val timeline = mutableListOf<HumanReadingInterval>()
        var canMergeReading = false
        for (segment in source.segments.sortedWith(compareBy({ it.startedAt }, { it.id }))) {
            val end = segment.endedAt?.takeIf { it > segment.startedAt }
            if (end == null) { canMergeReading = false; continue }
            val type = when (segment.type) {
                SessionSegmentType.FOCUS, SessionSegmentType.DEEP_FOCUS -> HumanReadingSegment.READING
                SessionSegmentType.BREAK -> HumanReadingSegment.BREAK
                SessionSegmentType.TEMPORARY_ALLOWANCE -> HumanReadingSegment.TEMPORARY_USE
                SessionSegmentType.DISTRACTION -> HumanReadingSegment.DISTRACTION
                SessionSegmentType.RECOVERY -> HumanReadingSegment.RECOVERING
                SessionSegmentType.UNMONITORED -> HumanReadingSegment.UNMONITORED
            }
            val label = if (type == HumanReadingSegment.DISTRACTION || type == HumanReadingSegment.TEMPORARY_USE) {
                labels[segment.packageName]?.takeIf { it.isNotBlank() } ?: "风险 App"
            } else null
            val interval = HumanReadingInterval(type, segment.startedAt, end, label)
            val previous = timeline.lastOrNull()
            // Presentation only: never alter stored segments, counts or validator input.
            if (canMergeReading && type == HumanReadingSegment.READING && previous?.type == type && previous.endedAt == interval.startedAt) {
                timeline[timeline.lastIndex] = previous.copy(endedAt = interval.endedAt)
            } else timeline.add(interval)
            canMergeReading = type == HumanReadingSegment.READING
        }
        return ReadingRecordProjection(
            session.id, session.startPage, session.endPage,
            session.endPage?.let { (it.toLong() - session.startPage.toLong()).coerceAtLeast(0L) } ?: 0L,
            duration, source.noteCount, session.endType, analysis.trust, source.context?.monitoringStatus,
            analysis.effectiveFocusMillis.takeIf { trusted },
            if (trusted) ReadingSegmentCounts(analysis.breakCount, analysis.allowanceCount, analysis.distractionCount) else null,
            timeline, source.context?.dndLifecycle,
        )
    }
}
