package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.repository.BehaviorAction
import com.guanyi.mirra.data.repository.BehaviorCommand
import com.guanyi.mirra.data.repository.DefaultFocusRepository
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import com.guanyi.mirra.platform.focus.MonitoringReadyLease
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses isolated Room to prove that a revoked external admission never writes learning facts. */
@RunWith(AndroidJUnit4::class)
class WearableBreakAdmissionRepositoryTest {
    private lateinit var database: MirraDatabase
    private lateinit var workflow: DefaultStudyWorkflowRepository
    private lateinit var focus: DefaultFocusRepository
    private var now = 1_000L

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java).build()
        workflow = DefaultStudyWorkflowRepository(database, RuleBasedSummaryEngine(), IntentExpiryPolicy(), clock = { now })
        focus = DefaultFocusRepository(database, clock = { now })
    }
    @After fun close() = database.close()

    @Test fun revokedStartBreakWritesNoSegmentContextOrEvent() = runTest {
        val session = start()
        val segment = checkNotNull(database.focusDao().getActiveSegment(session))
        now = 2_000
        assertRejectedWithoutWrites(BehaviorCommand(session, segment.id, BehaviorAction.START_BREAK,
            now, "start", durationMillis = 300_000, stillAuthorized = { false }))
    }

    @Test fun startBreakRechecksAdmissionAfterTransactionFactReads() = runTest {
        val session = start()
        val segment = checkNotNull(database.focusDao().getActiveSegment(session))
        now = 2_000
        var checks = 0
        assertRejectedWithoutWrites(BehaviorCommand(session, segment.id, BehaviorAction.START_BREAK,
            now, "start", durationMillis = 300_000, stillAuthorized = { ++checks == 1 }))
        assertEquals(2, checks)
    }

    @Test fun revokedFinishBreakDoesNotCreateRecoveryFact() = runTest {
        val session = start()
        val source = checkNotNull(database.focusDao().getActiveSegment(session))
        now = 2_000
        val rest = checkNotNull(focus.applyBehavior(BehaviorCommand(session, source.id,
            BehaviorAction.START_BREAK, now, "start", durationMillis = 300_000)))
        now = 3_000
        assertRejectedWithoutWrites(BehaviorCommand(session, rest.id, BehaviorAction.FINISH_BREAK,
            now, "finish", stillAuthorized = { false }))
    }

    @Test fun finishBreakRechecksAdmissionAfterTransactionFactReads() = runTest {
        val session = start()
        val source = checkNotNull(database.focusDao().getActiveSegment(session))
        now = 2_000
        val rest = checkNotNull(focus.applyBehavior(BehaviorCommand(session, source.id,
            BehaviorAction.START_BREAK, now, "start", durationMillis = 300_000)))
        now = 3_000
        var checks = 0
        assertRejectedWithoutWrites(BehaviorCommand(session, rest.id, BehaviorAction.FINISH_BREAK,
            now, "finish", stillAuthorized = { ++checks == 1 }))
        assertEquals(2, checks)
    }

    @Test fun authorizedBreakUsesOriginalRecoveryTransitionAndPreservesCoverage() = runTest {
        val session = start()
        val source = checkNotNull(database.focusDao().getActiveSegment(session))
        now = 2_000
        val rest = checkNotNull(focus.applyBehavior(BehaviorCommand(session, source.id,
            BehaviorAction.START_BREAK, now, "start", durationMillis = 600_000, stillAuthorized = { true })))
        assertEquals(SessionSegmentType.BREAK, rest.type)
        assertEquals(602_000L, rest.plannedEndAt)
        now = 3_000
        val recovered = checkNotNull(focus.applyBehavior(BehaviorCommand(session, rest.id,
            BehaviorAction.FINISH_BREAK, now, "finish", stillAuthorized = { true })))
        assertEquals(SessionSegmentType.RECOVERY, recovered.type)
        assertEquals(MonitoringCoverage.FULL, database.focusDao().getContext(session)?.monitoringStatus)
        assertTrue(database.focusDao().listEvents(session).isEmpty())
        assertNull(database.sessionDao().get(session)?.stableStartedAt)
    }

    private suspend fun start(): String {
        val item = DefaultLearningItemRepository(database, clock = { now }).create("授权事务测试", 100, 9, "翻到第 9 页，读第一段。")
        val intent = workflow.createIntent(item.id)
        return workflow.startMonitoredSession(intent.id, 9,
            MonitoringReadyLease("ready", now, 0, 1, now, 0, 0, false, false), "session").session.id
    }

    private suspend fun assertRejectedWithoutWrites(command: BehaviorCommand) {
        val session = database.sessionDao().get(command.sessionId)
        val context = database.focusDao().getContext(command.sessionId)
        val segments = database.focusDao().listSegments(command.sessionId)
        val events = database.focusDao().listEvents(command.sessionId)
        assertNull(focus.applyBehavior(command))
        assertEquals(session, database.sessionDao().get(command.sessionId))
        assertEquals(context, database.focusDao().getContext(command.sessionId))
        assertEquals(segments, database.focusDao().listSegments(command.sessionId))
        assertEquals(events, database.focusDao().listEvents(command.sessionId))
    }
}
