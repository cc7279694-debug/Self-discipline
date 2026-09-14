package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.FocusEventType
import com.guanyi.mirra.data.repository.DefaultFocusRepository
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.data.repository.SegmentTransitionCommand
import com.guanyi.mirra.data.repository.FocusEventInput
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModuleThreeAFocusRepositoryTest {
    private lateinit var db: MirraDatabase
    private var now = 1_000L
    private var id = 0
    private lateinit var workflow: DefaultStudyWorkflowRepository
    private lateinit var focus: DefaultFocusRepository
    private lateinit var items: DefaultLearningItemRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java).build()
        val ids = { "id-${++id}" }
        items = DefaultLearningItemRepository(db, clock = { now }, newId = ids)
        workflow = DefaultStudyWorkflowRepository(db, RuleBasedSummaryEngine(), IntentExpiryPolicy(), clock = { now }, newId = ids)
        focus = DefaultFocusRepository(db, clock = { now }, newId = ids)
    }

    @After fun tearDown() = db.close()

    @Test fun sessionStartsWithConservativeUnmonitoredFacts() = runTest {
        val session = start()
        assertEquals(MonitoringCoverage.NONE, focus.observeContext(session.id).first()?.monitoringStatus)
        assertEquals(SessionSegmentType.UNMONITORED, focus.observeActiveSegment(session.id).first()?.type)
        assertEquals(session.startedAt, focus.observeActiveSegment(session.id).first()?.startedAt)
    }

    @Test fun transitionsAreContinuousAndRejectZeroTimeDuplicateAndEndedWrites() = runTest {
        val session = start()
        now = 2_000
        val breakSegment = focus.transition(
            SegmentTransitionCommand(session.id, SessionSegmentType.BREAK, now, plannedEndAt = 2_500),
        )
        val timeline = db.focusDao().listSegments(session.id)
        assertEquals(timeline[0].endedAt, breakSegment.startedAt)
        assertFails { focus.transition(SegmentTransitionCommand(session.id, SessionSegmentType.FOCUS, now)) }
        now = 3_000
        workflow.finishSession(session.id, session.currentPage)
        assertFails { focus.transition(SegmentTransitionCommand(session.id, SessionSegmentType.FOCUS, now)) }
        assertFails { focus.updateHeartbeat(session.id, now) }
        assertNull(db.focusDao().getActiveSegment(session.id))
    }

    @Test fun eventAndHeartbeatRespectSessionTimeBounds() = runTest {
        val session = start()
        now = 2_000
        focus.recordEvent(FocusEventInput(session.id, FocusEventType.USER_PRESENT, 1_500))
        focus.updateHeartbeat(session.id, 1_500)
        assertFails { focus.recordEvent(FocusEventInput(session.id, FocusEventType.USER_PRESENT, 999)) }
        assertFails { focus.updateHeartbeat(session.id, 2_001) }
        assertEquals(1_500L, db.focusDao().getContext(session.id)?.lastHeartbeatAt)
    }

    @Test fun monitoringLossIsIdempotentAndCoverageCannotBecomeTrustedAgain() = runTest {
        val session = start()
        now = 2_000
        focus.markMonitoringLost(session.id, lastTrustedAt = 1_000, detectedAt = 2_000)
        focus.markMonitoringLost(session.id, lastTrustedAt = 1_000, detectedAt = 2_000)
        assertEquals(1, db.focusDao().listSegments(session.id).size)
        assertEquals(SessionSegmentType.UNMONITORED, db.focusDao().getActiveSegment(session.id)?.type)
        assertEquals(MonitoringCoverage.NONE, db.focusDao().getContext(session.id)?.monitoringStatus)
    }

    @Test fun processRecoveryClosesUnknownGapAndDoesNotForgeFocus() = runTest {
        val session = start()
        now = 4_000
        workflow.recoverInterruptedSession()
        val recovered = db.sessionDao().get(session.id)!!
        val segments = db.focusDao().listSegments(session.id)
        assertEquals(SessionEndType.ABNORMAL, recovered.endType)
        assertEquals(listOf(SessionSegmentType.UNMONITORED), segments.map { it.type })
        assertEquals(4_000L, segments.single().endedAt)
        assertEquals(false, segments.single().type.countsAsFocus)
    }

    @Test fun processRecoveryPreservesTrustedPartThenRecordsUnknownGap() = runTest {
        val session = start()
        db.focusDao().setCoverage(session.id, MonitoringCoverage.FULL, null, 1_000)
        now = 2_000
        focus.transition(SegmentTransitionCommand(session.id, SessionSegmentType.FOCUS, now))
        now = 2_500
        focus.updateHeartbeat(session.id, 2_500)
        now = 4_000

        workflow.recoverInterruptedSession()

        val timeline = db.focusDao().listSegments(session.id)
        assertEquals(
            listOf(SessionSegmentType.UNMONITORED, SessionSegmentType.FOCUS, SessionSegmentType.UNMONITORED),
            timeline.map { it.type },
        )
        assertEquals(2_500L, timeline[1].endedAt)
        assertEquals(2_500L, timeline[2].startedAt)
        assertEquals(4_000L, timeline[2].endedAt)
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(session.id)?.monitoringStatus)
    }

    @Test fun stableStartAndRecoveryAreMilestonesOnlyAfterTheirTrustedWindows() = runTest {
        val session = start()
        db.focusDao().setCoverage(session.id, MonitoringCoverage.FULL, null, 1_000)
        now = 2_000
        focus.transition(SegmentTransitionCommand(session.id, SessionSegmentType.FOCUS, now))
        now = 121_999
        assertFails { focus.markStableStarted(session.id, now) }
        now = 122_000
        focus.markStableStarted(session.id, now)
        assertEquals(122_000L, db.sessionDao().get(session.id)?.stableStartedAt)

        now = 200_000
        focus.transition(
            SegmentTransitionCommand(session.id, SessionSegmentType.DISTRACTION, now, packageName = "example.risk"),
        )
        now = 210_000
        focus.transition(SegmentTransitionCommand(session.id, SessionSegmentType.RECOVERY, now))
        now = 299_999
        assertFails { focus.completeRecovery(session.id, now) }
        now = 300_000
        focus.completeRecovery(session.id, now)
        assertEquals(SessionSegmentType.FOCUS, db.focusDao().getActiveSegment(session.id)?.type)
        assertEquals(listOf(FocusEventType.RECOVERY_SUCCEEDED), db.focusDao().listEvents(session.id).map { it.type })
    }

    @Test fun databaseUniqueSlotRejectsASecondActiveSegment() = runTest {
        val session = start()
        assertAnyFailure {
            db.focusDao().insertSegment(
                SessionSegmentEntity(
                    id = "duplicate", sessionId = session.id, type = SessionSegmentType.FOCUS,
                    startedAt = 2_000, endedAt = null, packageName = null, reason = null,
                    plannedEndAt = null, extensionCount = 0, relatedSegmentId = null, activeSlot = 1,
                ),
            )
        }
    }

    private suspend fun start() = items.create("书", 100, 10).let { item ->
        workflow.startSession(workflow.createIntent(item.id).id, 10)
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected failure") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }

    private suspend fun assertAnyFailure(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected failure")
        } catch (throwable: Throwable) {
            if (throwable is AssertionError) throw throwable
        }
    }
}
