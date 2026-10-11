package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstActionAdmissionTest {
    private lateinit var database: MirraDatabase
    private lateinit var items: DefaultLearningItemRepository
    private lateinit var workflow: DefaultStudyWorkflowRepository
    private var now = 1_000L

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java).build()
        items = DefaultLearningItemRepository(database, clock = { now })
        workflow = DefaultStudyWorkflowRepository(database, RuleBasedSummaryEngine(), IntentExpiryPolicy(), clock = { now })
    }

    @After fun close() = database.close()

    @Test fun createAllowsBlankActionWithoutCreatingAnIntentOrSession() = runTest {
        val item = items.create("先添加的书", 100, firstAction = "  ", setAsMainline = true)
        assertEquals("", item.firstAction)
        assertEquals(1, items.get(item.id)?.mainlineSlot)
        assertNull(database.intentDao().getActive())
        assertNull(database.sessionDao().getActive())
        rejects { workflow.createIntent(item.id) }
        assertEquals(item, items.get(item.id))
    }

    @Test fun createRejectsInvalidNonblankActionWithoutClearingExistingMainline() = runTest {
        val current = items.create("已有主线", 100, firstAction = "翻到当前页，读第一段。", setAsMainline = true)
        rejects { items.create("新书", 100, firstAction = "读完这本书", setAsMainline = true) }
        assertEquals(listOf(current), database.learningItemDao().listAll())
    }

    @Test fun actionEditRejectsBlankAndKeepsPreviouslyChosenAction() = runTest {
        val item = items.create("动作编辑", 100, firstAction = "把书放到桌面，读第一段。")
        rejects { items.updateFirstAction(item.id, " ") }
        assertEquals(item.firstAction, items.get(item.id)?.firstAction)
    }

    @Test fun blankAndKnownAutomaticLegacyActionsCannotCreateNewIntent() = runTest {
        for ((id, action) in listOf("blank" to "", "generated" to "拿起《旧书》，翻到第 9 页。")) {
            val item = legacyItem(id, action)
            database.learningItemDao().insert(item)
            rejects { workflow.createIntent(item.id, setAsMainline = true) }
            assertNull(database.intentDao().getActive())
            assertNull(items.get(item.id)?.mainlineSlot)
        }
    }

    @Test fun legacyActiveIntentContinuesWithoutChangingItsItemOrTimestamps() = runTest {
        val item = legacyItem("legacy-active", "")
        database.learningItemDao().insert(item)
        val intent = StudyIntentEntity("old-intent", item.id, 900, 950, null, null, null, 1)
        database.intentDao().insert(intent)
        assertEquals(intent, workflow.createIntent(item.id))
        rejectsState { items.updateFirstAction(item.id, "翻到第 9 页，读第一段。") }
        assertEquals(item, items.get(item.id))
        now = 1_100
        val session = workflow.startSession(intent.id, 9)
        assertEquals(intent.id, session.intentId)
        assertEquals(900L, database.intentDao().get(intent.id)?.createdAt)
        assertEquals(950L, database.intentDao().get(intent.id)?.transitionedAt)
        assertEquals(IntentOutcome.CONVERTED, database.intentDao().get(intent.id)?.outcome)
    }

    @Test fun expiredLegacyIntentStillTimesOutWhenANewActionIsMissing() = runTest {
        val item = legacyItem("expired", "")
        database.learningItemDao().insert(item)
        val intent = StudyIntentEntity("expired-intent", item.id, 1, null, null, null, null, 1)
        database.intentDao().insert(intent)
        now = 2_000_000L
        rejects { workflow.createIntent(item.id) }
        assertEquals(IntentOutcome.TIMEOUT, database.intentDao().get(intent.id)?.outcome)
        assertNull(database.intentDao().getActive())
    }

    @Test fun confirmedActionIsReusableAndRemainsImmutableDuringActiveWorkflow() = runTest {
        val item = legacyItem("remedied", "")
        database.learningItemDao().insert(item)
        val action = "翻到第 9 页，读这一页的第一段。"
        items.updateFirstAction(item.id, action)
        val first = workflow.createIntent(item.id)
        rejectsState { items.updateFirstAction(item.id, "站起来，把书放到桌面。") }
        workflow.abandonIntent(first.id)
        val second = workflow.createIntent(item.id)
        assertEquals(action, items.get(item.id)?.firstAction)
        assertEquals(IntentOutcome.ABANDONED, database.intentDao().get(first.id)?.outcome)
        workflow.startSession(second.id, 9)
        rejectsState { items.updateFirstAction(item.id, "站起来，把书放到桌面。") }
        assertEquals(1, database.sessionDao().listAll().size)
    }

    @Test fun preparationRepairChangesOnlyCurrentItemAndPreservesIntentAndHistoricalSession() = runTest {
        val item = legacyItem("preparing", "")
        database.learningItemDao().insert(item)
        val oldIntent = StudyIntentEntity("old-history", item.id, 100, 110, 120, 120, IntentOutcome.CONVERTED, null)
        val oldSession = StudySessionEntity("old-session", item.id, oldIntent.id, 120, null, 200,
            1, 9, 9, SessionEndType.NORMAL, "原始总结", null)
        database.intentDao().insert(oldIntent)
        database.sessionDao().insert(oldSession)
        val intent = StudyIntentEntity("current-preparation", item.id, 900, 950, null, null, null, 1)
        database.intentDao().insert(intent)
        now = 1_100
        val updated = items.updatePreparationFirstAction(intent.id, "  翻到第 9 页，读第一段。  ")
        assertEquals(item.copy(firstAction = "翻到第 9 页，读第一段。", updatedAt = 1_100), updated)
        assertEquals(intent, database.intentDao().get(intent.id))
        assertEquals(oldIntent, database.intentDao().get(oldIntent.id))
        assertEquals(listOf(oldSession), database.sessionDao().listAll())
        rejectsState { items.updateFirstAction(item.id, "把书放到桌上。") }
    }

    @Test fun preparationEditRejectsWrongEndedAndExpiredIntentWithoutChangingFacts() = runTest {
        val item = items.create("准备守卫", 100, 9, "翻到第 9 页，读第一段。")
        val ended = workflow.createIntent(item.id)
        workflow.abandonIntent(ended.id)
        val active = workflow.createIntent(item.id)
        val original = items.get(item.id)
        rejectsState { items.updatePreparationFirstAction("wrong-intent", "把书放到桌上。") }
        rejectsState { items.updatePreparationFirstAction(ended.id, "把书放到桌上。") }
        rejects { items.updatePreparationFirstAction(active.id, "今天读30页") }
        now = active.createdAt + IntentExpiryPolicy.DEFAULT_TIMEOUT_MILLIS
        rejectsState { items.updatePreparationFirstAction(active.id, "把书放到桌上。") }
        assertEquals(active, database.intentDao().get(active.id))
        assertEquals(original, items.get(item.id))
        assertNull(database.sessionDao().getActive())
    }

    @Test fun preparationEditRejectsNonProgressItemWithoutChangingIntent() = runTest {
        val item = items.create("状态已变化", 100, 9, "翻到第 9 页，读第一段。")
        val intent = workflow.createIntent(item.id)
        database.learningItemDao().markPaused(item.id, now)
        val paused = items.get(item.id)
        rejectsState { items.updatePreparationFirstAction(intent.id, "把书放到桌上。") }
        assertEquals(paused, items.get(item.id))
        assertEquals(intent, database.intentDao().get(intent.id))
    }

    @Test fun preparationEditCannotChangeConvertedOrPendingSessionFacts() = runTest {
        val item = items.create("已经开始", 100, 9, "翻到第 9 页，读第一段。")
        val intent = workflow.createIntent(item.id)
        val session = workflow.startSession(intent.id, 9)
        rejectsState { items.updatePreparationFirstAction(intent.id, "把书放到桌上。") }
        assertEquals(session, database.sessionDao().get(session.id))
        now = 2_000
        val pending = workflow.beginCloseout(session.id, 9, now)
        val converted = database.intentDao().get(intent.id)
        val pendingContext = database.focusDao().getContext(session.id)
        rejectsState { items.updatePreparationFirstAction(intent.id, "把书放到桌上。") }
        assertEquals(item.firstAction, items.get(item.id)?.firstAction)
        assertEquals(converted, database.intentDao().get(intent.id))
        assertEquals(session, database.sessionDao().get(session.id))
        assertEquals(pending, workflow.getCloseoutSnapshot(session.id))
        assertEquals(pendingContext, database.focusDao().getContext(session.id))
    }

    @Test fun preparationEditRejectsAnActiveSessionForAnyLearningItem() = runTest {
        val item = items.create("准备中的书", 100, 9, "翻到第 9 页，读第一段。")
        val intent = workflow.createIntent(item.id)
        val other = items.create("正在阅读的另一书", 100, 1, "把书放到桌上。")
        val otherIntent = StudyIntentEntity("other-intent", other.id, 100, 110, 120, 120, IntentOutcome.CONVERTED, null)
        val otherSession = StudySessionEntity("other-session", other.id, otherIntent.id, 120, null, null,
            1, 1, null, null, null, 1)
        database.intentDao().insert(otherIntent)
        database.sessionDao().insert(otherSession)
        rejectsState { items.updatePreparationFirstAction(intent.id, "把书放到桌上。") }
        assertEquals(item, items.get(item.id))
        assertEquals(intent, database.intentDao().get(intent.id))
        assertEquals(otherSession, database.sessionDao().get(otherSession.id))
    }

    @Test fun preparationEditAndSessionStartRaceNeverMutatesActionAfterSessionCommit() = runTest {
        val item = items.create("事务边界", 100, 9, "把书放到桌上。")
        val intent = workflow.createIntent(item.id)
        val chosen = "翻到第 9 页，读第一段。"
        coroutineScope {
            val start = async { workflow.startSession(intent.id, 9) }
            val edit = async { runCatching { items.updatePreparationFirstAction(intent.id, chosen) } }
            val session = start.await()
            val result = edit.await()
            assertEquals(if (result.isSuccess) chosen else item.firstAction, items.get(item.id)?.firstAction)
            if (result.isFailure) org.junit.Assert.assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertEquals(listOf(session), database.sessionDao().listAll())
            assertEquals(IntentOutcome.CONVERTED, database.intentDao().get(intent.id)?.outcome)
            rejectsState { items.updatePreparationFirstAction(intent.id, "再把书放到桌上。") }
            assertEquals(session, database.sessionDao().get(session.id))
        }
    }

    private fun legacyItem(id: String, action: String) = LearningItemEntity(
        id, "旧书", LearningItemStatus.IN_PROGRESS, 100, 9, null, action, 800, 800, null,
    )

    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected invalid First Action rejection") }
        catch (failure: IllegalArgumentException) { /* Expected admission rejection. */ }
    }

    private suspend fun rejectsState(block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected immutable active action rejection") }
        catch (failure: IllegalStateException) { /* Expected active-workflow rejection. */ }
    }
}
