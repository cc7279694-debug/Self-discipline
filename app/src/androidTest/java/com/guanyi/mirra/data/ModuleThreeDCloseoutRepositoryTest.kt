package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.repository.DefaultFocusRepository
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.data.repository.SegmentTransitionCommand
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import com.guanyi.mirra.domain.monitoring.BoundSessionMonitoringController
import com.guanyi.mirra.domain.monitoring.ClockSample
import com.guanyi.mirra.domain.monitoring.MonitoringSignal
import com.guanyi.mirra.domain.monitoring.RepositoryRuntimeFactsPort
import com.guanyi.mirra.platform.focus.MonitorSnapshot
import com.guanyi.mirra.platform.focus.MonitoringBinding
import com.guanyi.mirra.platform.focus.MonitoringReadyLease
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModuleThreeDCloseoutRepositoryTest {
    private lateinit var db: MirraDatabase
    private var wallNow = 1_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java,
        ).build()
    }

    @After fun tearDown() = db.close()

    @Test fun backwardClockJumpLossIsDurableBeforeCloseout() = runTest {
        val items = DefaultLearningItemRepository(db, clock = { wallNow })
        val workflow = DefaultStudyWorkflowRepository(
            db, RuleBasedSummaryEngine(), IntentExpiryPolicy(), clock = { wallNow },
        )
        val intent = workflow.createIntent(items.create("Clock preflight", 100, 10).id)
        val lease = MonitoringReadyLease(
            generation = "clock-preflight", readyAtWall = 1_000, readyAtElapsed = 0,
            successfulQueryGeneration = 1, cursorWallMillis = 1_000,
            lastSuccessfulQueryElapsed = 0, continuityEpoch = 0,
            notificationVisible = false, dndAccessAvailable = false,
        )
        val session = workflow.startMonitoredSession(
            intent.id, 10, lease, proposedSessionId = "clock-preflight-session",
        ).session
        val controller = BoundSessionMonitoringController(
            RepositoryRuntimeFactsPort(DefaultFocusRepository(db, clock = { wallNow })),
        )
        val binding = MonitoringBinding(session.id, lease.generation, lease.readyAtElapsed)
        assertEquals(1_000L, session.startedAt)
        assertEquals(MonitoringCoverage.FULL, db.focusDao().getContext(session.id)?.monitoringStatus)
        assertEquals(SessionSegmentType.FOCUS, db.focusDao().getActiveSegment(session.id)?.type)

        wallNow = 2_000
        val trustedSample = ClockSample(wallNow, 1_000)
        controller.onSample(binding, MonitorSnapshot(
            running = true, queryGeneration = 2,
            lastSuccessfulQueryElapsed = trustedSample.elapsedNowMillis,
            lastSuccessfulQueryClock = trustedSample,
        ), trustedSample)

        // Move only the injected wall clock backwards; monotonic elapsed time advances.
        wallNow = 100
        val regressionSample = ClockSample(wallNow, 2_000)
        try {
            controller.onSample(binding, MonitorSnapshot(
                running = false, queryGeneration = 2,
                lastSuccessfulQueryElapsed = trustedSample.elapsedNowMillis,
                lastSuccessfulQueryClock = trustedSample,
                signals = setOf(MonitoringSignal.WALL_CLOCK_JUMP),
            ), regressionSample)
        } catch (failure: Throwable) {
            // Read real Room after transaction failure, preserving the original exception.
            val context = db.focusDao().getContext(session.id)
            val active = db.focusDao().getActiveSegment(session.id)
            failure.addSuppressed(AssertionError(
                "Durable state after failed loss: coverage=${context?.monitoringStatus}, " +
                    "monitoringLostAt=${context?.monitoringLostAt}, " +
                    "segment=${active?.type}, startedAt=${active?.startedAt}, " +
                    "segmentCount=${db.focusDao().listSegments(session.id).size}",
            ))
            throw failure
        }

        // This is the prerequisite for closeout, not a fixture that fabricates durable loss.
        val context = db.focusDao().getContext(session.id)!!
        val segments = db.focusDao().listSegments(session.id)
        assertEquals(MonitoringCoverage.PARTIAL, context.monitoringStatus)
        assertEquals(2_000L, context.monitoringLostAt)
        assertEquals(listOf(SessionSegmentType.FOCUS, SessionSegmentType.UNMONITORED), segments.map { it.type })
        assertEquals(2_000L, segments.first().endedAt)
        assertEquals(2_000L, segments.last().startedAt)
        assertNull(segments.last().endedAt)
        assertEquals(1, segments.last().activeSlot)
    }

    @Test fun backwardLossUpperBoundIsNotAvailableToOrdinaryTransitions() = runTest {
        val session = startMonitored()
        wallNow = 100
        focus().markMonitoringLost(session.id, lastTrustedAt = 2_000, detectedAt = 2_000)
        val before = db.focusDao().listSegments(session.id)

        assertIllegalBoundary {
            focus().transition(SegmentTransitionCommand(session.id, SessionSegmentType.BREAK, 2_001, plannedEndAt = 3_000))
        }

        assertEquals(before, db.focusDao().listSegments(session.id))
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(session.id)?.monitoringStatus)
    }

    @Test fun backwardLossStillRejectsUnobservedFutureAndReversedBoundaries() = runTest {
        val session = startMonitored()
        wallNow = 100

        assertIllegalBoundary { focus().markMonitoringLost(session.id, 2_000, 2_001) }
        assertIllegalBoundary { focus().markMonitoringLost(session.id, 2_000, 1_999) }

        assertEquals(MonitoringCoverage.FULL, db.focusDao().getContext(session.id)?.monitoringStatus)
        assertEquals(SessionSegmentType.FOCUS, db.focusDao().getActiveSegment(session.id)?.type)
        assertEquals(1, db.focusDao().listSegments(session.id).size)
    }

    @Test fun normalMonitoringGapStillUsesRealBoundary() = runTest {
        val session = startMonitored()
        wallNow = 8_000

        focus().markMonitoringLost(session.id, lastTrustedAt = 5_000, detectedAt = 8_000)

        val segments = db.focusDao().listSegments(session.id)
        assertEquals(listOf(SessionSegmentType.FOCUS, SessionSegmentType.UNMONITORED), segments.map { it.type })
        assertEquals(5_000L, segments.first().endedAt)
        assertEquals(5_000L, segments.last().startedAt)
        assertNull(segments.last().endedAt)
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(session.id)?.monitoringStatus)
        assertEquals(5_000L, db.focusDao().getContext(session.id)?.monitoringLostAt)
    }

    @Test fun repeatedBackwardLossRetainsOneUnknownSegmentAndPartialCoverage() = runTest {
        val session = startMonitored()
        wallNow = 100
        focus().markMonitoringLost(session.id, 2_000, 2_000)
        val segments = db.focusDao().listSegments(session.id)
        val context = db.focusDao().getContext(session.id)

        repeat(2) { focus().markMonitoringLost(session.id, 2_000, 2_000) }

        assertEquals(segments, db.focusDao().listSegments(session.id))
        assertEquals(context, db.focusDao().getContext(session.id))
        assertEquals(MonitoringCoverage.PARTIAL, context?.monitoringStatus)
    }

    @Test fun backwardLossDoesNotUpgradeUnmonitoredSession() = runTest {
        val session = startUnmonitored()
        wallNow = 100

        repeat(2) { focus().markMonitoringLost(session.id, 1_000, 1_000) }

        assertEquals(MonitoringCoverage.NONE, db.focusDao().getContext(session.id)?.monitoringStatus)
        assertEquals(SessionSegmentType.UNMONITORED, db.focusDao().getActiveSegment(session.id)?.type)
        assertEquals(1, db.focusDao().listSegments(session.id).size)
    }

    @Test fun backwardClockRecoveryNeverEndsBeforeDurableTimeline() = runTest {
        val session = startMonitored()
        wallNow = 2_000
        focus().updateHeartbeat(session.id, wallNow)
        // Isolated Room fixture: emulate facts already durably written before process death.
        // The separate controller test above proves the real runtime loss write path.
        val active = db.focusDao().getActiveSegment(session.id)!!
        assertEquals(1, db.focusDao().closeActiveSegment(active.id, session.id, 2_000))
        db.focusDao().insertSegment(unknown(session.id, startedAt = 2_000))
        db.focusDao().setCoverage(session.id, MonitoringCoverage.PARTIAL, 2_000, 2_000)
        wallNow = 100

        workflow().recoverInterruptedSession()

        val recovered = db.sessionDao().get(session.id)!!
        val segments = db.focusDao().listSegments(session.id)
        val context = db.focusDao().getContext(session.id)
        assertEquals(SessionEndType.ABNORMAL, recovered.endType)
        assertEquals(2_000L, recovered.endedAt)
        assertNull(recovered.activeSlot)
        assertNull(db.focusDao().getActiveSegment(session.id))
        assertEquals(listOf(SessionSegmentType.FOCUS), segments.map { it.type })
        assertEquals(recovered.endedAt, segments.single().endedAt)
        assertEquals(MonitoringCoverage.PARTIAL, context?.monitoringStatus)
        assertEquals(2_000L, context?.lastHeartbeatAt)
        assertEquals(2_000L, context?.monitoringLostAt)
        assertEquals(2_000L, context?.updatedAt)

        workflow().recoverInterruptedSession()
        assertEquals(recovered, db.sessionDao().get(session.id))
        assertEquals(segments, db.focusDao().listSegments(session.id))
        assertEquals(context, db.focusDao().getContext(session.id))
    }

    @Test fun ordinaryRecoveryPreservesHeartbeatAndUsesCurrentWall() = runTest {
        val session = startMonitored()
        wallNow = 2_500
        focus().updateHeartbeat(session.id, wallNow)
        wallNow = 4_000

        workflow().recoverInterruptedSession()

        val recovered = db.sessionDao().get(session.id)!!
        val segments = db.focusDao().listSegments(session.id)
        assertEquals(SessionEndType.ABNORMAL, recovered.endType)
        assertEquals(4_000L, recovered.endedAt)
        assertEquals(listOf(SessionSegmentType.FOCUS, SessionSegmentType.UNMONITORED), segments.map { it.type })
        assertEquals(2_500L, segments.first().endedAt)
        assertEquals(2_500L, segments.last().startedAt)
        assertEquals(recovered.endedAt, segments.last().endedAt)
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(session.id)?.monitoringStatus)
    }

    @Test fun recoveryAtLastHeartbeatClosesFocusWithoutZeroDurationUnknown() = runTest {
        val session = startMonitored()
        wallNow = 2_000
        focus().updateHeartbeat(session.id, wallNow)
        wallNow = 100

        workflow().recoverInterruptedSession()

        val segments = db.focusDao().listSegments(session.id)
        assertEquals(2_000L, db.sessionDao().get(session.id)?.endedAt)
        assertEquals(listOf(SessionSegmentType.FOCUS), segments.map { it.type })
        assertEquals(2_000L, segments.single().endedAt)
        assertTrue(segments.all { it.endedAt!! > it.startedAt })
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(session.id)?.monitoringStatus)
    }

    @Test fun recoveryNeverPrecedesActiveSegmentStartWhenHeartbeatIsOlder() = runTest {
        val session = startMonitored()
        wallNow = 2_000
        focus().transition(SegmentTransitionCommand(session.id, SessionSegmentType.BREAK, wallNow, plannedEndAt = 302_000))
        wallNow = 100

        workflow().recoverInterruptedSession()

        val segments = db.focusDao().listSegments(session.id)
        assertEquals(2_000L, db.sessionDao().get(session.id)?.endedAt)
        assertEquals(listOf(SessionSegmentType.FOCUS), segments.map { it.type })
        assertEquals(2_000L, segments.single().endedAt)
        assertNull(db.focusDao().getActiveSegment(session.id))
    }

    @Test fun recoveryNeverPrecedesDurableMonitoringLossBoundary() = runTest {
        val session = startMonitored()
        db.focusDao().setCoverage(session.id, MonitoringCoverage.PARTIAL, 2_500, 2_500)
        wallNow = 100

        workflow().recoverInterruptedSession()

        val segments = db.focusDao().listSegments(session.id)
        assertEquals(2_500L, db.sessionDao().get(session.id)?.endedAt)
        assertEquals(SessionSegmentType.UNMONITORED, segments.single().type)
        assertEquals(2_500L, segments.single().endedAt)
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(session.id)?.monitoringStatus)
        assertEquals(2_500L, db.focusDao().getContext(session.id)?.monitoringLostAt)
    }

    @Test fun recoveryAtSessionStartDoesNotInventOneMillisecondOrUpgradeNone() = runTest {
        val session = startUnmonitored()
        wallNow = 100

        workflow().recoverInterruptedSession()

        val recovered = db.sessionDao().get(session.id)!!
        assertEquals(SessionEndType.ABNORMAL, recovered.endType)
        assertEquals(session.startedAt, recovered.endedAt)
        assertTrue(db.focusDao().listSegments(session.id).isEmpty())
        assertEquals(MonitoringCoverage.NONE, db.focusDao().getContext(session.id)?.monitoringStatus)
        assertNull(db.focusDao().getActiveSegment(session.id))
    }

    private fun workflow() = DefaultStudyWorkflowRepository(
        db, RuleBasedSummaryEngine(), IntentExpiryPolicy(), clock = { wallNow },
    )

    private fun focus() = DefaultFocusRepository(db, clock = { wallNow })

    private suspend fun startMonitored(): StudySessionEntity {
        val intent = workflow().createIntent(DefaultLearningItemRepository(db, clock = { wallNow }).create("Clock fixture", 100, 10).id)
        return workflow().startMonitoredSession(intent.id, 10,
            MonitoringReadyLease("clock-fixture", wallNow, 0, 1, wallNow, 0, 0, false, false),
            proposedSessionId = "clock-fixture-session").session
    }

    private suspend fun startUnmonitored(): StudySessionEntity {
        val intent = workflow().createIntent(DefaultLearningItemRepository(db, clock = { wallNow }).create("Clock fixture", 100, 10).id)
        return workflow().startSession(intent.id, 10)
    }

    private fun unknown(sessionId: String, startedAt: Long) = SessionSegmentEntity(
        id = "clock-unknown", sessionId = sessionId, type = SessionSegmentType.UNMONITORED,
        startedAt = startedAt, endedAt = null, packageName = null, reason = null,
        plannedEndAt = null, extensionCount = 0, relatedSegmentId = null, activeSlot = 1,
    )

    private suspend fun assertIllegalBoundary(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected illegal time boundary")
        } catch (_: IllegalArgumentException) {
            // Only the strict time guard is accepted, not an unrelated state failure.
        }
    }
}
