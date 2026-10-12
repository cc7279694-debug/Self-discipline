package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.domain.ConfirmedSessionStart
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import com.guanyi.mirra.domain.StalePreparationException
import com.guanyi.mirra.platform.focus.MonitoringReadyLease
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated in-memory Room proves the final transaction, not a UI precheck. */
@RunWith(AndroidJUnit4::class)
class ConfirmedMonitoredStartRepositoryTest {
    private lateinit var database: MirraDatabase
    private lateinit var items: DefaultLearningItemRepository
    private lateinit var workflow: DefaultStudyWorkflowRepository
    private var now = 1_000_000L

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java).build()
        items = DefaultLearningItemRepository(database, clock = { now })
        workflow = DefaultStudyWorkflowRepository(database, RuleBasedSummaryEngine(), IntentExpiryPolicy(), clock = { now })
    }

    @After fun close() = database.close()

    @Test fun exactConfirmedPreparationCommitsOnlyMonitoredFacts() = runTest {
        val expected = preparation()
        workflow.validateConfirmedPreparation(expected)
        val result = start(expected)
        assertTrue(result.created)
        assertEquals(expected.startPage, result.session.startPage)
        assertEquals(expected.learningItemId, result.session.learningItemId)
        assertEquals(MonitoringCoverage.FULL, database.focusDao().getContext(result.session.id)?.monitoringStatus)
        assertEquals(SessionSegmentType.FOCUS, database.focusDao().getActiveSegment(result.session.id)?.type)
        assertEquals(IntentOutcome.CONVERTED, database.intentDao().get(expected.intentId)?.outcome)
    }

    @Test fun revokedAdmissionCannotEnterSessionTransaction() = runTest {
        val expected = preparation()
        rejected { workflow.startConfirmedMonitoredSession(expected, lease(), "revoked", stillAuthorized = { false }) }
        assertNoSessionAndActiveIntent(expected)
    }

    @Test fun revokedAdmissionAfterFactReadsIsRecheckedBeforeSessionInsert() = runTest {
        val expected = preparation()
        var firstCheck = true
        rejected {
            workflow.startConfirmedMonitoredSession(expected, lease(), "revoked-before-insert", stillAuthorized = {
                firstCheck.also { firstCheck = false }
            })
        }
        assertNoSessionAndActiveIntent(expected)
    }

    @Test fun actionEditedAfterPrecheckIsRejectedInsideSessionTransaction() = runTest {
        val expected = preparation()
        workflow.validateConfirmedPreparation(expected)
        items.updatePreparationFirstAction(expected.intentId, "站起来，把书放到桌上。")
        rejected { start(expected) }
        assertNoSessionAndActiveIntent(expected)
        assertEquals("站起来，把书放到桌上。", items.get(expected.learningItemId)?.firstAction)
    }

    @Test fun pageAdvancedAfterPrecheckIsRejectedInsideSessionTransaction() = runTest {
        val expected = preparation()
        workflow.validateConfirmedPreparation(expected)
        database.learningItemDao().advanceProgress(expected.learningItemId, expected.startPage + 1, now)
        rejected { start(expected) }
        assertNoSessionAndActiveIntent(expected)
    }

    @Test fun pausedBookAfterPrecheckCannotStartSession() = runTest {
        val expected = preparation()
        workflow.validateConfirmedPreparation(expected)
        database.learningItemDao().markPaused(expected.learningItemId, now)
        rejected { start(expected) }
        assertNoSessionAndActiveIntent(expected)
    }

    @Test fun expiredIntentIsRejectedWithoutConvertingOrDeletingIt() = runTest {
        val expected = preparation()
        val before = database.intentDao().get(expected.intentId)
        now += IntentExpiryPolicy.DEFAULT_TIMEOUT_MILLIS
        rejected { workflow.validateConfirmedPreparation(expected) }
        rejected { start(expected) }
        assertEquals(before, database.intentDao().get(expected.intentId))
        assertNull(database.sessionDao().getActive())
    }

    @Test fun wrongBookOrPageOrActionCannotBypassActualPreparation() = runTest {
        val expected = preparation()
        for (changed in listOf(expected.copy(learningItemId = "another-book"),
            expected.copy(startPage = expected.startPage + 1), expected.copy(firstAction = "把书放到桌上。"))) {
            rejected { workflow.validateConfirmedPreparation(changed) }
            rejected { start(changed) }
            assertNoSessionAndActiveIntent(expected)
        }
    }

    @Test fun legacyAutomaticActionCannotBeConfirmedByWrist() = runTest {
        val expected = preparation()
        val legacy = "拿起《腕端事务测试》，翻到第 9 页。"
        database.learningItemDao().updateFirstAction(expected.learningItemId, legacy, now)
        rejected { start(expected.copy(firstAction = legacy)) }
        assertNoSessionAndActiveIntent(expected)
    }

    @Test fun abandonedIntentCannotBeRecreatedByWrist() = runTest {
        val expected = preparation()
        workflow.abandonIntent(expected.intentId)
        val before = database.intentDao().get(expected.intentId)
        rejected { start(expected) }
        assertEquals(before, database.intentDao().get(expected.intentId))
        assertNull(database.sessionDao().getActive())
    }

    @Test fun duplicateConcurrentStartReturnsOnePersistentSession() = runTest {
        val expected = preparation()
        coroutineScope {
            val first = async { start(expected, "first") }
            val second = async { start(expected, "second") }
            val outcomes = listOf(first.await(), second.await())
            assertEquals(1, outcomes.count { it.created })
            assertEquals(outcomes[0].session.id, outcomes[1].session.id)
        }
        assertEquals(1, database.sessionDao().listAll().size)
    }

    @Test fun lateConfirmationCannotTouchSavingSession() = runTest {
        val expected = preparation()
        val session = start(expected).session
        now += 1_000
        workflow.beginCloseout(session.id, expected.startPage, now)
        val before = database.focusDao().getContext(session.id)
        rejected { start(expected, "late") }
        assertEquals(before, database.focusDao().getContext(session.id))
        assertEquals(listOf(session), database.sessionDao().listAll())
    }

    private suspend fun preparation(): ConfirmedSessionStart {
        val item = items.create("腕端事务测试", 100, 9, "翻到第 9 页，读第一段。")
        val intent = workflow.createIntent(item.id)
        return ConfirmedSessionStart(intent.id, item.id, item.currentPage, item.firstAction)
    }

    private suspend fun start(expected: ConfirmedSessionStart, id: String = "confirmed-session") =
        workflow.startConfirmedMonitoredSession(expected, lease(), id)

    private fun lease() = MonitoringReadyLease("ready", now, 1, 1, now, 1, 0, false, false)

    private suspend fun assertNoSessionAndActiveIntent(expected: ConfirmedSessionStart) {
        assertNull(database.sessionDao().getActive())
        assertEquals(1, database.intentDao().get(expected.intentId)?.activeSlot)
        assertNull(database.intentDao().get(expected.intentId)?.outcome)
    }

    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Expected authoritative preparation rejection") }
        catch (_: StalePreparationException) { }
    }
}
