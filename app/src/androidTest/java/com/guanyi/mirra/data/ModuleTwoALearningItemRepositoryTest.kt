package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private suspend inline fun <reified T : Throwable> assertLearningItemSuspendThrows(crossinline block: suspend () -> Unit) {
    try {
        block()
        throw AssertionError("Expected ${T::class.java.simpleName}")
    } catch (throwable: Throwable) {
        if (throwable !is T) throw throwable
    }
}

@RunWith(AndroidJUnit4::class)
class ModuleTwoALearningItemRepositoryTest {
    private lateinit var database: MirraDatabase
    private lateinit var learningItems: DefaultLearningItemRepository
    private lateinit var workflow: DefaultStudyWorkflowRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MirraDatabase::class.java).build()
        learningItems = DefaultLearningItemRepository(database, clock = { now })
        workflow = DefaultStudyWorkflowRepository(
            database,
            RuleBasedSummaryEngine(),
            IntentExpiryPolicy(),
            clock = { now },
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun pauseClearsMainlineAndResumeKeepsItClear() = runTest {
        val item = learningItems.create("状态测试", 200, 20)
        learningItems.setMainline(item.id)

        now = 2_000L
        val paused = learningItems.pause(item.id)
        assertEquals(LearningItemStatus.PAUSED, paused.status)
        assertNull(paused.mainlineSlot)
        assertNull(paused.completedAt)
        assertEquals(2_000L, paused.updatedAt)

        now = 3_000L
        val resumed = learningItems.resume(item.id)
        assertEquals(LearningItemStatus.IN_PROGRESS, resumed.status)
        assertNull(resumed.mainlineSlot)
        assertNull(resumed.completedAt)
        assertEquals(3_000L, resumed.updatedAt)
    }

    @Test
    fun completeSetsTimestampClearsMainlineAndCannotResume() = runTest {
        val item = learningItems.create("完成测试", 100)
        learningItems.setMainline(item.id)

        now = 4_000L
        val completed = learningItems.complete(item.id)
        assertEquals(LearningItemStatus.COMPLETED, completed.status)
        assertNull(completed.mainlineSlot)
        assertEquals(4_000L, completed.completedAt)
        assertEquals(4_000L, completed.updatedAt)

        assertLearningItemSuspendThrows<IllegalStateException> { learningItems.resume(item.id) }
    }

    @Test
    fun pausedOrCompletedItemCannotBecomeMainlineOrCreateIntent() = runTest {
        val currentMainline = learningItems.create("当前主线", 100)
        val paused = learningItems.create("暂停内容", 100)
        val completed = learningItems.create("完成内容", 100)
        learningItems.setMainline(currentMainline.id)
        learningItems.pause(paused.id)
        learningItems.complete(completed.id)

        assertLearningItemSuspendThrows<IllegalStateException> { learningItems.setMainline(paused.id) }
        assertEquals(currentMainline.id, learningItems.observeMainline().first()?.id)
        assertLearningItemSuspendThrows<IllegalStateException> { learningItems.setMainline(completed.id) }
        assertEquals(currentMainline.id, learningItems.observeMainline().first()?.id)
        assertLearningItemSuspendThrows<IllegalStateException> { workflow.createIntent(paused.id) }
        assertLearningItemSuspendThrows<IllegalStateException> { workflow.createIntent(completed.id) }
    }

    @Test
    fun activeIntentAndSessionBlockPauseAndCompleteForTheirItem() = runTest {
        val item = learningItems.create("冲突测试", 100, 10)
        val intent = workflow.createIntent(item.id)

        assertLearningItemSuspendThrows<IllegalStateException> { learningItems.pause(item.id) }
        assertLearningItemSuspendThrows<IllegalStateException> { learningItems.complete(item.id) }

        workflow.startSession(intent.id, 10)
        assertLearningItemSuspendThrows<IllegalStateException> { learningItems.pause(item.id) }
        assertLearningItemSuspendThrows<IllegalStateException> { learningItems.complete(item.id) }
    }

    @Test
    fun activeIntentForAnotherItemDoesNotBlockPause() = runTest {
        val active = learningItems.create("正在启动", 100)
        val other = learningItems.create("另一内容", 100)
        workflow.createIntent(active.id)

        assertEquals(LearningItemStatus.PAUSED, learningItems.pause(other.id).status)
    }

    @Test
    fun startSessionRechecksLearningItemStatusBeforeInsert() = runTest {
        val item = learningItems.create("事务内复核", 100, 5)
        val intent = workflow.createIntent(item.id)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE learning_items SET status = 'PAUSED', mainlineSlot = NULL WHERE id = ?",
            arrayOf(item.id),
        )

        assertLearningItemSuspendThrows<IllegalStateException> { workflow.startSession(intent.id, 5) }
        assertNull(workflow.observeActiveSession().first())
    }
}
