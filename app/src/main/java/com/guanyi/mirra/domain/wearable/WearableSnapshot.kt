package com.guanyi.mirra.domain.wearable

import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.domain.FirstActionResolver
import com.guanyi.mirra.domain.IntentExpiryPolicy
import kotlinx.serialization.Serializable

@Serializable enum class WearablePhase { IDLE, PREPARING, ACTIVE, SAVING, ENDED }
@Serializable enum class MonitorAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }
@Serializable enum class WearableTimerKind {
    NONE, PREPARATION_ELAPSED, SESSION_ELAPSED, BREAK_REMAINING, ALLOWANCE_REMAINING, FINAL_SESSION_DURATION
}

/** Display-only fields. There is no note, risk app, DND ownership or write command. */
@Serializable data class WearableSnapshot(
    val phase: WearablePhase,
    val intentId: String? = null,
    val sessionId: String? = null,
    val learningItemId: String? = null,
    val bookTitle: String? = null,
    val currentPage: Int? = null,
    val totalPages: Int? = null,
    val firstAction: String? = null,
    val bookTitleTruncated: Boolean = false,
    val firstActionTruncated: Boolean = false,
    val segmentType: String? = null,
    val coverage: String? = null,
    val monitorAvailability: MonitorAvailability = MonitorAvailability.UNKNOWN,
    val elapsedMs: Long? = null,
    val remainingMs: Long? = null,
    val timerKind: WearableTimerKind = WearableTimerKind.NONE,
    val endType: String? = null,
    // Internal current identity; v1 body serialization remains byte-compatible.
    @kotlinx.serialization.Transient val activeSegmentId: String? = null,
    @kotlinx.serialization.Transient val preparationConfirmable: Boolean = false,
)

class WearableSourceUnavailable : Exception("SOURCE_UNAVAILABLE")
class WearableSourceBusy : Exception("SOURCE_BUSY")

fun interface WearableSnapshotSource {
    suspend fun read(lastSeenSessionId: String?): WearableSnapshot
}

object WearableProjection {
    private val intentExpiryPolicy = IntentExpiryPolicy()
    fun project(item: LearningItemEntity?, intent: StudyIntentEntity?, session: StudySessionEntity?,
        context: SessionFocusContextEntity?, segment: SessionSegmentEntity?, now: Long,
        monitorAvailability: MonitorAvailability): WearableSnapshot {
        if (item != null && (item.totalPages < 1 || item.currentPage !in 1..item.totalPages)) unavailable()
        val base = WearableSnapshot(WearablePhase.IDLE, learningItemId = item?.id, bookTitle = item?.name,
            currentPage = item?.currentPage, totalPages = item?.totalPages,
            firstAction = item?.let(FirstActionResolver::resolve)?.takeIf(String::isNotEmpty))
        if (session == null) {
            if (context != null || segment != null) unavailable()
            if (intent == null) return base
            if (item == null || intent.learningItemId != item.id || intent.activeSlot != 1 ||
                intent.outcome != null || intent.endedAt != null || intent.convertedAt != null) unavailable()
            val elapsed = duration(intent.createdAt, now)
            return base.copy(phase = WearablePhase.PREPARING, intentId = intent.id, elapsedMs = elapsed,
                preparationConfirmable = elapsed != null && item.status == LearningItemStatus.IN_PROGRESS &&
                    !intentExpiryPolicy.isExpired(intent.createdAt, now) && base.firstAction != null,
                timerKind = if (elapsed == null) WearableTimerKind.NONE else WearableTimerKind.PREPARATION_ELAPSED)
        }
        if (item == null || item.id != session.learningItemId || session.currentPage !in 1..item.totalPages ||
            context?.sessionId != session.id || intent != null) unavailable()
        if (segment != null && (segment.sessionId != session.id || segment.activeSlot != 1 ||
            segment.endedAt != null || segment.startedAt < session.startedAt)) unavailable()
        val selected = base.copy(intentId = session.intentId, sessionId = session.id,
            currentPage = session.currentPage, coverage = context.monitoringStatus.name,
            monitorAvailability = monitorAvailability)
        if (session.endedAt != null) {
            if (session.activeSlot != null || session.endType == null || segment != null ||
                context.closeoutState !in setOf(FocusCloseoutState.COMPLETED, FocusCloseoutState.ABORTED)) unavailable()
            val elapsed = duration(session.startedAt, session.endedAt)
            return selected.copy(phase = WearablePhase.ENDED, elapsedMs = elapsed, endType = session.endType.name,
                currentPage = session.endPage ?: session.currentPage,
                timerKind = if (elapsed == null) WearableTimerKind.NONE else WearableTimerKind.FINAL_SESSION_DURATION,
                monitorAvailability = MonitorAvailability.UNKNOWN)
        }
        if (session.activeSlot != 1 || session.endType != null) unavailable()
        if (context.closeoutState == FocusCloseoutState.PENDING) {
            if (segment != null || context.closeoutStartedAt == null || context.requestedEndPage == null ||
                context.requestedEndPage !in session.currentPage..item.totalPages) unavailable()
            val elapsed = duration(session.startedAt, context.closeoutStartedAt)
            return selected.copy(phase = WearablePhase.SAVING, elapsedMs = elapsed,
                monitorAvailability = MonitorAvailability.UNKNOWN,
                timerKind = if (elapsed == null) WearableTimerKind.NONE else WearableTimerKind.FINAL_SESSION_DURATION)
        }
        if (context.closeoutState != FocusCloseoutState.ACTIVE || segment == null) unavailable()
        // Never turn a backward or inconsistent wall clock into a plausible zero-duration fact.
        val continuous = now >= maxOf(context.lastHeartbeatAt, context.updatedAt, segment.startedAt)
        val elapsed = if (continuous) duration(session.startedAt, now) else null
        val remaining = if (continuous) segment.plannedEndAt?.takeIf { it >= segment.startedAt && it <= MAX_SAFE_INTEGER }
            ?.let { (it - now).coerceAtLeast(0) } else null
        val kind = when {
            elapsed == null -> WearableTimerKind.NONE
            segment.type == SessionSegmentType.BREAK && remaining != null -> WearableTimerKind.BREAK_REMAINING
            segment.type == SessionSegmentType.TEMPORARY_ALLOWANCE && remaining != null -> WearableTimerKind.ALLOWANCE_REMAINING
            else -> WearableTimerKind.SESSION_ELAPSED
        }
        return selected.copy(phase = WearablePhase.ACTIVE, segmentType = segment.type.name, activeSegmentId = segment.id,
            elapsedMs = elapsed, remainingMs = remaining, timerKind = kind)
    }

    private fun duration(start: Long, end: Long): Long? =
        if (start < 1 || end < start || end > MAX_SAFE_INTEGER) null else (end - start).takeIf { it <= MAX_SAFE_INTEGER }
    private fun unavailable(): Nothing = throw WearableSourceUnavailable()
    const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L
}
