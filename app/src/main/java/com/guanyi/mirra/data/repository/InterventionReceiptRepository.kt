package com.guanyi.mirra.data.repository

import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.domain.intervention.InterventionDeliveryReceipt
import com.guanyi.mirra.domain.monitoring.InterventionUiModel
import androidx.room.withTransaction
import com.guanyi.mirra.data.local.entity.FocusEventEntity
import com.guanyi.mirra.data.local.entity.FocusEventType
import com.guanyi.mirra.data.local.entity.InterventionDeliveryChannel
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentType

/** Presentation facts only; never owns Segment transitions or monitoring coverage. */
class InterventionReceiptRepository(private val database: MirraDatabase,
    private val isCurrent: (InterventionUiModel) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis) {
    suspend fun isEligible(prompt: InterventionUiModel): Boolean = database.withTransaction { eligible(prompt) }

    suspend fun record(prompt: InterventionUiModel, receipt: InterventionDeliveryReceipt): Boolean {
        if (receipt == InterventionDeliveryReceipt.NOTIFICATION_POSTED) return false
        return database.withTransaction {
            if (!eligible(prompt)) return@withTransaction false
            val channel = when (receipt) {
                InterventionDeliveryReceipt.IN_APP_PRESENTED -> InterventionDeliveryChannel.IN_APP
                InterventionDeliveryReceipt.OVERLAY_ATTACHED -> InterventionDeliveryChannel.OVERLAY
                else -> null
            }
            val id = "presentation:${prompt.sessionId}:${prompt.eventId}:$receipt"
            val dao = database.focusDao()
            if (dao.eventExists(prompt.sessionId, id)) return@withTransaction true
            val at = now()
            val segment = dao.getActiveSegment(prompt.sessionId) ?: return@withTransaction false
            if (at < segment.startedAt || !isCurrent(prompt)) return@withTransaction false
            dao.insertEvent(FocusEventEntity(id, prompt.sessionId,
                if (receipt == InterventionDeliveryReceipt.UNAVAILABLE) FocusEventType.INTERVENTION_UNAVAILABLE
                else FocusEventType.INTERVENTION_SHOWN,
                at, prompt.packageName, segment.id, channel))
            true
        }
    }

    /** Same episode eligibility as the frozen behavior repository; no transitions here. */
    private suspend fun eligible(prompt: InterventionUiModel): Boolean {
        if (prompt.dismissed || prompt.promptToken != prompt.eventId || !isCurrent(prompt)) return false
        val session = database.sessionDao().get(prompt.sessionId) ?: return false
        if (session.activeSlot != 1 || session.endedAt != null) return false
        val dao = database.focusDao()
        val context = dao.getContext(session.id) ?: return false
        if (!isLearningFactWritable(session, context)) return false
        if (context.monitoringStatus == MonitoringCoverage.NONE || context.monitoringLostAt != null) return false
        val segment = dao.getActiveSegment(session.id) ?: return false
        if (segment.id != prompt.segmentId) return false
        val risk = dao.latestRiskConfirmation(session.id) ?: return false
        if (risk.id != prompt.eventId || risk.packageName != prompt.packageName) return false
        return when (segment.type) {
            SessionSegmentType.DISTRACTION -> segment.packageName == risk.packageName && risk.occurredAt >= segment.startedAt
            SessionSegmentType.RECOVERY -> segment.relatedSegmentId?.let { dao.getSegment(session.id, it) }
                ?.let { it.type == SessionSegmentType.DISTRACTION && it.packageName == risk.packageName && risk.occurredAt >= it.startedAt } == true
            else -> false
        }
    }
}
