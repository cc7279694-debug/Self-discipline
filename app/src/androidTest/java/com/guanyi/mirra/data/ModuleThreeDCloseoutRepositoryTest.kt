package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.domain.monitoring.BackwardClockCloseoutEvidence
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
import com.guanyi.mirra.domain.DefaultSessionManager
import com.guanyi.mirra.domain.SessionFinishResult
import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import com.guanyi.mirra.domain.monitoring.RuntimeFactsPort
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import org.junit.Assert.assertFalse
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
import java.util.UUID

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

    @Test fun pendingRecoveryReopensDurableDatabaseAndRetainsOriginalDecision() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "closeout-reopen-test-${UUID.randomUUID()}.db"
        db.close()
        db = Room.databaseBuilder(context, MirraDatabase::class.java, name).build()
        try {
            val s = startUnmonitored()
            wallNow = 2_000
            val frozen = workflow().beginCloseout(s.id, 20, wallNow)
            db.close()
            db = Room.databaseBuilder(context, MirraDatabase::class.java, name).build()
            wallNow = 99_000
            assertEquals(frozen, workflow().getCloseoutSnapshot(s.id))
            assertEquals(FocusCloseoutState.PENDING, workflow().getCloseoutState(s.id))
            assertEquals(com.guanyi.mirra.domain.SessionRecoveryResult.Ready, workflow().recoverInterruptedSession())
            val ended = db.sessionDao().get(s.id)!!
            assertEquals(SessionEndType.NORMAL, ended.endType)
            assertEquals(2_000L, ended.endedAt); assertEquals(20, ended.endPage)
            assertEquals(FocusCloseoutState.COMPLETED, workflow().getCloseoutState(s.id))
            assertNull(db.sessionDao().getActive()); assertNull(db.focusDao().getActiveSegment(s.id))
            assertEquals(frozen, workflow().getCloseoutSnapshot(s.id))
        } finally {
            db.close()
            // Only this isolated, UUID-named test database; never the installed mirra.db.
            context.deleteDatabase(name)
        }
    }

    @Test fun beginCloseoutFreezesExactSampleAndReleasesOnlySegmentSlot() = runTest {
        for (type in SessionSegmentType.entries) {
            wallNow = 1_000
            val s = startUnmonitored()
            val active = db.focusDao().getActiveSegment(s.id)!!
            db.focusDao().changeActiveSegmentType(active.id, s.id, type)
            wallNow = 99_000 // Repository must not use this later clock as the end boundary.
            val snapshot = workflow().beginCloseout(s.id, 20, 2_000)
            assertEquals(2_000L, snapshot.closeoutStartedAt)
            assertEquals(20, snapshot.requestedEndPage)
            assertEquals(FocusCloseoutState.PENDING, workflow().getCloseoutState(s.id))
            assertEquals(1, db.sessionDao().get(s.id)?.activeSlot)
            assertNull(db.focusDao().getActiveSegment(s.id))
            assertEquals(2_000L, db.focusDao().listSegments(s.id).single().endedAt)
            val ended = workflow().completeCloseout(s.id)
            assertEquals(2_000L, ended.endedAt)
            assertEquals(SessionEndType.NORMAL, ended.endType)
            assertEquals(FocusCloseoutState.COMPLETED, workflow().getCloseoutState(s.id))
        }
    }

    @Test fun sameBoundaryCloseoutDeletesZeroDurationSegmentWithoutInventedMillis() = runTest {
        val s = startMonitored()
        wallNow = 2_000
        focus().transition(SegmentTransitionCommand(s.id, SessionSegmentType.BREAK, wallNow, plannedEndAt = 302_000))
        val closed = db.focusDao().listSegments(s.id).first()
        workflow().beginCloseout(s.id, 10, 2_000)
        assertEquals(listOf(closed), db.focusDao().listSegments(s.id))
        assertEquals(2_000L, workflow().completeCloseout(s.id).endedAt)
    }

    @Test fun backwardClockJumpStillAllowsCloseoutAtLastDurableBoundary() = runTest {
        val s = startMonitored()
        wallNow = 100
        focus().markMonitoringLost(s.id, 2_000, 2_000)
        val unknown = db.focusDao().getActiveSegment(s.id)!!
        val proof = BackwardClockCloseoutEvidence(s.id, ClockSample(2_000, 1_000), ClockSample(100, 2_000), 2_000, unknown.id)
        val snap = workflow().beginCloseout(s.id, 20, 100, proof)
        assertEquals(2_000L, snap.closeoutStartedAt)
        wallNow = 500_000
        val ended = workflow().completeCloseout(s.id)
        assertEquals(2_000L, ended.endedAt)
        assertEquals(SessionEndType.NORMAL, ended.endType)
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(s.id)?.monitoringStatus)
        assertEquals(2_000L, db.focusDao().getContext(s.id)?.monitoringLostAt)
        assertEquals(listOf(SessionSegmentType.FOCUS), db.focusDao().listSegments(s.id).map { it.type })
    }

    @Test fun ordinaryBoundaryBeforeActiveSegmentStillFailsWithoutClockJumpEvidence() = runTest {
        val s = startMonitored()
        wallNow = 3_000
        focus().markMonitoringLost(s.id, 2_000, 3_000)
        val context = db.focusDao().getContext(s.id)
        val segments = db.focusDao().listSegments(s.id)
        val active = db.focusDao().getActiveSegment(s.id)!!
        val invalidProofs = listOf(
            null,
            BackwardClockCloseoutEvidence("other", ClockSample(2_000, 1_000), ClockSample(100, 2_000), 2_000, active.id),
            BackwardClockCloseoutEvidence(s.id, ClockSample(2_000, 2_000), ClockSample(100, 1_000), 2_000, active.id),
            BackwardClockCloseoutEvidence(s.id, ClockSample(2_000, 1_000), ClockSample(100, 2_000), 1_999, active.id),
            BackwardClockCloseoutEvidence(s.id, ClockSample(2_000, 1_000), ClockSample(100, 2_000), 2_000, "stale"),
        )
        for (proof in invalidProofs) assertIllegalBoundary { workflow().beginCloseout(s.id, 20, 100, proof) }
        assertEquals(context, db.focusDao().getContext(s.id))
        assertEquals(segments, db.focusDao().listSegments(s.id))
        assertEquals(s, db.sessionDao().get(s.id))
    }

    @Test fun endPageBelowPersistedProgressIsRejected() = runTest {
        val s = startUnmonitored()
        workflow().updateCurrentPage(s.id, 42)
        for (page in listOf(40, 101, 0)) assertIllegalBoundary { workflow().beginCloseout(s.id, page, 2_000) }
        assertEquals(FocusCloseoutState.ACTIVE, db.focusDao().getContext(s.id)?.closeoutState)
        assertEquals(42, db.sessionDao().get(s.id)?.currentPage)
    }

    @Test fun beginFailureRollsBackDecisionButRetainsPreviouslyCommittedMonitoringLoss() = runTest {
        val s = startMonitored()
        wallNow = 3_000
        focus().markMonitoringLost(s.id, 2_000, wallNow)
        val context = db.focusDao().getContext(s.id)
        val segments = db.focusDao().listSegments(s.id)
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_begin BEFORE UPDATE OF closeoutState ON session_focus_contexts BEGIN SELECT RAISE(ABORT, 'injected begin'); END")
        expectFailure { workflow().beginCloseout(s.id, 20, 3_000) }
        assertEquals(context, db.focusDao().getContext(s.id))
        assertEquals(segments, db.focusDao().listSegments(s.id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_begin")
    }

    @Test fun completeFailureRetainsPendingAndRetryUsesOriginalBoundary() = runTest {
        val s = startUnmonitored()
        val snap = workflow().beginCloseout(s.id, 20, 2_000)
        val itemBefore = db.learningItemDao().get(s.learningItemId)
        val indexBefore = sessionIndex(s.id)
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_complete BEFORE UPDATE OF closeoutState ON session_focus_contexts WHEN NEW.closeoutState = 'COMPLETED' BEGIN SELECT RAISE(ABORT, 'injected complete'); END")
        expectFailure { workflow().completeCloseout(s.id) }
        assertEquals(s, db.sessionDao().get(s.id))
        assertEquals(itemBefore, db.learningItemDao().get(s.learningItemId))
        assertEquals(indexBefore, sessionIndex(s.id))
        assertEquals(snap, workflow().getCloseoutSnapshot(s.id))
        assertEquals(FocusCloseoutState.PENDING, workflow().getCloseoutState(s.id))
        assertEquals(10, db.learningItemDao().get(s.learningItemId)?.currentPage)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_complete")
        wallNow = 999_000
        val result = workflow().completeCloseout(s.id)
        assertEquals(2_000L, result.endedAt)
        assertEquals(20, result.endPage)
        assertEquals(20, db.learningItemDao().get(s.learningItemId)?.currentPage)
        assertTrue(result.generatedSummary!!.contains("20"))
        val indexAfter = sessionIndex(s.id)
        assertEquals(1, indexAfter.size)
        assertTrue(indexAfter.single().contains(result.generatedSummary))
        assertEquals(FocusCloseoutState.COMPLETED, workflow().getCloseoutState(s.id))
        assertEquals(result, workflow().completeCloseout(s.id))
        assertEquals(indexAfter, sessionIndex(s.id))
    }

    @Test fun duplicateBeginAndCompleteNeverRewriteBoundary() = runTest {
        val s = startUnmonitored()
        val snap = workflow().beginCloseout(s.id, 20, 2_000)
        assertEquals(snap, workflow().beginCloseout(s.id, 90, 99_000))
        val result = workflow().completeCloseout(s.id)
        val index = sessionIndex(s.id)
        assertEquals(snap, workflow().beginCloseout(s.id, 90, 999_000))
        assertEquals(result, workflow().completeCloseout(s.id))
        assertEquals(index, sessionIndex(s.id))
    }

    private fun sessionIndex(sessionId: String): List<String> =
        db.openHelper.writableDatabase.query(
            "SELECT searchableText FROM search_fts WHERE entityType = 'SESSION' AND entityId = ?", arrayOf(sessionId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    @Test fun startupCompletesPendingBeforeAbnormalRecovery() = runTest {
        val s = startUnmonitored()
        workflow().beginCloseout(s.id, 20, 2_000)
        val segments = db.focusDao().listSegments(s.id)
        wallNow = 99_000
        assertEquals(com.guanyi.mirra.domain.SessionRecoveryResult.Ready, workflow().recoverInterruptedSession())
        val ended = db.sessionDao().get(s.id)!!
        assertEquals(SessionEndType.NORMAL, ended.endType)
        assertEquals(2_000L, ended.endedAt)
        assertEquals(20, ended.endPage)
        assertEquals(FocusCloseoutState.COMPLETED, workflow().getCloseoutState(s.id))
        assertEquals(segments, db.focusDao().listSegments(s.id))
        assertEquals(1, sessionIndex(s.id).size)
        workflow().recoverInterruptedSession()
        assertEquals(ended, db.sessionDao().get(s.id))
    }

    @Test fun failedPendingRecoveryNeverBecomesAbnormalAndStillExpiresOldIntent() = runTest {
        val s = startUnmonitored()
        val snap = workflow().beginCloseout(s.id, 20, 2_000)
        // Isolated fixture for independent leftover Intent recovery, not user data.
        db.intentDao().insert(com.guanyi.mirra.data.local.entity.StudyIntentEntity("leftover-intent", s.learningItemId, 1_000, null, null, null, null, 1))
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_complete BEFORE UPDATE OF closeoutState ON session_focus_contexts WHEN NEW.closeoutState = 'COMPLETED' BEGIN SELECT RAISE(ABORT, 'injected complete'); END")
        wallNow = 3_000_000
        val result = workflow().recoverInterruptedSession()
        assertTrue(result is com.guanyi.mirra.domain.SessionRecoveryResult.PendingRetry)
        assertEquals(s, db.sessionDao().get(s.id))
        assertEquals(snap, workflow().getCloseoutSnapshot(s.id))
        assertEquals(FocusCloseoutState.PENDING, workflow().getCloseoutState(s.id))
        assertNull(db.focusDao().getActiveSegment(s.id))
        assertEquals(com.guanyi.mirra.data.local.entity.IntentOutcome.TIMEOUT, db.intentDao().get("leftover-intent")?.outcome)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_complete")
        assertEquals(com.guanyi.mirra.domain.SessionRecoveryResult.Ready, workflow().recoverInterruptedSession())
        assertEquals(2_000L, db.sessionDao().get(s.id)?.endedAt)
        assertEquals(SessionEndType.NORMAL, db.sessionDao().get(s.id)?.endType)
    }

    @Test fun finalSettlementBackwardClockUsesOnlyDurableLossBoundary() = runTest {
        verifyRuntimeBackwardCloseout(alreadyObserved = false)
    }

    @Test fun previouslyObservedBackwardClockStillAllowsPreciseCloseout() = runTest {
        verifyRuntimeBackwardCloseout(alreadyObserved = true)
    }

    private suspend fun verifyRuntimeBackwardCloseout(alreadyObserved: Boolean) {
        val s = startMonitored()
        val controller = BoundSessionMonitoringController(RepositoryRuntimeFactsPort(focus()))
        val binding = MonitoringBinding(s.id, "clock-fixture", 0)
        val trusted = ClockSample(2_000, 1_000)
        wallNow = trusted.wallNowMillis
        controller.onSample(binding, MonitorSnapshot(running = true, queryGeneration = 2,
            lastSuccessfulQueryElapsed = 1_000, lastSuccessfulQueryClock = trusted), trusted)
        val final = ClockSample(100, 2_000)
        wallNow = final.wallNowMillis
        if (alreadyObserved) controller.onSample(binding, MonitorSnapshot(running = false,
            queryGeneration = 2, lastSuccessfulQueryElapsed = 1_000, lastSuccessfulQueryClock = trusted,
            signals = setOf(MonitoringSignal.WALL_CLOCK_JUMP)), final)
        val manager = DefaultSessionManager(workflow(), closeoutWithMonitoringFacts = { id, sample, block ->
            controller.closeoutWithFacts(id, sample, block)
        })
        val result = manager.finish(s.id, 20, final) as SessionFinishResult.Completed
        assertEquals(2_000L, result.session.endedAt)
        assertEquals(SessionEndType.NORMAL, result.session.endType)
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(s.id)?.monitoringStatus)
        assertEquals(2_000L, db.focusDao().getContext(s.id)?.monitoringLostAt)
        assertEquals(listOf(SessionSegmentType.FOCUS), db.focusDao().listSegments(s.id).map { it.type })
        assertEquals(result, manager.retryPendingFinish(s.id))
    }

    @Test fun realSixSecondGapSettlesBeforeCloseout() = runTest {
        val s = startMonitored()
        val controller = BoundSessionMonitoringController(RepositoryRuntimeFactsPort(focus()))
        wallNow = 2_000
        controller.onSample(MonitoringBinding(s.id, "clock-fixture", 0), MonitorSnapshot(running = true,
            queryGeneration = 2, lastSuccessfulQueryElapsed = 1_000, lastSuccessfulQueryClock = ClockSample(2_000, 1_000)), ClockSample(2_000, 1_000))
        wallNow = 8_000
        val manager = DefaultSessionManager(workflow(), closeoutWithMonitoringFacts = { id, sample, block -> controller.closeoutWithFacts(id, sample, block) })
        manager.finish(s.id, 20, ClockSample(8_000, 7_000))
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(s.id)?.monitoringStatus)
        val segments = db.focusDao().listSegments(s.id)
        assertEquals(listOf(SessionSegmentType.FOCUS, SessionSegmentType.UNMONITORED), segments.map { it.type })
        assertEquals(2_000L, segments.last().startedAt)
        assertEquals(8_000L, segments.last().endedAt)
    }

    @Test fun olderConfirmSampleDoesNotInventMonitoringLoss() = runTest {
        val s = startMonitored()
        val controller = BoundSessionMonitoringController(RepositoryRuntimeFactsPort(focus()))
        wallNow = 2_001
        controller.onSample(MonitoringBinding(s.id, "clock-fixture", 0), MonitorSnapshot(running = true,
            queryGeneration = 2, lastSuccessfulQueryElapsed = 1_001, lastSuccessfulQueryClock = ClockSample(2_001, 1_001)), ClockSample(2_001, 1_001))
        val manager = DefaultSessionManager(workflow(), closeoutWithMonitoringFacts = { id, sample, block -> controller.closeoutWithFacts(id, sample, block) })
        manager.finish(s.id, 20, ClockSample(2_000, 1_000))
        assertEquals(2_000L, db.sessionDao().get(s.id)?.endedAt)
        assertEquals(MonitoringCoverage.FULL, db.focusDao().getContext(s.id)?.monitoringStatus)
        assertEquals(listOf(SessionSegmentType.FOCUS), db.focusDao().listSegments(s.id).map { it.type })
    }

    @Test fun newerDurableRiskConfirmationRejectsOlderCloseoutBoundary() = runTest {
        val s = startMonitored()
        val active = db.focusDao().getActiveSegment(s.id)!!
        // Real Room fixture: confirmation is later than its backdated distraction start.
        db.focusDao().changeActiveSegmentToRisk(active.id, s.id, SessionSegmentType.DISTRACTION, "risk")
        val event = com.guanyi.mirra.data.local.entity.FocusEventEntity("risk-confirmed", s.id,
            com.guanyi.mirra.data.local.entity.FocusEventType.RISK_APP_CONFIRMED, 2_001, "risk", active.id, null)
        db.focusDao().insertEvent(event)
        assertOlderDecisionRejected(s, 2_000)
        assertEquals(event, db.focusDao().listEvents(s.id).single())
        assertEquals(1_000L, db.focusDao().getActiveSegment(s.id)?.startedAt)
        assertFreshDecisionIncludesFacts(s, 2_001)
    }

    @Test fun newerDurableHeartbeatRejectsOlderCloseoutBoundary() = runTest {
        val s = startMonitored()
        wallNow = 2_001
        focus().updateHeartbeat(s.id, wallNow)
        assertOlderDecisionRejected(s, 2_000)
        assertEquals(2_001L, db.focusDao().getContext(s.id)?.lastHeartbeatAt)
        assertFreshDecisionIncludesFacts(s, 2_001)
    }

    @Test fun newerDurableStableMilestoneRejectsOlderCloseoutBoundary() = runTest {
        val s = startMonitored()
        // Isolate the existing milestone timestamp from heartbeat/event timestamps.
        assertEquals(1, db.focusDao().markStableStarted(s.id, 2_001))
        assertOlderDecisionRejected(s, 2_000)
        assertEquals(2_001L, db.sessionDao().get(s.id)?.stableStartedAt)
        assertFreshDecisionIncludesFacts(s, 2_001)
    }

    @Test fun newerPresentationReceiptCannotRemainAfterClosedSessionBoundary() = runTest {
        val s = startMonitored()
        val active = db.focusDao().getActiveSegment(s.id)!!
        db.focusDao().changeActiveSegmentToRisk(active.id, s.id, SessionSegmentType.DISTRACTION, "risk")
        db.focusDao().insertEvent(com.guanyi.mirra.data.local.entity.FocusEventEntity("risk", s.id,
            com.guanyi.mirra.data.local.entity.FocusEventType.RISK_APP_CONFIRMED, 1_500, "risk", active.id, null))
        val prompt = com.guanyi.mirra.domain.monitoring.InterventionUiModel(s.id, "risk", "risk", active.id, "risk", 0, 0)
        val receipts = com.guanyi.mirra.data.repository.InterventionReceiptRepository(db, { it == prompt }, { 2_001 })
        assertTrue(receipts.record(prompt, com.guanyi.mirra.domain.intervention.InterventionDeliveryReceipt.IN_APP_PRESENTED))
        assertOlderDecisionRejected(s, 2_000)
        assertEquals(2_001L, db.focusDao().listEvents(s.id).last().occurredAt)
        assertFreshDecisionIncludesFacts(s, 2_001)
    }

    @Test fun newerDndMetadataDoesNotMoveLearningEndBoundary() = runTest {
        val s = startMonitored()
        db.focusDao().setDndLifecycle(s.id, com.guanyi.mirra.data.local.entity.DndLifecycle.APPLY_FAILED, 99_000)
        assertEquals(99_000L, db.focusDao().getContext(s.id)?.updatedAt)
        assertFreshDecisionIncludesFacts(s, 2_000)
    }

    @Test fun backwardProofRejectsEventBeyondDurableLossBoundary() = runTest {
        verifyBackwardProofRejectsLaterFact { s ->
            db.focusDao().insertEvent(com.guanyi.mirra.data.local.entity.FocusEventEntity("later-event", s.id,
                com.guanyi.mirra.data.local.entity.FocusEventType.INTERVENTION_SHOWN, 2_001, null, null, null))
        }
    }

    @Test fun backwardProofRejectsHeartbeatBeyondDurableLossBoundary() = runTest {
        verifyBackwardProofRejectsLaterFact { s -> db.focusDao().updateHeartbeat(s.id, 2_001) }
    }

    @Test fun backwardProofRejectsStableFactBeyondDurableLossBoundary() = runTest {
        verifyBackwardProofRejectsLaterFact { s -> db.focusDao().markStableStarted(s.id, 2_001) }
    }

    private suspend fun verifyBackwardProofRejectsLaterFact(addFact: suspend (StudySessionEntity) -> Unit) {
        val s = startMonitored()
        wallNow = 100
        focus().markMonitoringLost(s.id, 2_000, 2_000)
        val active = db.focusDao().getActiveSegment(s.id)!!
        addFact(s)
        val before = db.sessionDao().get(s.id)!!
        val proof = BackwardClockCloseoutEvidence(s.id, ClockSample(2_000, 1_000), ClockSample(100, 2_000), 2_000, active.id)
        assertOlderDecisionRejected(before, 100, proof)
    }

    @Test fun newerMonitoringCommitPrecedesOlderFinalSampleAtControllerMutex() = runTest {
        focus().replaceRiskApp("risk", "Test risk app")
        val s = startMonitored()
        val real = RepositoryRuntimeFactsPort(focus())
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val port = object : RuntimeFactsPort by real {
            override suspend fun confirm(command: com.guanyi.mirra.data.repository.RiskConfirmation): Boolean {
                val result = real.confirm(command)
                check(result)
                committed.complete(Unit)
                release.await() // Hold the real controller mutex after the real Room commit.
                return result
            }
        }
        val controller = BoundSessionMonitoringController(port)
        val binding = MonitoringBinding(s.id, "clock-fixture", 0)
        suspend fun observe(at: Long, generation: Long) {
            wallNow = at
            val sample = ClockSample(at, at - 1_000)
            controller.onSample(binding, MonitorSnapshot(running = true, queryGeneration = generation,
                lastSuccessfulQueryElapsed = sample.elapsedNowMillis, lastSuccessfulQueryClock = sample,
                observation = com.guanyi.mirra.domain.monitoring.ForegroundObservation.Package("risk", sample.elapsedNowMillis, 10_001)), sample)
        }
        observe(10_001, 2)
        observe(13_000, 3)
        observe(16_000, 4)
        observe(19_000, 5)
        val manager = DefaultSessionManager(workflow(), closeoutWithMonitoringFacts = { id, sample, block ->
            controller.closeoutWithFacts(id, sample, block)
        })
        val olderFinalSample = ClockSample(20_000, 19_000)
        val observing = async(Dispatchers.IO) { observe(20_001, 6) }
        var closing: kotlinx.coroutines.Deferred<Result<SessionFinishResult>>? = null
        try {
            withContext(Dispatchers.IO) { withTimeout(5_000) { committed.await() } }
            val events = db.focusDao().listEvents(s.id)
            assertTrue(events.any { it.type == com.guanyi.mirra.data.local.entity.FocusEventType.RISK_APP_CONFIRMED && it.occurredAt == 20_001L })
            // Undispatched execution reaches the occupied controller mutex before returning.
            val queuedClose = async(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                runCatching { manager.finish(s.id, 20, olderFinalSample) }
            }
            closing = queuedClose
            release.complete(Unit)
            observing.await()
            val failure = queuedClose.await().exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("阅读状态刚刚更新，请再次确认结束", failure?.message)
            assertEquals(FocusCloseoutState.ACTIVE, workflow().getCloseoutState(s.id))
            assertNull(db.sessionDao().get(s.id)?.endedAt)
            assertEquals(events, db.focusDao().listEvents(s.id))
            assertEquals(SessionSegmentType.DISTRACTION, db.focusDao().getActiveSegment(s.id)?.type)
            assertEquals(10_001L, db.focusDao().getActiveSegment(s.id)?.startedAt)
            assertEquals(MonitoringCoverage.FULL, db.focusDao().getContext(s.id)?.monitoringStatus)
            manager.finish(s.id, 20, ClockSample(20_001, 19_001))
            assertEquals(20_001L, db.sessionDao().get(s.id)?.endedAt)
            assertTrue(db.focusDao().listEvents(s.id).all { it.occurredAt <= 20_001 })
        } finally {
            release.complete(Unit)
            observing.cancelAndJoin()
            closing?.cancelAndJoin()
        }
    }

    private suspend fun assertOlderDecisionRejected(s: StudySessionEntity, at: Long,
        proof: BackwardClockCloseoutEvidence? = null) {
        val context = db.focusDao().getContext(s.id)
        val session = db.sessionDao().get(s.id)
        val segments = db.focusDao().listSegments(s.id)
        val events = db.focusDao().listEvents(s.id)
        assertIllegalBoundary { workflow().beginCloseout(s.id, 20, at, proof) }
        assertEquals(context, db.focusDao().getContext(s.id))
        assertEquals(segments, db.focusDao().listSegments(s.id))
        assertEquals(events, db.focusDao().listEvents(s.id))
        assertEquals(session, db.sessionDao().get(s.id))
        assertEquals(FocusCloseoutState.ACTIVE, workflow().getCloseoutState(s.id))
        assertNull(db.sessionDao().get(s.id)?.endedAt)
    }

    private suspend fun assertFreshDecisionIncludesFacts(s: StudySessionEntity, at: Long) {
        val events = db.focusDao().listEvents(s.id)
        val frozen = workflow().beginCloseout(s.id, 20, at)
        assertEquals(at, frozen.closeoutStartedAt)
        assertEquals(at, workflow().completeCloseout(s.id).endedAt)
        assertEquals(events, db.focusDao().listEvents(s.id))
        assertTrue(events.all { it.occurredAt <= at })
    }

    @Test fun blockedAndroidCleanupDoesNotHoldFactsMutexOrRoomTransaction() = runTest {
        val s = startMonitored()
        val controller = BoundSessionMonitoringController(RepositoryRuntimeFactsPort(focus()))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        wallNow = 2_000
        val manager = DefaultSessionManager(workflow(), closeoutWithMonitoringFacts = { id, sample, block -> controller.closeoutWithFacts(id, sample, block) },
            cleanupClosedSession = {
                assertFalse(db.inTransaction())
                entered.complete(Unit)
                release.await()
            })
        val finish = async(Dispatchers.IO) { manager.finish(s.id, 20, ClockSample(2_000, 1_000)) }
        try {
            withContext(Dispatchers.IO) { withTimeout(5_000) { entered.await() } }
            withContext(Dispatchers.IO) { withTimeout(5_000) { controller.refresh(s.id, ClockSample(3_000, 2_000)) } }
            assertNull(db.focusDao().getActiveSegment(s.id))
            assertNull(db.sessionDao().get(s.id)?.endedAt)
            assertNull(db.sessionDao().get(s.id)?.endType)
            assertEquals(1, db.sessionDao().get(s.id)?.activeSlot)
            assertEquals(FocusCloseoutState.PENDING, workflow().getCloseoutState(s.id))
            assertEquals(2_000L, workflow().getCloseoutSnapshot(s.id)?.closeoutStartedAt)
        } finally { release.complete(Unit) }
        assertTrue(finish.await() is SessionFinishResult.Completed)
        assertEquals(2_000L, db.sessionDao().get(s.id)?.endedAt)
        assertEquals(SessionEndType.NORMAL, db.sessionDao().get(s.id)?.endType)
        assertEquals(FocusCloseoutState.COMPLETED, workflow().getCloseoutState(s.id))
    }

    @Test fun committedLossWithFailedProofReadCanRetryWithoutRewritingLoss() = runTest {
        val s = startMonitored()
        val delegate = RepositoryRuntimeFactsPort(focus())
        var failRead = true
        val port = object : RuntimeFactsPort by delegate {
            override suspend fun read(sessionId: String): com.guanyi.mirra.data.repository.RuntimeFocusFacts? {
                val facts = delegate.read(sessionId)
                if (facts?.coverage == MonitoringCoverage.PARTIAL && failRead) { failRead = false; error("proof read failed") }
                return facts
            }
        }
        val controller = BoundSessionMonitoringController(port)
        val binding = MonitoringBinding(s.id, "clock-fixture", 0)
        wallNow = 2_000
        controller.onSample(binding, MonitorSnapshot(running = true, queryGeneration = 2,
            lastSuccessfulQueryElapsed = 1_000, lastSuccessfulQueryClock = ClockSample(2_000, 1_000)), ClockSample(2_000, 1_000))
        wallNow = 100
        expectFailure { controller.onSample(binding, MonitorSnapshot(running = false,
            signals = setOf(MonitoringSignal.WALL_CLOCK_JUMP)), ClockSample(100, 2_000)) }
        assertEquals(2_000L, db.focusDao().getContext(s.id)?.monitoringLostAt)
        val manager = DefaultSessionManager(workflow(), closeoutWithMonitoringFacts = { id, sample, block -> controller.closeoutWithFacts(id, sample, block) })
        val completed = manager.finish(s.id, 20, ClockSample(100, 2_000)) as SessionFinishResult.Completed
        assertEquals(2_000L, completed.session.endedAt)
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(s.id)?.monitoringStatus)
    }

    @Test fun actualJobCancellationAfterUnknownBeginCommitInvalidatesAndCleansPending() = runTest {
        val s = startMonitored()
        val base = workflow()
        val committed = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val controller = BoundSessionMonitoringController(RepositoryRuntimeFactsPort(focus()))
        wallNow = 2_000
        controller.refresh(s.id, ClockSample(2_000, 1_000))
        assertEquals(s.id, controller.focusStatus.value.sessionId)
        val hiddenReturn = object : StudyWorkflowRepository by base {
            override suspend fun beginCloseout(sessionId: String, requestedEndPage: Int, closeoutStartedAt: Long,
                backwardClockEvidence: BackwardClockCloseoutEvidence?): com.guanyi.mirra.data.local.model.CloseoutSnapshot {
                base.beginCloseout(sessionId, requestedEndPage, closeoutStartedAt, backwardClockEvidence)
                committed.complete(Unit)
                awaitCancellation()
            }
        }
        val manager = DefaultSessionManager(hiddenReturn, closeoutWithMonitoringFacts = { id, sample, block -> controller.closeoutWithFacts(id, sample, block) },
            cleanupClosedSession = { assertNull(controller.focusStatus.value.sessionId); assertFalse(db.inTransaction()); cleaned.complete(Unit) })
        val job = async(Dispatchers.Default) { manager.finish(s.id, 20, ClockSample(2_000, 1_000)) }
        withContext(Dispatchers.IO) { withTimeout(5_000) { committed.await(); job.cancelAndJoin(); cleaned.await() } }
        assertTrue(job.isCancelled)
        assertEquals(FocusCloseoutState.PENDING, base.getCloseoutState(s.id))
        assertEquals(2_000L, base.getCloseoutSnapshot(s.id)?.closeoutStartedAt)
        assertTrue(manager.retryPendingFinish(s.id) is SessionFinishResult.Completed)
    }

    @Test fun closeoutRacesRiskAndBehaviorWithoutReopeningSegments() = runTest {
        val s = startMonitored()
        val controller = BoundSessionMonitoringController(RepositoryRuntimeFactsPort(focus()))
        val base = workflow()
        val committed = CompletableDeferred<Unit>(); val finishA = CompletableDeferred<Unit>()
        val pausedA = object : StudyWorkflowRepository by base {
            override suspend fun beginCloseout(sessionId: String, requestedEndPage: Int, closeoutStartedAt: Long,
                backwardClockEvidence: BackwardClockCloseoutEvidence?): com.guanyi.mirra.data.local.model.CloseoutSnapshot {
                val result = base.beginCloseout(sessionId, requestedEndPage, closeoutStartedAt, backwardClockEvidence)
                committed.complete(Unit); finishA.await(); return result
            }
        }
        wallNow = 3_000
        val manager = DefaultSessionManager(pausedA, closeoutWithMonitoringFacts = { id, sample, block -> controller.closeoutWithFacts(id, sample, block) })
        val segment = db.focusDao().getActiveSegment(s.id)!!
        val closing = async(Dispatchers.IO) { manager.finish(s.id, 20, ClockSample(2_000, 1_000)) }
        withContext(Dispatchers.IO) { withTimeout(5_000) { committed.await() } }
        val action = async(Dispatchers.IO) { controller.startBreak(s.id, segment.id, 300_000, ClockSample(3_000, 2_000)) }
        val risk = async(Dispatchers.IO) { controller.onSample(MonitoringBinding(s.id, "clock-fixture", 0),
            MonitorSnapshot(running = true, lastSuccessfulQueryElapsed = 2_000, lastSuccessfulQueryClock = ClockSample(3_000, 2_000)), ClockSample(3_000, 2_000)) }
        finishA.complete(Unit); closing.await(); risk.await()
        assertEquals(com.guanyi.mirra.domain.monitoring.FocusActionResult.EXPIRED, action.await())
        assertNull(db.focusDao().getActiveSegment(s.id))
        assertEquals(listOf(SessionSegmentType.FOCUS), db.focusDao().listSegments(s.id).map { it.type })
        assertTrue(db.focusDao().listEvents(s.id).isEmpty())
    }

    @Test fun permissionLossCannotBorrowBackwardProof() = runTest {
        val s = startMonitored()
        val controller = BoundSessionMonitoringController(RepositoryRuntimeFactsPort(focus()))
        val binding = MonitoringBinding(s.id, "clock-fixture", 0)
        wallNow = 2_000
        controller.onSample(binding, MonitorSnapshot(running = true, queryGeneration = 2,
            lastSuccessfulQueryElapsed = 1_000, lastSuccessfulQueryClock = ClockSample(2_000, 1_000)), ClockSample(2_000, 1_000))
        // A real permission/service loss is not observed backward-clock evidence.
        controller.onServiceLost(binding, ClockSample(2_000, 1_000), "permission revoked")
        wallNow = 100
        val manager = DefaultSessionManager(workflow(), closeoutWithMonitoringFacts = { id, sample, block -> controller.closeoutWithFacts(id, sample, block) })
        assertIllegalBoundary { manager.finish(s.id, 20, ClockSample(100, 2_000)) }
        assertEquals(FocusCloseoutState.ACTIVE, workflow().getCloseoutState(s.id))
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(s.id)?.monitoringStatus)
    }

    @Test fun newBindingGenerationCannotBorrowPreviousBackwardProof() = runTest {
        val s = startMonitored()
        val controller = BoundSessionMonitoringController(RepositoryRuntimeFactsPort(focus()))
        val old = MonitoringBinding(s.id, "clock-fixture", 0)
        wallNow = 2_000
        controller.onSample(old, MonitorSnapshot(running = true, queryGeneration = 2,
            lastSuccessfulQueryElapsed = 1_000, lastSuccessfulQueryClock = ClockSample(2_000, 1_000)), ClockSample(2_000, 1_000))
        wallNow = 100
        controller.onSample(old, MonitorSnapshot(running = false, signals = setOf(MonitoringSignal.WALL_CLOCK_JUMP)), ClockSample(100, 2_000))
        controller.onSample(MonitoringBinding(s.id, "different-generation", 2_000), MonitorSnapshot(running = true), ClockSample(100, 2_000))
        val manager = DefaultSessionManager(workflow(), closeoutWithMonitoringFacts = { id, sample, block -> controller.closeoutWithFacts(id, sample, block) })
        assertIllegalBoundary { manager.finish(s.id, 20, ClockSample(100, 2_000)) }
        assertEquals(FocusCloseoutState.ACTIVE, workflow().getCloseoutState(s.id))
    }

    @Test fun failedCompleteClearsRealPromptBeforeBlockedCleanup() = runTest {
        focus().replaceRiskApp("risk", "Test app")
        val s = startMonitored()
        wallNow = 12_000
        val segment = db.focusDao().getActiveSegment(s.id)!!
        assertTrue(focus().confirmRisk(com.guanyi.mirra.data.repository.RiskConfirmation(s.id, segment.id, "risk", 2_000, 12_000)))
        val controller = BoundSessionMonitoringController(RepositoryRuntimeFactsPort(focus()))
        controller.refresh(s.id, ClockSample(12_000, 11_000))
        assertTrue(controller.intervention.value != null)
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_complete BEFORE UPDATE OF closeoutState ON session_focus_contexts WHEN NEW.closeoutState = 'COMPLETED' BEGIN SELECT RAISE(ABORT, 'injected complete'); END")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val manager = DefaultSessionManager(workflow(), closeoutWithMonitoringFacts = { id, sample, block -> controller.closeoutWithFacts(id, sample, block) },
            cleanupClosedSession = { assertNull(controller.intervention.value); assertNull(controller.focusStatus.value.sessionId); entered.complete(Unit); release.await() })
        val job = async(Dispatchers.IO) { manager.finish(s.id, 20, ClockSample(12_000, 11_000)) }
        try { withContext(Dispatchers.IO) { withTimeout(5_000) { entered.await() } }; assertEquals(FocusCloseoutState.PENDING, workflow().getCloseoutState(s.id)) }
        finally { release.complete(Unit) }
        assertEquals(SessionFinishResult.PendingRetry(s.id), job.await())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_complete")
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Expected injected database failure", failed)
    }

    @Test fun backwardClockJumpLossIsDurableBeforeCloseout() = runTest {
        val items = DefaultLearningItemRepository(db, clock = { wallNow })
        val workflow = DefaultStudyWorkflowRepository(
            db, RuleBasedSummaryEngine(), IntentExpiryPolicy(), clock = { wallNow },
        )
        val intent = workflow.createIntent(items.create("Clock preflight", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
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
        val intent = workflow().createIntent(DefaultLearningItemRepository(db, clock = { wallNow }).create("Clock fixture", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
        return workflow().startMonitoredSession(intent.id, 10,
            MonitoringReadyLease("clock-fixture", wallNow, 0, 1, wallNow, 0, 0, false, false),
            proposedSessionId = "clock-fixture-session").session
    }

    private suspend fun startUnmonitored(): StudySessionEntity {
        val intent = workflow().createIntent(DefaultLearningItemRepository(db, clock = { wallNow }).create("Clock fixture", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
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
