package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultNoteRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudyWorkflowRepositoryTest {
    private lateinit var database: MirraDatabase
    private lateinit var learningItems: DefaultLearningItemRepository
    private lateinit var workflow: DefaultStudyWorkflowRepository
    private lateinit var notes: DefaultNoteRepository
    private var now = 1_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MirraDatabase::class.java).build()
        learningItems = DefaultLearningItemRepository(database, clock = { now })
        workflow = DefaultStudyWorkflowRepository(
            database = database,
            summaryEngine = RuleBasedSummaryEngine(),
            expiryPolicy = IntentExpiryPolicy(),
            clock = { now },
        )
        notes = DefaultNoteRepository(database, clock = { now })
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun settingNewMainlineAtomicallyClearsPreviousMainline() = runTest {
        val first = learningItems.create("第一本书", totalPages = 200, currentPage = 20)
        val second = learningItems.create("第二本书", totalPages = 300, currentPage = 8)

        learningItems.setMainline(first.id)
        learningItems.setMainline(second.id)

        assertEquals(second.id, learningItems.observeMainline().first()?.id)
        assertNull(learningItems.get(first.id)?.mainlineSlot)
    }

    @Test
    fun duplicateActiveIntentIsRejectedByDatabaseConstraint() = runTest {
        val item = learningItems.create("书", totalPages = 100, currentPage = 1)
        database.intentDao().insert(activeIntent(item.id, "intent-1"))

        assertSuspendThrows<Exception> {
            database.intentDao().insert(activeIntent(item.id, "intent-2"))
        }
    }

    @Test
    fun startingSessionConvertsIntentInSameTransaction() = runTest {
        val item = learningItems.create("书", totalPages = 100, currentPage = 12)
        val intent = workflow.createIntent(item.id)
        workflow.markTransitioned(intent.id)

        val session = workflow.startSession(intent.id, startPage = 12)

        assertEquals(session.id, workflow.observeActiveSession().first()?.id)
        val converted = database.intentDao().get(intent.id)!!
        assertEquals(IntentOutcome.CONVERTED, converted.outcome)
        assertEquals(now, converted.convertedAt)
        assertEquals(now, converted.endedAt)
        assertNull(converted.activeSlot)
    }

    @Test
    fun abandoningIntentEndsItAndAllowsAnotherItemToCreateIntent() = runTest {
        val firstItem = learningItems.create("第一本", 100, 4)
        val secondItem = learningItems.create("第二本", 100, 7)
        val firstIntent = workflow.createIntent(firstItem.id)
        workflow.markTransitioned(firstIntent.id)

        workflow.abandonIntent(firstIntent.id)
        workflow.abandonIntent(firstIntent.id)

        val abandoned = database.intentDao().get(firstIntent.id)!!
        assertEquals(IntentOutcome.ABANDONED, abandoned.outcome)
        assertNull(abandoned.convertedAt)
        assertEquals(now, abandoned.endedAt)
        assertNull(abandoned.activeSlot)
        assertEquals(secondItem.id, workflow.createIntent(secondItem.id).learningItemId)
    }

    @Test
    fun secondActiveSessionIsRejected() = runTest {
        val firstItem = learningItems.create("第一本", 100, 4)
        val secondItem = learningItems.create("第二本", 100, 7)
        val firstIntent = workflow.createIntent(firstItem.id)
        workflow.startSession(firstIntent.id, 4)

        val secondIntent = activeIntent(secondItem.id, "intent-2")
        database.intentDao().insert(secondIntent)

        assertSuspendThrows<IllegalStateException> {
            workflow.startSession(secondIntent.id, 7)
        }
    }

    @Test
    fun newIntentIsRejectedWhileSessionIsActive() = runTest {
        val firstItem = learningItems.create("第一本", 100, 4)
        val secondItem = learningItems.create("第二本", 100, 7)
        workflow.startSession(workflow.createIntent(firstItem.id).id, 4)

        assertSuspendThrows<IllegalStateException> { workflow.createIntent(secondItem.id) }
        assertNull(workflow.observeActiveIntent().first())
    }

    @Test
    fun sessionAndSummaryProgressNeverMoveBackward() = runTest {
        val item = learningItems.create("书", totalPages = 200, currentPage = 40)
        val intent = workflow.createIntent(item.id)
        val session = workflow.startSession(intent.id, startPage = 40)
        now += 45 * 60 * 1_000L
        workflow.updateCurrentPage(session.id, 35)
        notes.save(session.learningItemId, session.id, "旧页摘录", pageNumber = 12)

        val completed = workflow.finishSession(session.id, endPage = 35)

        assertEquals(SessionEndType.NORMAL, completed.endType)
        assertEquals(40, completed.currentPage)
        assertEquals(40, completed.endPage)
        assertEquals(40, learningItems.get(item.id)?.currentPage)
        assertEquals("本次阅读第 40 页，用时 45 分钟，共记录 1 条笔记。", completed.generatedSummary)
    }

    @Test
    fun oldPageNoteDoesNotChangeSessionOrLearningItemProgress() = runTest {
        val item = learningItems.create("书", totalPages = 200, currentPage = 40)
        val session = workflow.startSession(workflow.createIntent(item.id).id, startPage = 40)
        workflow.updateCurrentPage(session.id, 52)

        val note = notes.save(item.id, session.id, "回看旧页", pageNumber = 12)

        assertEquals(12, note.pageNumber)
        assertEquals(52, database.sessionDao().get(session.id)?.currentPage)
        assertEquals(40, learningItems.get(item.id)?.currentPage)
    }

    @Test
    fun interruptedSessionBecomesAbnormalWithoutAdvancingBook() = runTest {
        val item = learningItems.create("书", totalPages = 200, currentPage = 20)
        val session = workflow.startSession(workflow.createIntent(item.id).id, 20)
        workflow.updateCurrentPage(session.id, 31)
        now += 10_000L

        workflow.recoverInterruptedSession()

        val recovered = database.sessionDao().get(session.id)!!
        assertEquals(SessionEndType.ABNORMAL, recovered.endType)
        assertEquals(31, recovered.endPage)
        assertEquals(20, learningItems.get(item.id)?.currentPage)
        assertNull(workflow.observeActiveSession().first())
    }

    @Test
    fun expiredIntentIsPersistedAsTimeoutWhenSessionStartIsRejected() = runTest {
        val item = learningItems.create("书", totalPages = 100, currentPage = 1)
        val intent = workflow.createIntent(item.id)
        now += 30 * 60 * 1_000L

        assertSuspendThrows<IllegalStateException> { workflow.startSession(intent.id, 1) }

        val timedOut = database.intentDao().get(intent.id)!!
        assertEquals(IntentOutcome.TIMEOUT, timedOut.outcome)
        assertNull(timedOut.convertedAt)
        assertNotNull(timedOut.endedAt)
        assertNull(timedOut.activeSlot)
    }

    @Test
    fun sessionStoresAllFourIndependentNoteTypesWithPageNumbers() = runTest {
        val item = learningItems.create("书", 100, 1)
        val session = workflow.startSession(workflow.createIntent(item.id).id, 1)

        NoteSemanticType.entries.forEachIndexed { index, type ->
            notes.save(item.id, session.id, "note-$type", index + 2, type)
        }

        val saved = notes.observeForSession(session.id).first()
        assertEquals(NoteSemanticType.entries.toSet(), saved.map { it.semanticType }.toSet())
        assertEquals(setOf(2, 3, 4, 5), saved.mapNotNull { it.pageNumber }.toSet())
    }

    private fun activeIntent(learningItemId: String, id: String) = StudyIntentEntity(
        id = id,
        learningItemId = learningItemId,
        createdAt = now,
        transitionedAt = null,
        convertedAt = null,
        endedAt = null,
        outcome = null,
        activeSlot = 1,
    )

    private suspend inline fun <reified T : Throwable> assertSuspendThrows(
        crossinline block: suspend () -> Unit,
    ) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (throwable: Throwable) {
            if (throwable !is T) throw throwable
        }
    }
}
