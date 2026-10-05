package com.guanyi.mirra.data

import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.TestAppContainer
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.repository.*
import com.guanyi.mirra.domain.intervention.InterventionDeliveryReceipt
import com.guanyi.mirra.domain.monitoring.InterventionUiModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class InterventionReceiptRepositoryTest {
    private suspend fun fixture(block: suspend (TestAppContainer, InterventionUiModel, InterventionReceiptRepository) -> Unit) {
        TestAppContainer(ApplicationProvider.getApplicationContext()).use { c ->
            val item = c.learningItemRepository.create("3C3 receipt test", 100, 1)
            val intent = c.studyWorkflowRepository.createIntent(item.id)
            val s = c.studyWorkflowRepository.startSession(intent.id, 1)
            val db = c.database
            val start = s.startedAt
            // Dedicated fixture facts, not manual acceptance data.
            db.openHelper.writableDatabase.execSQL("UPDATE session_focus_contexts SET monitoringStatus='FULL', monitoringLostAt=NULL WHERE sessionId=?", arrayOf(s.id))
            db.openHelper.writableDatabase.execSQL("UPDATE session_segments SET type='DISTRACTION', packageName='risk' WHERE sessionId=?", arrayOf(s.id))
            val segment = db.focusDao().getActiveSegment(s.id)!!
            db.focusDao().insertEvent(FocusEventEntity("risk", s.id, FocusEventType.RISK_APP_CONFIRMED,
                start, "risk", segment.id, null))
            val p = InterventionUiModel(s.id, "risk", "risk", segment.id, "risk", 0, 0)
            block(c, p, InterventionReceiptRepository(db, { !it.dismissed }, { start + 100 }))
        }
    }
    @Test fun overlayAndInAppEachRecordOnceAndNeverChangeFacts() = runTest { fixture { c, p, r ->
        repeat(2) { assertTrue(r.record(p, InterventionDeliveryReceipt.OVERLAY_ATTACHED)) }
        repeat(2) { assertTrue(r.record(p, InterventionDeliveryReceipt.IN_APP_PRESENTED)) }
        val events = c.database.focusDao().listEvents(p.sessionId).filter { it.type == FocusEventType.INTERVENTION_SHOWN }
        assertEquals(2, events.size)
        assertEquals(setOf(InterventionDeliveryChannel.OVERLAY, InterventionDeliveryChannel.IN_APP), events.map { it.deliveryChannel }.toSet())
        assertEquals(SessionSegmentType.DISTRACTION, c.database.focusDao().getActiveSegment(p.sessionId)?.type)
        assertEquals(MonitoringCoverage.FULL, c.database.focusDao().getContext(p.sessionId)?.monitoringStatus)
    } }
    @Test fun notificationPostedNeverWritesShown() = runTest { fixture { c, p, r ->
        assertFalse(r.record(p, InterventionDeliveryReceipt.NOTIFICATION_POSTED))
        assertEquals(1, c.database.focusDao().listEvents(p.sessionId).size)
    } }
    @Test fun unavailableIsIdempotentPerEpisode() = runTest { fixture { c, p, r ->
        repeat(3) { assertTrue(r.record(p, InterventionDeliveryReceipt.UNAVAILABLE)) }
        assertEquals(1, c.database.focusDao().listEvents(p.sessionId).count { it.type == FocusEventType.INTERVENTION_UNAVAILABLE })
    } }
    @Test fun lateDismissedAndStaleTokensCannotRecord() = runTest { fixture { c, p, r ->
        assertFalse(r.record(p.copy(dismissed = true), InterventionDeliveryReceipt.OVERLAY_ATTACHED))
        assertFalse(r.record(p.copy(eventId = "old", promptToken = "old"), InterventionDeliveryReceipt.OVERLAY_ATTACHED))
        assertEquals(1, c.database.focusDao().listEvents(p.sessionId).size)
    } }
    @Test fun endedSessionRejectsLateReceipt() = runTest { fixture { c, p, r ->
        c.studyWorkflowRepository.beginCloseout(p.sessionId, 1, System.currentTimeMillis())
        c.studyWorkflowRepository.completeCloseout(p.sessionId)
        assertFalse(r.record(p, InterventionDeliveryReceipt.OVERLAY_ATTACHED))
        assertEquals(1, c.database.focusDao().listEvents(p.sessionId).size)
    } }
    @Test fun monitoringLossRejectsLateReceipt() = runTest { fixture { c, p, r ->
        val start = c.database.sessionDao().get(p.sessionId)!!.startedAt
        c.focusRepository.markMonitoringLost(p.sessionId, start, System.currentTimeMillis())
        assertFalse(r.record(p, InterventionDeliveryReceipt.OVERLAY_ATTACHED))
        assertEquals(SessionSegmentType.UNMONITORED, c.database.focusDao().getActiveSegment(p.sessionId)?.type)
    } }
}
