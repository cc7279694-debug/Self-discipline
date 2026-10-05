package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.repository.*
import com.guanyi.mirra.domain.*
import com.guanyi.mirra.domain.intervention.InterventionDeliveryReceipt
import com.guanyi.mirra.domain.monitoring.*
import com.guanyi.mirra.platform.focus.MonitoringReadyLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.guanyi.mirra.di.configureActiveSessionPresentationAndDnd

@RunWith(AndroidJUnit4::class)
class ModuleThreeDPendingGuardsTest {
    private lateinit var db: MirraDatabase
    private var now = 1_000L
    private lateinit var workflow: DefaultStudyWorkflowRepository
    private lateinit var focus: DefaultFocusRepository
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java).build()
        workflow = DefaultStudyWorkflowRepository(db, RuleBasedSummaryEngine(), IntentExpiryPolicy(), clock = { now })
        focus = DefaultFocusRepository(db, clock = { now })
    }
    @After fun tearDown() = db.close()
    private suspend fun start(): StudySessionEntity {
        focus.replaceRiskApp("risk", "Test risk")
        val item = DefaultLearningItemRepository(db, clock = { now }).create("Closeout guards", 100, 10)
        val intent = workflow.createIntent(item.id)
        return workflow.startMonitoredSession(intent.id, 10, MonitoringReadyLease("guard", now, 0, 1, now, 0, 0, false, false), "guard-session").session
    }
    private suspend fun snapshot(id: String): List<Any?> = listOf(db.sessionDao().get(id), db.focusDao().getContext(id),
        db.focusDao().listSegments(id), db.focusDao().listEvents(id))
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected logically closed rejection") } catch (_: IllegalStateException) { }
    }
    @Test fun pendingRejectsEveryFocusWriteEntryWithOriginalReturnSemantics() = runTest {
        val s = start()
        val active = db.focusDao().getActiveSegment(s.id)!!
        now = 2_000
        workflow.beginCloseout(s.id, 20, now)
        for (complete in listOf(false, true)) {
            if (complete) workflow.completeCloseout(s.id)
            val before = snapshot(s.id)
            rejected { workflow.updateCurrentPage(s.id, 30) }
            assertNull(focus.runtimeFacts(s.id))
            for (action in BehaviorAction.entries) assertNull(focus.applyBehavior(BehaviorCommand(s.id, active.id, action, now, "token")))
            assertFalse(focus.confirmRisk(RiskConfirmation(s.id, active.id, "risk", 1_000, 11_000)))
            assertFalse(focus.recordBriefRiskVisit(s.id, "risk", now))
            assertFalse(focus.exitRisk(s.id, "risk", now))
            rejected { focus.transition(SegmentTransitionCommand(s.id, SessionSegmentType.BREAK, now, plannedEndAt = now + 300_000)) }
            rejected { focus.recordEvent(FocusEventInput(s.id, FocusEventType.RISK_APP_BRIEF_VISIT, now, "risk")) }
            rejected { focus.updateHeartbeat(s.id, now) }
            rejected { focus.markMonitoringLost(s.id, 1_000, now) }
            rejected { focus.markStableStarted(s.id, now) }
            rejected { focus.completeRecovery(s.id, now) }
            rejected { focus.promoteDeepFocus(s.id, now, StableEvidence(active.id, EvidenceMilestone.DEEP, 900_000, 600_000)) }
            assertEquals(before, snapshot(s.id))
        }
    }
    @Test fun pageCommitBeforeBeginRejectsStaleEndPage() = runTest {
        val s = start()
        workflow.updateCurrentPage(s.id, 42)
        try { workflow.beginCloseout(s.id, 40, 2_000); fail("stale page accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(FocusCloseoutState.ACTIVE, workflow.getCloseoutState(s.id))
    }
    @Test fun beginCommitBeforePageUpdateRejectsLateWrite() = runTest {
        val s = start()
        workflow.beginCloseout(s.id, 20, 2_000)
        rejected { workflow.updateCurrentPage(s.id, 42) }
        assertEquals(10, db.sessionDao().get(s.id)?.currentPage)
        assertEquals(20, workflow.getCloseoutSnapshot(s.id)?.requestedEndPage)
    }
    @Test fun pendingInvalidatesLateReceipts() = runTest {
        val s = start()
        now = 11_000
        val active = db.focusDao().getActiveSegment(s.id)!!
        assertTrue(focus.confirmRisk(RiskConfirmation(s.id, active.id, "risk", 1_000, now)))
        val risk = db.focusDao().latestRiskConfirmation(s.id)!!
        val segment = db.focusDao().getActiveSegment(s.id)!!
        val prompt = InterventionUiModel(s.id, risk.id, risk.id, segment.id, "risk", 0, 0)
        val receipts = InterventionReceiptRepository(db, { true }, { now })
        assertTrue(receipts.isEligible(prompt))
        workflow.beginCloseout(s.id, 20, now)
        val before = snapshot(s.id)
        assertFalse(receipts.isEligible(prompt))
        for (receipt in InterventionDeliveryReceipt.entries) assertFalse(receipts.record(prompt, receipt))
        assertEquals(before, snapshot(s.id))
    }
    @Test fun pendingOwnedRuleAppearsInRecoveryQuery() = runTest {
        val s = start()
        val store = RoomDndStateStore(db)
        store.prepare(s.id, null, "owned-rule")
        store.setLifecycle(s.id, DndLifecycle.ACTIVE)
        workflow.beginCloseout(s.id, 20, 2_000)
        assertEquals(listOf(s.id), store.pendingAfterRecovery().map { it.sessionId })
        store.setLifecycle(s.id, DndLifecycle.RELEASE_PENDING)
        store.setLifecycle(s.id, DndLifecycle.RELEASE_FAILED)
        store.setLifecycle(s.id, DndLifecycle.RELEASED)
        assertTrue(store.pendingAfterRecovery().isEmpty())
    }
    @Test fun pendingSessionCannotPrepareOrRetryApplyDnd() = runTest {
        val s = start()
        val store = RoomDndStateStore(db)
        workflow.beginCloseout(s.id, 20, 2_000)
        assertFalse(store.get(s.id)!!.active)
        rejected { store.prepare(s.id, null, "owned-rule") }
        rejected { store.setLifecycle(s.id, DndLifecycle.ACTIVE) }
        val system = BarrierDndSystem()
        DndController(store, system).apply(s.id, true)
        assertFalse(system.active)
    }
    @Test fun lateApplyAfterActivationBeforeLifecycleWriteIsCompensated() = runTest {
        for (stage in listOf("beforePrepare", "afterPrepare", "afterActivation")) {
            // One session per isolated database avoids unique slot and identity reuse.
            val s = start()
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val system = BarrierDndSystem {
                if (it == stage) { entered.countDown(); check(resume.await(10, TimeUnit.SECONDS)) }
            }
            val controller = DndController(RoomDndStateStore(db), system)
            val job = async(Dispatchers.IO) { controller.apply(s.id, true) }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
                workflow.beginCloseout(s.id, 20, 2_000)
            } finally { resume.countDown() }
            job.await()
            assertFalse(system.active)
            assertNotEquals(DndLifecycle.ACTIVE, db.focusDao().getContext(s.id)?.dndLifecycle)
            assertEquals(2_000L, workflow.getCloseoutSnapshot(s.id)?.closeoutStartedAt)
            assertEquals(MonitoringCoverage.FULL, db.focusDao().getContext(s.id)?.monitoringStatus)
            workflow.completeCloseout(s.id)
            db.close()
            setUp()
        }
    }
    @Test fun latePostStartHookCannotRecaptureClosedSessionPresentationOrApplyDnd() = runTest {
        val s = start()
        val configured = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var captured = false
        var applies = 0
        suspend fun hook(blockConfigure: Boolean) = configureActiveSessionPresentationAndDnd(s.id,
            { focus.runtimeFacts(it) != null },
            { captured = true; if (blockConfigure) { configured.complete(Unit); resume.await() } },
            { applies++ }, { captured = false })
        val delayed = async { hook(true) }
        configured.await()
        workflow.beginCloseout(s.id, 20, 2_000)
        resume.complete(Unit)
        delayed.await()
        assertFalse(captured)
        assertEquals(0, applies)
        hook(false)
        workflow.completeCloseout(s.id)
        hook(false)
        assertFalse(captured)
        assertEquals(0, applies)
    }
    @Test fun lateSuccessfulApplyIsReleasedByFacadeFreshRead() = runTest {
        val s = start()
        val store = RoomDndStateStore(db)
        val system = BarrierDndSystem()
        val dnd = DndController(store, system)
        val applied = CompletableDeferred<Unit>(); val returnApply = CompletableDeferred<Unit>()
        val hook = async {
            configureActiveSessionPresentationAndDnd(s.id, { focus.runtimeFacts(it) != null }, {},
                { dnd.apply(it, true); applied.complete(Unit); returnApply.await() }, { dnd.release(it) })
        }
        applied.await()
        assertTrue(system.active)
        assertEquals(DndLifecycle.ACTIVE, store.get(s.id)?.lifecycle)
        workflow.beginCloseout(s.id, 20, 2_000)
        returnApply.complete(Unit)
        hook.await()
        assertFalse(system.active)
        assertEquals(DndLifecycle.RELEASED, store.get(s.id)?.lifecycle)
        assertEquals(MonitoringCoverage.FULL, db.focusDao().getContext(s.id)?.monitoringStatus)
        assertEquals(2_000L, workflow.getCloseoutSnapshot(s.id)?.closeoutStartedAt)
    }
    @Test fun pageWriteQueuedBehindCloseoutTransactionRechecksDurableEligibility() = runTest {
        val s = start()
        val entered = CompletableDeferred<Unit>(); val commit = CompletableDeferred<Unit>()
        val closing = async(Dispatchers.IO) { db.withTransaction {
            workflow.beginCloseout(s.id, 20, 2_000)
            entered.complete(Unit); commit.await()
        } }
        entered.await()
        val page = async(Dispatchers.IO) { runCatching { workflow.updateCurrentPage(s.id, 42) } }
        commit.complete(Unit); closing.await()
        assertTrue(page.await().exceptionOrNull() is IllegalStateException)
        assertEquals(10, db.sessionDao().get(s.id)?.currentPage)
        assertEquals(20, workflow.getCloseoutSnapshot(s.id)?.requestedEndPage)
    }
    private class BarrierDndSystem(private val barrier: (String) -> Unit = {}) : DndSystem {
        override val apiLevel = 37
        var active = false
        override fun hasAccess() = true
        override fun findOwnedRule(): String { barrier("beforePrepare"); return "owned-rule" }
        override fun createOwnedRule() = error("Existing rule expected")
        override fun activateOwnedRule(id: String) { barrier("afterPrepare"); active = true; barrier("afterActivation") }
        override fun deactivateOwnedRule(id: String) { active = false }
        override fun currentFilter() = 1
        override fun applyLegacyPriority() = error("modern only")
        override fun legacyOwnershipIntact() = false
        override fun restoreLegacyFilter(priorFilter: Int) = error("modern only")
    }
}
