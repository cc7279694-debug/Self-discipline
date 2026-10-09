package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.RiskAppEntity
import com.guanyi.mirra.data.local.entity.FocusEventType
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.repository.DefaultFocusRepository
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.DefaultSessionManager
import com.guanyi.mirra.domain.SessionFinishResult
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import com.guanyi.mirra.data.repository.RiskConfirmation
import com.guanyi.mirra.domain.monitoring.BoundSessionMonitoringController
import com.guanyi.mirra.domain.monitoring.ClockSample
import com.guanyi.mirra.domain.monitoring.RepositoryRuntimeFactsPort
import com.guanyi.mirra.domain.monitoring.RuntimeFactsPort
import com.guanyi.mirra.platform.focus.MonitoringBinding
import com.guanyi.mirra.platform.focus.MonitoringReadyLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModuleThreeBMonitoredStartRepositoryTest {
    private lateinit var db: MirraDatabase
    private lateinit var items: DefaultLearningItemRepository
    private lateinit var workflow: DefaultStudyWorkflowRepository
    private var now = 1_000_000L
    private var id = 0

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java).build()
        val ids = { "id-${++id}" }
        items = DefaultLearningItemRepository(db, clock = { now }, newId = ids)
        workflow = DefaultStudyWorkflowRepository(db, RuleBasedSummaryEngine(), IntentExpiryPolicy(), clock = { now }, newId = ids)
    }

    @After fun tearDown() = db.close()

    @Test fun fullStartAtomicallyUsesReadyTimestampAndSnapshots() = runTest {
        val item = items.create("书", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置")
        val intent = workflow.createIntent(item.id)
        now = 1_005_100
        val session = monitored(intent.id, 10).session

        assertEquals(1_005_000L, session.startedAt)
        val context = db.focusDao().getContext(session.id)!!
        assertEquals(MonitoringCoverage.FULL, context.monitoringStatus)
        assertNull(context.monitoringLostAt)
        assertEquals(1_000L, context.pollIntervalMillis)
        assertTrue(context.usageAccessAtStart)
        assertEquals(session.startedAt, context.lastHeartbeatAt)
        assertEquals(SessionSegmentType.FOCUS, db.focusDao().getActiveSegment(session.id)?.type)
        assertEquals(session.startedAt, db.focusDao().getActiveSegment(session.id)?.startedAt)
        assertEquals(IntentOutcome.CONVERTED, db.intentDao().get(intent.id)?.outcome)
        assertEquals(session.startedAt, db.intentDao().get(intent.id)?.convertedAt)
        assertEquals(1, db.sessionDao().listAll().size)
    }

    @Test fun failedConversionRollsBackAllMonitoredFacts() = runTest {
        val intent = workflow.createIntent(items.create("书", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_conversion BEFORE UPDATE OF outcome ON study_intents
            WHEN NEW.outcome = 'CONVERTED' BEGIN SELECT RAISE(ABORT, 'forced rollback'); END
        """.trimIndent())
        now = 1_005_100
        assertAnyFailure { monitored(intent.id, 10) }
        assertTrue(db.sessionDao().listAll().isEmpty())
        assertNull(db.sessionDao().getActive())
        assertNull(db.intentDao().get(intent.id)?.outcome)
        assertEquals(1, db.intentDao().get(intent.id)?.activeSlot)
    }

    @Test fun duplicateMonitoredStartReturnsSameActiveSession() = runTest {
        val intent = workflow.createIntent(items.create("书", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
        now = 1_005_100
        val first = monitored(intent.id, 10)
        val second = monitored(intent.id, 10)
        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.session.id, second.session.id)
        assertEquals(1, db.sessionDao().listAll().size)
        assertEquals(1, db.focusDao().listSegments(first.session.id).size)
    }

    @Test fun anotherActiveSessionRejectsMonitoredStartWithoutPartialFacts() = runTest {
        val firstIntent = workflow.createIntent(items.create("第一本", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
        now = 1_005_100
        val active = monitored(firstIntent.id, 10).session
        val secondItem = items.create("第二本", 100, 1, firstAction = "把书放到桌上，翻到上次阅读的位置")
        val secondIntent = StudyIntentEntity("conflict-intent", secondItem.id, now, null, null,
            null, null, 1).also { db.intentDao().insert(it) }
        assertAnyFailure { monitored(secondIntent.id, 1, lease().copy(readyAtWall = now)) }
        assertEquals(active.id, db.sessionDao().getActive()?.id)
        assertEquals(1, db.sessionDao().listAll().size)
        assertNull(db.intentDao().get(secondIntent.id)?.outcome)
    }

    @Test fun riskAppSnapshotAndBindFailureCompensationRetainCommittedSession() = runTest {
        db.focusDao().upsertRiskApp(RiskAppEntity("example.risk", "Risk", now, now))
        val intent = workflow.createIntent(items.create("书", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
        now = 1_005_100
        val session = monitored(intent.id, 10).session
        assertEquals("example.risk", db.focusDao().listRiskApps().single().packageName)
        assertEquals(1, db.openHelper.readableDatabase.query(
            "SELECT * FROM session_risk_app_snapshots WHERE sessionId = ?", arrayOf(session.id),
        ).use { it.count })

        DefaultFocusRepository(db, clock = { now }).markMonitoringLost(session.id, session.startedAt, now)
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(session.id)?.monitoringStatus)
        assertEquals(session.startedAt, db.focusDao().getContext(session.id)?.monitoringLostAt)
        assertEquals(SessionSegmentType.UNMONITORED, db.focusDao().getActiveSegment(session.id)?.type)
        assertEquals(1, db.focusDao().listSegments(session.id).size)
        assertEquals(session.id, db.sessionDao().getActive()?.id)
    }

    @Test fun timeoutItemStateAndInvalidPageRejectMonitoredStartWithoutPartialSession() = runTest {
        val item = items.create("书", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置")
        val intent = workflow.createIntent(item.id)
        now = 1_005_100
        assertAnyFailure { monitored(intent.id, 101) }
        assertTrue(db.sessionDao().listAll().isEmpty())
        // Simulate a stale/corrupt status observed only inside the start transaction.
        db.learningItemDao().markPaused(item.id, now)
        assertAnyFailure { monitored(intent.id, 10) }
        assertTrue(db.sessionDao().listAll().isEmpty())

        val other = items.create("另一本", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置")
        workflow.abandonIntent(intent.id)
        val expired = workflow.createIntent(other.id)
        now = 2_900_000
        assertAnyFailure { monitored(expired.id, 10) }
        assertEquals(IntentOutcome.TIMEOUT, db.intentDao().get(expired.id)?.outcome)
        assertTrue(db.sessionDao().listAll().isEmpty())
    }

    @Test fun finishingCommittedSessionReleasesOnlyItsMonitoringBinding() = runTest {
        val intent = workflow.createIntent(items.create("书", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
        now = 1_005_100
        val session = monitored(intent.id, 10).session
        var released: String? = null
        val manager = DefaultSessionManager(workflow, cleanupClosedSession = { assertFalse(db.inTransaction()); released = it })
        now = 1_010_000
        manager.finish(session.id, 11, ClockSample(now, 10_000))
        assertEquals(session.id, released)
        assertNull(db.sessionDao().getActive())
        assertEquals(MonitoringCoverage.FULL, db.focusDao().getContext(session.id)?.monitoringStatus)
        assertEquals(listOf(SessionSegmentType.FOCUS), db.focusDao().listSegments(session.id).map { it.type })
    }

    @Test fun dndReleaseHookRunsAfterDurableBeginBeforeSettlementAndPreservesCoverage() = runTest {
        val intent = workflow.createIntent(items.create("书", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
        now = 1_005_100
        val session = monitored(intent.id, 10).session
        val boundary = 1_010_000L
        var releaseCalls = 0
        val manager = DefaultSessionManager(workflow,
            cleanupClosedSession = { id ->
                assertEquals(session.id, id)
                assertFalse(db.inTransaction())
                val pendingSession = checkNotNull(db.sessionDao().get(id))
                val pendingContext = checkNotNull(db.focusDao().getContext(id))
                assertNull(pendingSession.endedAt)
                assertNull(pendingSession.endType)
                assertEquals(1, pendingSession.activeSlot)
                assertEquals(id, db.sessionDao().getActive()?.id)
                assertEquals(FocusCloseoutState.PENDING, pendingContext.closeoutState)
                assertEquals(boundary, pendingContext.closeoutStartedAt)
                assertEquals(11, pendingContext.requestedEndPage)
                assertEquals(MonitoringCoverage.FULL, pendingContext.monitoringStatus)
                assertNull(db.focusDao().getActiveSegment(id))
                val closedSegment = db.focusDao().listSegments(id).single()
                assertEquals(SessionSegmentType.FOCUS, closedSegment.type)
                assertEquals(boundary, closedSegment.endedAt)
                assertNull(closedSegment.activeSlot)
                releaseCalls++
            })
        now = boundary
        val ended = (manager.finish(session.id, 11, ClockSample(boundary, 10_000)) as SessionFinishResult.Completed).session
        assertEquals(1, releaseCalls)
        assertEquals(boundary, ended.endedAt)
        assertEquals(SessionEndType.NORMAL, ended.endType)
        assertNull(ended.activeSlot)
        assertEquals(ended, db.sessionDao().get(session.id))
        assertNull(db.sessionDao().getActive())
        val completedContext = checkNotNull(db.focusDao().getContext(session.id))
        assertEquals(FocusCloseoutState.COMPLETED, completedContext.closeoutState)
        assertEquals(boundary, completedContext.closeoutStartedAt)
        assertEquals(11, completedContext.requestedEndPage)
        assertEquals(MonitoringCoverage.FULL, completedContext.monitoringStatus)
        assertEquals(boundary, db.focusDao().listSegments(session.id).single().endedAt)
    }

    @Test fun boundUserStopPersistsLossBeforeNormalFinishAndDuplicateDestroyDoesNotAddFacts() = runTest {
        val intent = workflow.createIntent(items.create("书", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
        now = 1_005_100
        val session = monitored(intent.id, 10).session
        val controller = BoundSessionMonitoringController(DefaultFocusRepository(db, clock = { now }))
        val binding = MonitoringBinding(session.id, "test-generation", 5_000)
        now = 1_020_000
        controller.onServiceLost(binding, ClockSample(now, 20_000), "user stop")
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(session.id)?.monitoringStatus)
        assertEquals(SessionSegmentType.UNMONITORED, db.focusDao().getActiveSegment(session.id)?.type)
        controller.onServiceLost(binding, ClockSample(now + 1_000, 21_000), "destroy")
        assertEquals(1, db.focusDao().listSegments(session.id).size)
        val ended = (DefaultSessionManager(workflow, closeoutWithMonitoringFacts = { id, sample, block -> controller.closeoutWithFacts(id, sample, block) })
            .finish(session.id, 11, ClockSample(now, 20_000)) as SessionFinishResult.Completed).session
        val segments = db.focusDao().listSegments(session.id)
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(session.id)?.monitoringStatus)
        assertEquals(SessionSegmentType.UNMONITORED, segments.single().type)
        assertEquals(session.startedAt, segments.single().startedAt)
        assertEquals(ended.endedAt, segments.single().endedAt)
    }

    @Test fun normalFinishAndReleaseBeforeDestroyDoNotCreateUnmonitoredSegment() = runTest {
        val intent = workflow.createIntent(items.create("书", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
        now = 1_005_100
        val session = monitored(intent.id, 10).session
        val controller = BoundSessionMonitoringController(DefaultFocusRepository(db, clock = { now }))
        val binding = MonitoringBinding(session.id, "test-generation", 5_000)
        now = 1_010_000
        val manager = DefaultSessionManager(workflow,
            cleanupClosedSession = { controller.onNormalRelease(it) },
            closeoutWithMonitoringFacts = { id, sample, block -> controller.closeoutWithFacts(id, sample, block) })
        manager.finish(session.id, 11, ClockSample(now, 10_000))
        controller.onServiceLost(binding, ClockSample(now, 10_000), "destroy after release")
        assertEquals(MonitoringCoverage.FULL, db.focusDao().getContext(session.id)?.monitoringStatus)
        assertEquals(listOf(SessionSegmentType.FOCUS), db.focusDao().listSegments(session.id).map { it.type })
    }

    @Test fun revokedUsageAccessPersistsLossBeforeControlledServiceStop() = runTest {
        val intent = workflow.createIntent(items.create("书", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
        now = 1_005_100
        val session = monitored(intent.id, 10).session
        val controller = BoundSessionMonitoringController(DefaultFocusRepository(db, clock = { now }))
        val binding = MonitoringBinding(session.id, "test-generation", 5_000)
        now = 1_020_000
        controller.onServiceLost(binding, ClockSample(now, 20_000), "usage access unavailable")
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(session.id)?.monitoringStatus)
        assertEquals(SessionSegmentType.UNMONITORED, db.focusDao().getActiveSegment(session.id)?.type)
        DefaultSessionManager(workflow, closeoutWithMonitoringFacts = { id, sample, block -> controller.closeoutWithFacts(id, sample, block) })
            .finish(session.id, 11, ClockSample(now, 20_000))
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(session.id)?.monitoringStatus)
    }

    @Test fun finishCannotCommitFullWhileEarlierBoundStopIsWaitingForDurableLoss() = runBlocking {
        val intent = workflow.createIntent(items.create("书", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
        now = 1_005_100
        val session = monitored(intent.id, 10).session
        val focus = DefaultFocusRepository(db, clock = { now })
        val lossEntered = CompletableDeferred<Unit>()
        val allowLossCommit = CompletableDeferred<Unit>()
        val port = object : RuntimeFactsPort by RepositoryRuntimeFactsPort(focus) {
            override suspend fun lose(sessionId: String, trustedAt: Long, detectedAt: Long) {
                lossEntered.complete(Unit)
                allowLossCommit.await()
                focus.markMonitoringLost(sessionId, trustedAt, detectedAt)
            }
        }
        val controller = BoundSessionMonitoringController(port)
        val binding = MonitoringBinding(session.id, "test-generation", 5_000)
        now = 1_020_000
        val stop = async { controller.onServiceLost(binding, ClockSample(now, 20_000), "user stop") }
        lossEntered.await()
        val manager = DefaultSessionManager(workflow, closeoutWithMonitoringFacts = { id, sample, block -> controller.closeoutWithFacts(id, sample, block) })
        val finish = async { manager.finish(session.id, 11, ClockSample(now, 20_000)) }
        try {
            assertNull("Session finish must wait for the already requested loss",
                withTimeoutOrNull(1_500) { finish.await() })
        } finally {
            allowLossCommit.complete(Unit)
        }
        stop.await()
        val ended = (finish.await() as SessionFinishResult.Completed).session
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(session.id)?.monitoringStatus)
        assertEquals(SessionSegmentType.UNMONITORED, db.focusDao().listSegments(session.id).last().type)
        assertEquals(ended.endedAt, db.focusDao().listSegments(session.id).last().endedAt)
    }

    @Test fun riskConfirmationUsesFrozenSnapshotAndSameOpenFocusSegment() = runTest {
        db.focusDao().upsertRiskApp(RiskAppEntity("example.risk", "Risk", now, now))
        val intent = workflow.createIntent(items.create("书", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
        now = 1_005_100
        val session = monitored(intent.id, 10).session
        val focus = DefaultFocusRepository(db, clock = { now })
        val source = db.focusDao().getActiveSegment(session.id)!!
        focus.removeRiskApp("example.risk") // Active Session must still use its frozen snapshot.
        now = 1_016_000
        val command = RiskConfirmation(session.id, source.id, "example.risk", 1_005_500, now)
        assertTrue(focus.confirmRisk(command))
        assertFalse(focus.confirmRisk(command))
        val segments = db.focusDao().listSegments(session.id)
        assertEquals(listOf(SessionSegmentType.FOCUS, SessionSegmentType.DISTRACTION), segments.map { it.type })
        assertEquals(1_005_500L, segments.first().endedAt)
        assertEquals(1_005_500L, segments.last().startedAt)
        assertEquals(1, db.focusDao().listEvents(session.id).count { it.type == FocusEventType.RISK_APP_CONFIRMED })
        assertTrue(focus.exitRisk(session.id, "example.risk", 1_017_000).not()) // Cannot write future time.
        now = 1_018_000
        assertTrue(focus.exitRisk(session.id, "example.risk", 1_017_000))
        assertFalse(focus.exitRisk(session.id, "example.risk", 1_017_000))
        assertEquals(SessionSegmentType.RECOVERY, db.focusDao().getActiveSegment(session.id)?.type)
        assertEquals(segments.last().id, db.focusDao().getActiveSegment(session.id)?.relatedSegmentId)
    }

    @Test fun changedSegmentAndUnknownRiskCannotConfirmAndBriefIsIdempotent() = runTest {
        db.focusDao().upsertRiskApp(RiskAppEntity("example.risk", "Risk", now, now))
        val intent = workflow.createIntent(items.create("书", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
        now = 1_005_100
        val session = monitored(intent.id, 10).session
        val focus = DefaultFocusRepository(db, clock = { now })
        val source = db.focusDao().getActiveSegment(session.id)!!
        now = 1_010_000
        assertFalse(focus.confirmRisk(RiskConfirmation(session.id, source.id, "unknown", 1_006_000, now)))
        assertFalse(focus.confirmRisk(RiskConfirmation(session.id, source.id, "example.risk", 1_006_000, now)))
        assertTrue(focus.recordBriefRiskVisit(session.id, "example.risk", now))
        assertFalse(focus.recordBriefRiskVisit(session.id, "example.risk", now))
        assertEquals(1, db.focusDao().listEvents(session.id).size)
        focus.transition(com.guanyi.mirra.data.repository.SegmentTransitionCommand(session.id,
            SessionSegmentType.BREAK, now, plannedEndAt = now + 300_000))
        now = 1_020_000
        assertFalse(focus.confirmRisk(RiskConfirmation(session.id, source.id, "example.risk", 1_006_000, now)))
        assertEquals(SessionSegmentType.BREAK, db.focusDao().getActiveSegment(session.id)?.type)
    }

    @Test fun candidateAtSessionStartReclassifiesOpenSegmentWithoutZeroLengthRow() = runTest {
        db.focusDao().upsertRiskApp(RiskAppEntity("example.risk", "Risk", now, now))
        val intent = workflow.createIntent(items.create("书", 100, 10, firstAction = "把书放到桌上，翻到上次阅读的位置").id)
        now = 1_005_100
        val session = monitored(intent.id, 10).session
        val source = db.focusDao().getActiveSegment(session.id)!!
        now = 1_016_000
        val focus = DefaultFocusRepository(db, clock = { now })
        assertTrue(focus.confirmRisk(RiskConfirmation(session.id, source.id, "example.risk",
            session.startedAt, now)))
        val segments = db.focusDao().listSegments(session.id)
        assertEquals(1, segments.size)
        assertEquals(SessionSegmentType.DISTRACTION, segments.single().type)
        assertEquals(session.startedAt, segments.single().startedAt)
        assertNull(segments.single().endedAt)
    }

    private fun lease() = MonitoringReadyLease(
        generation = "test-generation", readyAtWall = 1_005_000, readyAtElapsed = 5_000,
        successfulQueryGeneration = 1, cursorWallMillis = 1_005_000,
        lastSuccessfulQueryElapsed = 5_000, continuityEpoch = 0,
        notificationVisible = false, dndAccessAvailable = false,
    )

    private suspend fun monitored(intentId: String, page: Int, evidence: MonitoringReadyLease = lease()) =
        workflow.startMonitoredSession(intentId, page, evidence, proposedSessionId = "proposal-${++id}")

    private suspend fun assertAnyFailure(block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected failure") }
        catch (failure: Throwable) { if (failure is AssertionError) throw failure }
    }
}
