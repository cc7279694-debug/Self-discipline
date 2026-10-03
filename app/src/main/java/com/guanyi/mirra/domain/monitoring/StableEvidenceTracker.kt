package com.guanyi.mirra.domain.monitoring

import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentType

enum class EvidenceMilestone { RECOVERY, STABLE, DEEP }
class StableEvidence internal constructor(val segmentId: String, val milestone: EvidenceMilestone,
    val continuousMillis: Long, val screenOffMillis: Long)
data class EvidenceDecision(val recovery: StableEvidence? = null, val stable: StableEvidence? = null,
    val deep: StableEvidence? = null)

class StableEvidenceTracker {
    private var segment: String? = null
    private var positiveSince: Long? = null
    private var screenSince: Long? = null
    private var last: Long? = null
    fun clear() { segment = null; positiveSince = null; screenSince = null; last = null }
    fun accept(segmentId: String, type: SessionSegmentType, coverage: MonitoringCoverage,
        elapsed: Long, healthy: Boolean, positive: Boolean, screenOff: Boolean,
        recoveryMillis: Long = 90_000, stableMillis: Long = 120_000,
        deepMillis: Long = 900_000, deepScreenMillis: Long = 600_000): EvidenceDecision {
        if (segment != segmentId || (last != null && elapsed < last!!)) clear()
        segment = segmentId; last = elapsed
        if (!healthy || coverage == MonitoringCoverage.NONE || !positive ||
            type !in setOf(SessionSegmentType.RECOVERY, SessionSegmentType.FOCUS, SessionSegmentType.DEEP_FOCUS)) {
            positiveSince = null; screenSince = null
            return EvidenceDecision()
        }
        if (positiveSince == null) positiveSince = elapsed
        if (!screenOff) screenSince = null else if (screenSince == null) screenSince = elapsed
        val duration = elapsed - positiveSince!!
        val screen = screenSince?.let { elapsed - it } ?: 0
        fun proof(milestone: EvidenceMilestone) = StableEvidence(segmentId, milestone, duration, screen)
        return EvidenceDecision(
            recovery = proof(EvidenceMilestone.RECOVERY).takeIf { type == SessionSegmentType.RECOVERY && duration >= recoveryMillis },
            stable = proof(EvidenceMilestone.STABLE).takeIf { type == SessionSegmentType.FOCUS && coverage == MonitoringCoverage.FULL && duration >= stableMillis },
            deep = proof(EvidenceMilestone.DEEP).takeIf { type == SessionSegmentType.FOCUS && coverage == MonitoringCoverage.FULL && duration >= deepMillis && screen >= deepScreenMillis },
        )
    }
}
