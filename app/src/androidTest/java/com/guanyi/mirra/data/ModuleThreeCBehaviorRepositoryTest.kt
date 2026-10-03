package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.repository.*
import com.guanyi.mirra.domain.*
import com.guanyi.mirra.platform.focus.MonitoringReadyLease
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModuleThreeCBehaviorRepositoryTest {
    private lateinit var db: MirraDatabase
    private lateinit var workflow: DefaultStudyWorkflowRepository
    private lateinit var focus: DefaultFocusRepository
    private var now = 1_000L
    private var id = 0
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java).build()
        workflow = DefaultStudyWorkflowRepository(db, RuleBasedSummaryEngine(), IntentExpiryPolicy(), clock = { now })
        focus = DefaultFocusRepository(db, clock = { now }, newId = { "segment-${++id}" })
    }
    @After fun close() = db.close()
    private suspend fun start(monitored: Boolean = true): String {
        val item = DefaultLearningItemRepository(db, clock = { now }).create("测试书", 100, 10)
        focus.replaceRiskApp("risk.a", "A"); focus.replaceRiskApp("risk.b", "B")
        val intent = workflow.createIntent(item.id)
        return if (!monitored) workflow.startSession(intent.id, 10).id else {
            workflow.startMonitoredSession(intent.id, 10, MonitoringReadyLease("g", now, 0, 1, now, 0, 0,
                false, false), "s").session.id
        }
    }
    @Test fun recoveryAndAllowanceCanBeRiskSourcesAndOldConfirmationIsIdempotent() = runTest {
        val s = start(); val source = db.focusDao().getActiveSegment(s)!!
        now = 12_000
        assertTrue(focus.confirmRisk(RiskConfirmation(s, source.id, "risk.a", 2_000, now)))
        assertFalse(focus.confirmRisk(RiskConfirmation(s, source.id, "risk.a", 2_000, now)))
        now = 13_000; focus.exitRisk(s, "risk.a", now)
        val recovery = db.focusDao().getActiveSegment(s)!!
        now = 24_000
        assertTrue(focus.confirmRisk(RiskConfirmation(s, recovery.id, "risk.b", 14_000, now)))
        assertEquals(1, db.focusDao().listEvents(s).count { it.type == FocusEventType.RECOVERY_INTERRUPTED })
    }
    @Test fun grantDoesNotEraseEarlierDistractionAndConcurrentExtensionSucceedsOnce() = runTest {
        val s = start(); val source = db.focusDao().getActiveSegment(s)!!; now = 12_000
        focus.confirmRisk(RiskConfirmation(s, source.id, "risk.a", 2_000, now))
        val risk = db.focusDao().getActiveSegment(s)!!; now = 13_000
        val a = focus.applyBehavior(BehaviorCommand(s, risk.id, BehaviorAction.GRANT_ALLOWANCE, now,
            "grant", "risk.a", "REPLY", 180_000, db.focusDao().latestRiskConfirmation(s)!!.id))!!
        assertEquals(13_000L, a.startedAt)
        assertEquals(13_000L, db.focusDao().listSegments(s).first { it.type == SessionSegmentType.DISTRACTION }.endedAt)
        now = 14_000
        val results = listOf(async { focus.applyBehavior(BehaviorCommand(s, a.id, BehaviorAction.EXTEND_ALLOWANCE, now, "e1")) },
            async { focus.applyBehavior(BehaviorCommand(s, a.id, BehaviorAction.EXTEND_ALLOWANCE, now, "e2")) }).map { it.await() }
        assertEquals(1, results.count { it != null })
        assertEquals(313_000L, db.focusDao().getActiveSegment(s)!!.plannedEndAt)
        assertEquals(1, db.focusDao().getActiveSegment(s)!!.extensionCount)
    }
    @Test fun noneBreakEndsUnmonitoredAndNeverUpgradesCoverage() = runTest {
        val s = start(false); val source = db.focusDao().getActiveSegment(s)!!; now = 2_000
        val b = focus.applyBehavior(BehaviorCommand(s, source.id, BehaviorAction.START_BREAK, now, "b", durationMillis = 300_000))!!
        now = 3_000
        val end = focus.applyBehavior(BehaviorCommand(s, b.id, BehaviorAction.FINISH_BREAK, now, "end"))!!
        assertEquals(SessionSegmentType.UNMONITORED, end.type)
        assertEquals(MonitoringCoverage.NONE, db.focusDao().getContext(s)!!.monitoringStatus)
    }
    @Test fun healthyBreakExpiresAtDeadlineInRecoveryAndCannotExtendExpiredAllowance() = runTest {
        val s = start(); val source = db.focusDao().getActiveSegment(s)!!; now = 2_000
        val b = focus.applyBehavior(BehaviorCommand(s, source.id, BehaviorAction.START_BREAK, now, "b", durationMillis = 300_000))!!
        now = 310_000
        val r = focus.applyBehavior(BehaviorCommand(s, b.id, BehaviorAction.EXPIRE, 302_000, "deadline"))!!
        assertEquals(SessionSegmentType.RECOVERY, r.type); assertEquals(302_000L, r.startedAt)
        assertNull(focus.applyBehavior(BehaviorCommand(s, b.id, BehaviorAction.EXPIRE, 302_000, "deadline")))
    }
    @Test fun endedSessionRejectsOldCommandAndInsertFailureRollsBackSegmentClose() = runTest {
        val s = start(); val source = db.focusDao().getActiveSegment(s)!!; now = 2_000
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_break BEFORE INSERT ON session_segments WHEN NEW.type='BREAK' BEGIN SELECT RAISE(ABORT,'rollback'); END")
        try { focus.applyBehavior(BehaviorCommand(s, source.id, BehaviorAction.START_BREAK, now, "b", durationMillis = 300_000)); fail() }
        catch (e: android.database.sqlite.SQLiteException) { }
        assertEquals(source, db.focusDao().getActiveSegment(s))
        workflow.finishSession(s, 10)
        assertNull(focus.applyBehavior(BehaviorCommand(s, source.id, BehaviorAction.START_BREAK, now, "old", durationMillis = 300_000)))
    }

    @Test fun recoveryGrantRequiresSameEpisodeAndValidDurationAndTargetBClosesAllowance() = runTest {
        val s = start(); val source = db.focusDao().getActiveSegment(s)!!; now = 12_000
        focus.confirmRisk(RiskConfirmation(s, source.id, "risk.a", 2_000, now))
        val event = db.focusDao().latestRiskConfirmation(s)!!
        now = 13_000; focus.exitRisk(s, "risk.a", now)
        val recovery = db.focusDao().getActiveSegment(s)!!; now = 14_000
        val command = BehaviorCommand(s, recovery.id, BehaviorAction.GRANT_ALLOWANCE, now,
            "grant", "risk.a", "REPLY", 180_000, event.id)
        assertNull(focus.applyBehavior(command.copy(expectedRiskEventId = "stale")))
        assertNull(focus.applyBehavior(command.copy(durationMillis = 59_999)))
        assertNull(focus.applyBehavior(command.copy(durationMillis = 900_001)))
        assertNull(focus.applyBehavior(command.copy(packageName = "risk.b")))
        val allowance = focus.applyBehavior(command)!!
        now = 25_000
        assertFalse(focus.confirmRisk(RiskConfirmation(s, allowance.id, "risk.a", 15_000, now)))
        assertTrue(focus.confirmRisk(RiskConfirmation(s, allowance.id, "risk.b", 15_000, now)))
        assertEquals("risk.b", db.focusDao().getActiveSegment(s)!!.packageName)
        assertEquals(15_000L, db.focusDao().listSegments(s).single { it.id == allowance.id }.endedAt)
        assertNull(focus.applyBehavior(BehaviorCommand(s, allowance.id, BehaviorAction.EXTEND_ALLOWANCE, now, "old")))
        assertEquals(mapOf("risk.a" to 1, "risk.b" to 1), focus.runtimeFacts(s)!!.riskConfirmationCounts)
    }

    @Test fun expiryWinsOverConcurrentLateExtensionWithoutOverlapOrCoverageUpgrade() = runTest {
        val s = start(); val source = db.focusDao().getActiveSegment(s)!!; now = 12_000
        focus.confirmRisk(RiskConfirmation(s, source.id, "risk.a", 2_000, now))
        val risk = db.focusDao().getActiveSegment(s)!!; now = 13_000
        val allowance = focus.applyBehavior(BehaviorCommand(s, risk.id, BehaviorAction.GRANT_ALLOWANCE, now,
            "grant", "risk.a", "REPLY", 180_000, db.focusDao().latestRiskConfirmation(s)!!.id))!!
        now = 193_000
        val extension = async { focus.applyBehavior(BehaviorCommand(s, allowance.id,
            BehaviorAction.EXTEND_ALLOWANCE, now, "late")) }
        val expiry = async { focus.applyBehavior(BehaviorCommand(s, allowance.id,
            BehaviorAction.EXPIRE, now, "expiry")) }
        assertNull(extension.await()); assertEquals(SessionSegmentType.RECOVERY, expiry.await()!!.type)
        val segments = db.focusDao().listSegments(s)
        assertTrue(segments.zipWithNext().all { (a, b) -> a.endedAt == b.startedAt })
        assertTrue(segments.all { it.endedAt == null || it.endedAt > it.startedAt })
        assertEquals(MonitoringCoverage.FULL, db.focusDao().getContext(s)!!.monitoringStatus)
    }

    @Test fun lostMonitoringRejectsAllowanceAndBreakStillEndsUnmonitored() = runTest {
        val s = start(); val source = db.focusDao().getActiveSegment(s)!!; now = 12_000
        focus.confirmRisk(RiskConfirmation(s, source.id, "risk.a", 2_000, now))
        val risk = db.focusDao().getActiveSegment(s)!!; now = 13_000
        focus.markMonitoringLost(s, 12_000, now)
        assertNull(focus.applyBehavior(BehaviorCommand(s, risk.id, BehaviorAction.GRANT_ALLOWANCE, now,
            "old", "risk.a", "REPLY", 180_000, db.focusDao().latestRiskConfirmation(s)!!.id)))
        val gap = db.focusDao().getActiveSegment(s)!!
        val b = focus.applyBehavior(BehaviorCommand(s, gap.id, BehaviorAction.START_BREAK, now,
            "break", durationMillis = 600_000))!!
        now = 14_000
        val ended = focus.applyBehavior(BehaviorCommand(s, b.id, BehaviorAction.FINISH_BREAK, now, "end"))!!
        assertEquals(SessionSegmentType.UNMONITORED, ended.type)
        assertEquals(MonitoringCoverage.PARTIAL, db.focusDao().getContext(s)!!.monitoringStatus)
    }

    @Test fun sameTimeAndFutureActionsAreRejectedWithoutInventingMilliseconds() = runTest {
        val s = start(); val source = db.focusDao().getActiveSegment(s)!!
        val command = BehaviorCommand(s, source.id, BehaviorAction.START_BREAK, now,
            "break", durationMillis = 300_000)
        assertNull(focus.applyBehavior(command))
        assertNull(focus.applyBehavior(command.copy(at = now + 1)))
        assertEquals(source, db.focusDao().getActiveSegment(s))
    }

    @Test fun sameBoundaryRiskBRemovesAllowanceDeadlineWithoutCreatingZeroDurationHistory() = runTest {
        val s = start(); val source = db.focusDao().getActiveSegment(s)!!; now = 12_000
        focus.confirmRisk(RiskConfirmation(s, source.id, "risk.a", 2_000, now))
        val risk = db.focusDao().getActiveSegment(s)!!; now = 13_000
        val allowance = focus.applyBehavior(BehaviorCommand(s, risk.id, BehaviorAction.GRANT_ALLOWANCE, now,
            "grant", "risk.a", "REPLY", 180_000, db.focusDao().latestRiskConfirmation(s)!!.id))!!
        now = 23_000
        assertTrue(focus.confirmRisk(RiskConfirmation(s, allowance.id, "risk.b", allowance.startedAt, now)))
        val active = db.focusDao().getActiveSegment(s)!!
        assertEquals(SessionSegmentType.DISTRACTION, active.type)
        assertNull(active.plannedEndAt); assertNull(active.reason)
        assertEquals(0, active.extensionCount)
        assertTrue(db.focusDao().listSegments(s).all { it.endedAt == null || it.endedAt > it.startedAt })
    }

    @Test fun deepPromotionValidatesEvidenceBoundaryAndStableMilestoneDoesNotOverwrite() = runTest {
        val s = start(); val source = db.focusDao().getActiveSegment(s)!!
        val tracker = com.guanyi.mirra.domain.monitoring.StableEvidenceTracker()
        tracker.accept(source.id, source.type, MonitoringCoverage.FULL, 0, true, true, true)
        now = 121_000
        val stable = tracker.accept(source.id, source.type, MonitoringCoverage.FULL,
            120_000, true, true, true).stable!!
        focus.markStableStarted(s, now, stable)
        try { focus.markStableStarted(s, now, stable); fail("Cannot overwrite milestone") }
        catch (_: IllegalStateException) { }
        assertEquals(now, db.sessionDao().get(s)!!.stableStartedAt)
        now = 901_000
        val deep = tracker.accept(source.id, source.type, MonitoringCoverage.FULL,
            900_000, true, true, true).deep!!
        try { focus.promoteDeepFocus(s, now + 1, deep); fail("Cannot write future boundary") }
        catch (_: IllegalArgumentException) { }
        assertEquals(source, db.focusDao().getActiveSegment(s))
        val promoted = focus.promoteDeepFocus(s, now, deep)
        assertEquals(SessionSegmentType.DEEP_FOCUS, promoted.type)
        try { focus.promoteDeepFocus(s, now, deep); fail("Old segment proof must be rejected") }
        catch (_: IllegalStateException) { }
        assertEquals(promoted, db.focusDao().getActiveSegment(s))
    }
}
