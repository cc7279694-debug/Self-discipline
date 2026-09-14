package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.NoteEntity
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.repository.DefaultReadingAnalyticsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingAnalyticsRepositoryTest {
    private lateinit var database: MirraDatabase
    private lateinit var repository: DefaultReadingAnalyticsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MirraDatabase::class.java).build()
        repository = DefaultReadingAnalyticsRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun historyIsDescendingIncludesAbnormalAndCountsOnlyNonBlankNotes() = runTest {
        insertItem("book")
        insertSession("older", "book", endedAt = 2_000L, endType = SessionEndType.NORMAL)
        insertSession("newer", "book", endedAt = 3_000L, endType = SessionEndType.ABNORMAL)
        insertNote("n1", "book", "older", "内容")
        insertNote("n2", "book", "older", "  ")

        val history = repository.observeHistory("book").first()

        assertEquals(listOf("newer", "older"), history.map { it.sessionId })
        assertEquals(SessionEndType.ABNORMAL, history.first().endType)
        assertEquals(1, history.last().noteCount)
    }

    @Test
    fun recentItemQueryFiltersByLearningItemAndInclusiveTimeBounds() = runTest {
        insertItem("book")
        insertItem("other")
        insertSession("at-start", "book", endedAt = 2_000L)
        insertSession("inside", "book", endedAt = 2_500L)
        insertSession("at-end", "book", endedAt = 3_000L)
        insertSession("outside", "book", endedAt = 3_001L)
        insertSession("wrong-item", "other", endedAt = 2_500L)

        val recent = repository.observeRecentForItem("book", 2_000L, 3_000L).first()

        assertEquals(listOf("at-end", "inside", "at-start"), recent.map { it.sessionId })
    }

    @Test
    fun fourteenDaySourceUsesOneSessionSetAndSeparateNoteWindows() = runTest {
        insertItem("book")
        insertSession("previous", "book", endedAt = 1_500L)
        insertSession("current", "book", endedAt = 2_500L)
        insertNote("previous-note", "book", null, "旧笔记", createdAt = 1_500L)
        insertNote("current-note", "book", null, "新笔记", createdAt = 2_500L)
        insertNote("blank", "book", null, " ", createdAt = 2_600L)

        val source = repository.observeFourteenDaySource(
            fromInclusive = 1_000L,
            currentPeriodStart = 2_000L,
            toExclusive = 3_000L,
        ).first()

        assertEquals(listOf("current", "previous"), source.sessions.map { it.sessionId })
        assertEquals(1, source.currentNoteCount)
        assertEquals(1, source.previousNoteCount)
    }

    @Test
    fun tenThousandSessionHistoryUsesOneProjectionQueryAndReturnsCompleteResults() = runTest {
        insertItem("book")
        database.withTransaction {
            repeat(10_000) { index ->
                insertSession("history-$index", "book", endedAt = 10_000L + index)
            }
        }

        val started = android.os.SystemClock.elapsedRealtime()
        val history = repository.observeHistory("book").first()
        val elapsed = android.os.SystemClock.elapsedRealtime() - started

        assertEquals(10_000, history.size)
        assertEquals("history-9999", history.first().sessionId)
        assertEquals("history-0", history.last().sessionId)
        println("10k analytics history projection completed in ${elapsed}ms")
    }

    private suspend fun insertItem(id: String) {
        database.learningItemDao().insert(
            LearningItemEntity(id, id, LearningItemStatus.IN_PROGRESS, 320, 1, null, "", 1L, 1L, null),
        )
    }

    private suspend fun insertSession(
        id: String,
        learningItemId: String,
        endedAt: Long,
        endType: SessionEndType = SessionEndType.NORMAL,
    ) {
        val intentId = "intent-$id"
        database.intentDao().insert(
            StudyIntentEntity(intentId, learningItemId, 1L, 1L, 1L, 1L, IntentOutcome.CONVERTED, null),
        )
        database.sessionDao().insert(
            StudySessionEntity(
                id = id,
                learningItemId = learningItemId,
                intentId = intentId,
                startedAt = endedAt - 600L,
                stableStartedAt = null,
                endedAt = endedAt,
                startPage = 1,
                currentPage = 5,
                endPage = 5,
                endType = endType,
                generatedSummary = null,
                activeSlot = null,
            ),
        )
    }

    private suspend fun insertNote(
        id: String,
        learningItemId: String,
        sessionId: String?,
        content: String,
        createdAt: Long = 1_000L,
    ) {
        database.noteDao().insert(
            NoteEntity(id, learningItemId, sessionId, NoteSemanticType.UNDERSTANDING, content, null, createdAt, createdAt),
        )
    }
}
