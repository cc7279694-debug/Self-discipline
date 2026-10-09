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
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import kotlinx.coroutines.test.runTest
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

    @Test fun createRequiresChosenActionAndDoesNotClearExistingMainlineOnFailure() = runTest {
        val current = items.create("已有主线", 100, firstAction = "翻到当前页，读第一段。", setAsMainline = true)
        rejects { items.create("新书", 100, firstAction = "  ", setAsMainline = true) }
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
