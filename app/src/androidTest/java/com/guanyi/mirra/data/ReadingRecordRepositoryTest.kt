package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.local.model.ReadingRecordSource
import com.guanyi.mirra.data.repository.DefaultReadingRecordRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class ReadingRecordRepositoryTest {
    private lateinit var database: MirraDatabase
    private lateinit var repository: DefaultReadingRecordRepository
    private val queries = Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                val query = sql.trim().replace(Regex("\\s+"), " ").lowercase()
                if (query.startsWith("select") && listOf("study_sessions", "session_focus_contexts",
                        "session_segments", "session_risk_app_snapshots", "notes").any { "from $it" in query }) queries.add(query)
            }, Executor { it.run() }).build()
        repository = DefaultReadingRecordRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test fun recordSourceUsesExistingFactsAndNonBlankNoteCount() = runTest {
        insertSession()
        for ((id, content) in listOf("a" to "", "b" to "   ", "c" to "记录", "d" to " 第二条 ")) insertNote(id, content)
        val source = repository.observe("s").first()!!
        assertEquals("s", source.session.id)
        assertEquals(MonitoringCoverage.FULL, source.context?.monitoringStatus)
        assertEquals(listOf("seg-0"), source.segments.map { it.id })
        assertEquals("当时名称", source.riskSnapshots.single().labelSnapshot)
        assertEquals(2, source.noteCount)
        assertFalse(queries.any { "select * from notes" in it })
        assertEquals("", database.noteDao().get("a")?.content)
    }

    @Test fun recordSourceMissingSessionIsNull() = runTest {
        assertNull(repository.observe("missing").first())
        insertSession(facts = false)
        val old = repository.observe("s").first()!!
        assertNull(old.context)
        assertTrue(old.segments.isEmpty())
        assertTrue(old.riskSnapshots.isEmpty())
        assertEquals(0, old.noteCount)
    }

    @Test fun recordSourceRefreshesAfterNoteOrDndMetadataChanges() = runTest {
        insertSession()
        val updates = Channel<ReadingRecordSource?>(Channel.UNLIMITED)
        val collector = launch { repository.observe("s").collect { updates.send(it) } }
        try {
            val original = updates.next { it?.context != null }!!
            insertNote("n", "新笔记")
            assertEquals(1, updates.next { it?.noteCount == 1 }?.noteCount)
            database.focusDao().setDndLifecycle("s", DndLifecycle.RELEASE_FAILED, 30_000)
            val updated = updates.next { it?.context?.dndLifecycle == DndLifecycle.RELEASE_FAILED }!!
            assertEquals(original.session, updated.session)
            assertEquals(original.segments, updated.segments)
            database.noteDao().updateContent("n", " ", NoteSemanticType.UNDERSTANDING, 5, 30_001)
            assertEquals(0, updates.next { it?.noteCount == 0 }?.noteCount)
        } finally { collector.cancelAndJoin(); updates.close() }
    }

    @Test fun singleRecordUsesFixedQueryBudget() = runTest {
        for (count in listOf(1, 40)) {
            if (count == 1) insertSession()
            else for (i in 1 until count) database.focusDao().insertSegment(segment(i))
            queries.clear()
            val source = repository.observe("s").first()!!
            assertEquals(count, source.segments.size)
            assertEquals("Five reads independent of segment count: $queries", 5, queries.size)
            assertEquals(1, queries.count { "from notes" in it })
            assertTrue(queries.single { "from notes" in it }.contains("trim(content) != ''"))
        }
    }

    private suspend fun Channel<ReadingRecordSource?>.next(predicate: (ReadingRecordSource?) -> Boolean) = withContext(Dispatchers.IO) {
        withTimeout(10_000) { var value = receive(); while (!predicate(value)) value = receive(); value }
    }

    private suspend fun insertSession(facts: Boolean = true) {
        database.learningItemDao().insert(LearningItemEntity("book", "专用记录测试", LearningItemStatus.IN_PROGRESS, 320, 5, null, "", 1, 1, null))
        database.intentDao().insert(StudyIntentEntity("i", "book", 1, 1, 1, 1, IntentOutcome.CONVERTED, null))
        database.sessionDao().insert(StudySessionEntity("s", "book", "i", 1_000, null, 20_000, 1, 5, 5, SessionEndType.NORMAL, "原总结", null))
        if (facts) {
            database.focusDao().insertContext(SessionFocusContextEntity(sessionId = "s", monitoringStatus = MonitoringCoverage.FULL,
                monitoringLostAt = null, priorDndInterruptionFilter = null, dndRuleId = null, requestedEndPage = null,
                closeoutStartedAt = null, lastHeartbeatAt = 20_000, createdAt = 1_000, updatedAt = 20_000))
            database.focusDao().insertSegment(segment(0))
            database.focusDao().insertRiskSnapshots(listOf(SessionRiskAppSnapshotEntity("s", "com.test.risk", "当时名称")))
        }
    }

    private fun segment(index: Int) = SessionSegmentEntity("seg-$index", "s", SessionSegmentType.FOCUS,
        1_000L + index * 100, 1_100L + index * 100, null, null, null, relatedSegmentId = null, activeSlot = null)

    private suspend fun insertNote(id: String, content: String) = database.noteDao().insert(
        NoteEntity(id, "book", "s", NoteSemanticType.UNDERSTANDING, content, 5, 1, 1))
}
