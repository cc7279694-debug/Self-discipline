package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.RiskAppEntity
import com.guanyi.mirra.data.local.entity.FocusEventType
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.repository.DefaultFocusRepository
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.DefaultSessionManager
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import com.guanyi.mirra.data.repository.RiskConfirmation
import com.guanyi.mirra.platform.focus.MonitoringReadyLease
import kotlinx.coroutines.test.runTest
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
        val item = items.create("书", 100, 10)
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
        val intent = workflow.createIntent(items.create("书", 100, 10).id)
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
        val intent = workflow.createIntent(items.create("书", 100, 10).id)
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
        val firstIntent = workflow.createIntent(items.create("第一本", 100, 10).id)
        now = 1_005_100
        val active = monitored(firstIntent.id, 10).session
        val secondItem = items.create("第二本", 100, 1)
        val secondIntent = StudyIntentEntity("conflict-intent", secondItem.id, now, null, null,
            null, null, 1).also { db.intentDao().insert(it) }
        assertAnyFailure { monitored(secondIntent.id, 1, lease().copy(readyAtWall = now)) }
        assertEquals(active.id, db.sessionDao().getActive()?.id)
        assertEquals(1, db.sessionDao().listAll().size)
        assertNull(db.intentDao().get(secondIntent.id)?.outcome)
    }

    @Test fun riskAppSnapshotAndBindFailureCompensationRetainCommittedSession() = runTest {
        db.focusDao().upsertRiskApp(RiskAppEntity("example.risk", "Risk", now, now))
        val intent = workflow.createIntent(items.create("书", 100, 10).id)
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
        val item = items.create("书", 100, 10)
        val intent = workflow.createIntent(item.id)
        now = 1_005_100
        assertAnyFailure { monitored(intent.id, 101) }
        assertTrue(db.sessionDao().listAll().isEmpty())
        // Simulate a stale/corrupt status observed only inside the start transaction.
        db.learningItemDao().markPaused(item.id, now)
        assertAnyFailure { monitored(intent.id, 10) }
        assertTrue(db.sessionDao().listAll().isEmpty())

        val other = items.create("另一本", 100, 10)
        workflow.abandonIntent(intent.id)
        val expired = workflow.createIntent(other.id)
        now = 2_900_000
        assertAnyFailure { monitored(expired.id, 10) }
        assertEquals(IntentOutcome.TIMEOUT, db.intentDao().get(expired.id)?.outcome)
        assertTrue(db.sessionDao().listAll().isEmpty())
    }

    @Test fun finishingCommittedSessionReleasesOnlyItsMonitoringBinding() = runTest {
        val intent = workflow.createIntent(items.create("书", 100, 10).id)
        now = 1_005_100
        val session = monitored(intent.id, 10).session
        var released: String? = null
        val manager = DefaultSessionManager(workflow, onSessionFinished = { released = it })
        now = 1_010_000
        manager.finish(session.id, 11)
        assertEquals(session.id, released)
        assertNull(db.sessionDao().getActive())
    }

    @Test fun riskConfirmationUsesFrozenSnapshotAndSameOpenFocusSegment() = runTest {
        db.focusDao().upsertRiskApp(RiskAppEntity("example.risk", "Risk", now, now))
        val intent = workflow.createIntent(items.create("书", 100, 10).id)
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
        val intent = workflow.createIntent(items.create("书", 100, 10).id)
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
        val intent = workflow.createIntent(items.create("书", 100, 10).id)
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
