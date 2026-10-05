package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.DndLifecycle
import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.local.model.CloseoutSnapshot
import com.guanyi.mirra.data.repository.DefaultFocusRepository
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.data.repository.RoomDndStateStore
import com.guanyi.mirra.domain.DefaultSessionManager
import com.guanyi.mirra.domain.DndController
import com.guanyi.mirra.domain.DndSystem
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import com.guanyi.mirra.domain.SessionFinishResult
import com.guanyi.mirra.domain.SessionTimelineValidator
import com.guanyi.mirra.domain.TimelineTrust
import com.guanyi.mirra.domain.monitoring.BoundSessionMonitoringController
import com.guanyi.mirra.domain.monitoring.ClockSample
import com.guanyi.mirra.domain.monitoring.RepositoryRuntimeFactsPort
import com.guanyi.mirra.platform.focus.MonitorSnapshot
import com.guanyi.mirra.platform.focus.MonitoringBinding
import com.guanyi.mirra.platform.focus.MonitoringReadyLease
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModuleThreeDFinalBoundaryTest {
    private lateinit var database: MirraDatabase
    private lateinit var workflow: DefaultStudyWorkflowRepository
    private var wallNow = 1_000L

    @Before fun setUp() {
        wallNow = 1_000L
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java,
        ).build()
        workflow = DefaultStudyWorkflowRepository(
            database, RuleBasedSummaryEngine(), IntentExpiryPolicy(), clock = { wallNow },
        )
    }

    @After fun tearDown() = database.close()

    @Test fun pendingAndCompletedDndReleaseFailureRetryPreserveFrozenLearningFacts() = runTest {
        for (state in listOf(FocusCloseoutState.PENDING, FocusCloseoutState.COMPLETED)) {
            wallNow = 1_000L
            val session = startMonitored()
            val store = RoomDndStateStore(database)
            val system = ReleaseFailureDndSystem()
            val dnd = DndController(store, system)
            dnd.apply(session.id, enabled = true)
            assertEquals(DndLifecycle.ACTIVE, store.get(session.id)?.lifecycle)
            workflow.updateCurrentPage(session.id, 20)

            wallNow = 2_000L
            val frozen = workflow.beginCloseout(session.id, 20, wallNow)
            if (state == FocusCloseoutState.COMPLETED) workflow.completeCloseout(session.id)
            val before = learningFacts(session.id)
            assertEquals(CloseoutSnapshot(session.id, 2_000L, 20), frozen)
            assertEquals(state, before.closeoutState)
            assertEquals(MonitoringCoverage.FULL, before.coverage)
            assertNull(before.monitoringLostAt)
            assertEquals(20, before.session.currentPage)
            assertEquals(if (state == FocusCloseoutState.PENDING) 10 else 20, before.learningItemPage)
            assertEquals(2_000L, before.segments.single().endedAt)
            if (state == FocusCloseoutState.PENDING) assertNull(before.session.endedAt)
            else assertEquals(2_000L, before.session.endedAt)

            // Only the Android boundary is fake; lifecycle writes use the real Room store.
            system.failRelease = true
            dnd.release(session.id)
            assertEquals(DndLifecycle.RELEASE_FAILED, store.get(session.id)?.lifecycle)
            assertEquals(before, learningFacts(session.id))

            system.failRelease = false
            dnd.release(session.id)
            assertEquals(DndLifecycle.RELEASED, store.get(session.id)?.lifecycle)
            assertEquals(before, learningFacts(session.id))

            wallNow = 99_000L
            val ended = workflow.completeCloseout(session.id)
            assertEquals(SessionEndType.NORMAL, ended.endType)
            assertEquals(2_000L, ended.endedAt)
            assertEquals(20, ended.endPage)
            assertEquals(frozen, workflow.getCloseoutSnapshot(session.id))
            assertEquals(before.segments, database.focusDao().listSegments(session.id))
            assertEquals(MonitoringCoverage.FULL, database.focusDao().getContext(session.id)?.monitoringStatus)
            assertEquals(20, database.learningItemDao().get(session.learningItemId)?.currentPage)
            assertNull(database.sessionDao().getActive())
            assertNull(database.focusDao().getActiveSegment(session.id))
        }
    }

    @Test fun lossAtFinalConfirmationNeverProducesTrustedFocus() = runTest {
        val session = startMonitored()
        val controller = BoundSessionMonitoringController(
            RepositoryRuntimeFactsPort(DefaultFocusRepository(database, clock = { wallNow })),
        )
        val trusted = ClockSample(2_000L, 1_000L)
        wallNow = trusted.wallNowMillis
        controller.onSample(
            MonitoringBinding(session.id, "final-boundary", 0),
            MonitorSnapshot(
                running = true, queryGeneration = 2,
                lastSuccessfulQueryElapsed = trusted.elapsedNowMillis,
                lastSuccessfulQueryClock = trusted,
            ),
            trusted,
        )
        val manager = DefaultSessionManager(workflow, closeoutWithMonitoringFacts = { id, sample, block ->
            controller.closeoutWithFacts(id, sample, block)
        })

        // Final confirmation reaches the existing six-second watchdog before stage A.
        wallNow = 8_000L
        val result = manager.finish(session.id, 20, ClockSample(8_000L, 7_000L)) as SessionFinishResult.Completed
        val ended = checkNotNull(database.sessionDao().get(session.id))
        val context = checkNotNull(database.focusDao().getContext(session.id))
        val segments = database.focusDao().listSegments(session.id)
        assertEquals(result.session, ended)
        assertEquals(SessionEndType.NORMAL, ended.endType)
        assertEquals(8_000L, ended.endedAt)
        assertEquals(FocusCloseoutState.COMPLETED, context.closeoutState)
        assertEquals(MonitoringCoverage.PARTIAL, context.monitoringStatus)
        assertEquals(2_000L, context.monitoringLostAt)
        assertEquals(listOf(SessionSegmentType.FOCUS, SessionSegmentType.UNMONITORED), segments.map { it.type })
        assertEquals(2_000L, segments.first().endedAt)
        assertEquals(2_000L, segments.last().startedAt)
        assertEquals(8_000L, segments.last().endedAt)
        assertNull(database.sessionDao().getActive())
        assertNull(database.focusDao().getActiveSegment(session.id))

        // Analyze these persisted facts, rather than constructing a separate validator fixture.
        val analysis = SessionTimelineValidator().analyze(ended, context, segments)
        assertEquals(TimelineTrust.MONITORING_INCOMPLETE, analysis.trust)
        assertNull(analysis.effectiveFocusMillis)
    }

    private suspend fun startMonitored(): StudySessionEntity {
        val item = DefaultLearningItemRepository(database, clock = { wallNow })
            .create("Final boundary controlled fixture", 100, 10)
        val intent = workflow.createIntent(item.id)
        // Controlled READY evidence for this isolated Room test, not a running Android monitor.
        val lease = MonitoringReadyLease("final-boundary", wallNow, 0, 1, wallNow, 0, 0, false, true)
        return workflow.startMonitoredSession(intent.id, 10, lease, "final-boundary-${item.id}").session
    }

    private suspend fun learningFacts(sessionId: String): FrozenLearningFacts {
        val session = checkNotNull(database.sessionDao().get(sessionId))
        val context = checkNotNull(database.focusDao().getContext(sessionId))
        return FrozenLearningFacts(
            session, checkNotNull(workflow.getCloseoutSnapshot(sessionId)), context.closeoutState,
            context.monitoringStatus, context.monitoringLostAt, context.lastHeartbeatAt,
            database.focusDao().listSegments(sessionId),
            checkNotNull(database.learningItemDao().get(session.learningItemId)).currentPage,
        )
    }

    private data class FrozenLearningFacts(
        val session: StudySessionEntity,
        val snapshot: CloseoutSnapshot,
        val closeoutState: FocusCloseoutState,
        val coverage: MonitoringCoverage,
        val monitoringLostAt: Long?,
        val lastHeartbeatAt: Long,
        val segments: List<SessionSegmentEntity>,
        val learningItemPage: Int,
    )

    private class ReleaseFailureDndSystem : DndSystem {
        override val apiLevel = 37
        var failRelease = false
        override fun hasAccess() = true
        override fun findOwnedRule() = "test-owned-rule"
        override fun createOwnedRule(): String = error("Existing owned rule expected")
        override fun activateOwnedRule(id: String) { check(id == "test-owned-rule") }
        override fun deactivateOwnedRule(id: String) {
            check(id == "test-owned-rule")
            if (failRelease) error("Controlled test-only DND release failure")
        }
        override fun currentFilter(): Int = error("Modern rule fixture only")
        override fun applyLegacyPriority(): Unit = error("Modern rule fixture only")
        override fun legacyOwnershipIntact() = false
        override fun restoreLegacyFilter(priorFilter: Int): Unit = error("Modern rule fixture only")
    }
}
