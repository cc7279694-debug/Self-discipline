package com.guanyi.mirra.domain.wearable

import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.domain.IntentExpiryPolicy
import org.junit.Assert.*
import org.junit.Test

class WearableProjectionTest {
    private val item = LearningItemEntity("book", "中文 📖", LearningItemStatus.IN_PROGRESS, 300, 30, 1,
        "翻到第 30 页，读第一段", 10, 10, null)
    private val intent = StudyIntentEntity("intent", item.id, 100, null, null, null, null, 1)
    private val session = StudySessionEntity("session", item.id, intent.id, 200, null, null, 30, 45, null, null, null, 1)
    private val context = SessionFocusContextEntity(session.id, monitoringStatus = MonitoringCoverage.FULL,
        monitoringLostAt = null, priorDndInterruptionFilter = null, dndRuleId = null,
        requestedEndPage = null, closeoutStartedAt = null, lastHeartbeatAt = 900, createdAt = 200, updatedAt = 900)
    private val segment = SessionSegmentEntity("segment", session.id, SessionSegmentType.FOCUS, 200, null,
        null, null, null, relatedSegmentId = null, activeSlot = 1)

    @Test fun `empty confirmed source is idle with nullable book`() {
        val body = WearableProjection.project(null, null, null, null, null, 1_000, MonitorAvailability.UNKNOWN)
        assertEquals(WearablePhase.IDLE, body.phase)
        assertNull(body.bookTitle)
        assertNull(body.elapsedMs)
    }
    @Test fun `preparation uses exact chosen phone action`() {
        val body = WearableProjection.project(item, intent, null, null, null, 1_000, MonitorAvailability.UNKNOWN)
        assertEquals(WearablePhase.PREPARING, body.phase)
        assertEquals(item.firstAction, body.firstAction)
        assertEquals(900L, body.elapsedMs)
    }
    @Test fun `valid current preparation can be confirmed until the expiry boundary`() {
        val body = WearableProjection.project(item, intent, null, null, null,
            intent.createdAt + IntentExpiryPolicy.DEFAULT_TIMEOUT_MILLIS - 1, MonitorAvailability.UNKNOWN)
        assertEquals(WearablePhase.PREPARING, body.phase)
        assertEquals(item.firstAction, body.firstAction)
        assertTrue(body.preparationConfirmable)
    }
    @Test fun `expired preparation remains truthful but cannot be confirmed`() {
        val body = WearableProjection.project(item, intent, null, null, null,
            intent.createdAt + IntentExpiryPolicy.DEFAULT_TIMEOUT_MILLIS, MonitorAvailability.UNKNOWN)
        assertEquals(WearablePhase.PREPARING, body.phase)
        assertEquals(item.firstAction, body.firstAction)
        assertFalse(body.preparationConfirmable)
    }
    @Test fun `paused or completed book never admits preparation confirmation`() {
        for (status in listOf(LearningItemStatus.PAUSED, LearningItemStatus.COMPLETED)) {
            val body = WearableProjection.project(item.copy(status = status), intent, null, null, null,
                1_000, MonitorAvailability.UNKNOWN)
            assertEquals(WearablePhase.PREPARING, body.phase)
            assertFalse(body.preparationConfirmable)
        }
    }
    @Test fun `unselected legacy or invalid action never admits preparation confirmation`() {
        for (action in listOf("", "拿起《中文 📖》，翻到第 30 页。", "读完这本书")) {
            val body = WearableProjection.project(item.copy(firstAction = action), intent, null, null, null,
                1_000, MonitorAvailability.UNKNOWN)
            assertNull(body.firstAction)
            assertFalse(body.preparationConfirmable)
        }
    }
    @Test fun `backward clock cannot admit preparation confirmation`() {
        val body = WearableProjection.project(item, intent, null, null, null,
            intent.createdAt - 1, MonitorAvailability.UNKNOWN)
        assertNull(body.elapsedMs)
        assertFalse(body.preparationConfirmable)
    }
    @Test fun `active session page wins over book and full is independent of current monitoring`() {
        val body = WearableProjection.project(item, null, session, context, segment, 1_000, MonitorAvailability.UNAVAILABLE)
        assertEquals(45, body.currentPage)
        assertEquals(MonitoringCoverage.FULL.name, body.coverage)
        assertEquals(MonitorAvailability.UNAVAILABLE, body.monitorAvailability)
        assertEquals(800L, body.elapsedMs)
    }
    @Test fun `pending freezes at durable end boundary and does not need active segment`() {
        val pending = context.copy(closeoutState = FocusCloseoutState.PENDING, closeoutStartedAt = 950, requestedEndPage = 46)
        val body = WearableProjection.project(item, null, session, pending, null, 9_000, MonitorAvailability.AVAILABLE)
        assertEquals(WearablePhase.SAVING, body.phase)
        assertEquals(750L, body.elapsedMs)
        assertNull(body.segmentType)
        assertEquals(WearableTimerKind.FINAL_SESSION_DURATION, body.timerKind)
    }
    @Test fun `missing or foreign related facts fail closed`() {
        assertThrows(WearableSourceUnavailable::class.java) {
            WearableProjection.project(item, null, session, null, segment, 1_000, MonitorAvailability.UNKNOWN)
        }
        assertThrows(WearableSourceUnavailable::class.java) {
            WearableProjection.project(item, null, session, context.copy(sessionId = "foreign"), segment, 1_000, MonitorAvailability.UNKNOWN)
        }
    }
    @Test fun `bad time is unavailable instead of zero`() {
        val body = WearableProjection.project(item, null, session, context, segment, 150, MonitorAvailability.UNKNOWN)
        assertNull(body.elapsedMs)
        assertEquals(WearableTimerKind.NONE, body.timerKind)
    }
    @Test fun `break remaining holds at zero without changing segment`() {
        val body = WearableProjection.project(item, null, session, context,
            segment.copy(type = SessionSegmentType.BREAK, plannedEndAt = 900), 1_000, MonitorAvailability.AVAILABLE)
        assertEquals(0L, body.remainingMs)
        assertEquals(SessionSegmentType.BREAK.name, body.segmentType)
    }
    @Test fun `abnormal ended does not become normal`() {
        val body = WearableProjection.project(item, null, session.copy(activeSlot = null, endedAt = 800,
            endType = SessionEndType.ABNORMAL, endPage = 45), context.copy(closeoutState = FocusCloseoutState.ABORTED),
            null, 1_000, MonitorAvailability.UNKNOWN)
        assertEquals(WearablePhase.ENDED, body.phase)
        assertEquals("ABNORMAL", body.endType)
        assertEquals(600L, body.elapsedMs)
    }
}
