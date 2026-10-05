package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.local.model.EffectiveReadingSource
import com.guanyi.mirra.data.repository.DefaultReadingAnalyticsRepository
import com.guanyi.mirra.domain.SessionTimelineValidator
import com.guanyi.mirra.domain.TimelineTrust
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class EffectiveReadingRepositoryTest {
    private lateinit var database: MirraDatabase
    private lateinit var repository: DefaultReadingAnalyticsRepository
    private val queries = Collections.synchronizedList(mutableListOf<String>())
    private val validator = SessionTimelineValidator()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                val normalized = sql.trim().replace(Regex("\\s+"), " ").lowercase()
                if (normalized.startsWith("select s.* from study_sessions") ||
                    normalized.startsWith("select * from session_focus_contexts where sessionid in") ||
                    normalized.startsWith("select * from session_segments where sessionid in")
                ) queries.add(normalized)
            }, Executor { it.run() })
            .build()
        repository = DefaultReadingAnalyticsRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test fun oneAndFortySessionsUseSameBatchQueryBudget() = runTest {
        insertItem("book")
        var previous = 0
        for (count in listOf(1, 40)) {
            database.withTransaction { for (i in previous until count) insertSession("s-$i", endedAt = 10_000L + i) }
            queries.clear()
            val source = source().first()
            assertComplete(source, count)
            assertBudget(count, 3)
            previous = count
        }
    }

    @Test fun largeSourceUsesBoundedBatchesWithoutDroppingSessions() = runTest {
        insertItem("book")
        var previous = 0
        for ((count, budget) in listOf(800 to 3, 801 to 5, 1601 to 7)) {
            database.withTransaction { for (i in previous until count) insertSession("s-$i", endedAt = 10_000L + i) }
            queries.clear()
            val source = source().first()
            assertComplete(source, count)
            assertBudget(count, budget)
            assertTrue(queries.filter { "sessionid in" in it }.all { sql -> sql.count { it == '?' } <= 800 })
            previous = count
        }
    }

    @Test fun emptySessionSourceDoesNotQueryFocusTables() = runTest {
        insertItem("book")
        queries.clear()
        val empty = source().first()
        assertEquals(EffectiveReadingSource(emptyList(), emptyMap(), emptyMap()), empty)
        assertBudget(0, 1)
    }

    @Test fun effectiveSourceKeepsInclusiveBoundsAndItemIsolation() = runTest {
        insertItem("book"); insertItem("other")
        insertSession("start", endedAt = 2000)
        insertSession("end-a", endedAt = 3000)
        insertSession("end-b", endedAt = 3000, endType = SessionEndType.ABNORMAL)
        insertSession("outside", endedAt = 3001)
        insertSession("old", endedAt = 2500, facts = false)
        insertSession("wrong-item", itemId = "other", endedAt = 2500)
        val source = repository.observeEffectiveRecentForItem("book", 2000, 3000).first()
        assertEquals(listOf("end-b", "end-a", "old", "start"), source.sessions.map { it.id })
        assertEquals(SessionEndType.ABNORMAL, source.sessions.first().endType)
        assertNull(source.contexts["old"])
        assertNull(source.segments["old"])
        assertEquals(setOf("end-b", "end-a", "start"), source.contexts.keys)
        assertEquals(source.contexts.keys, source.segments.keys)
    }

    @Test fun sourceRefreshesWhenContextOrSegmentsChange() = runTest {
        insertItem("book"); insertSession("s")
        val updates = Channel<EffectiveReadingSource>(Channel.UNLIMITED)
        val collector = launch { source().collect { updates.send(it) } }
        try {
            val first = updates.next { it.contexts["s"] != null }
            val expected = analyze(first)
            database.focusDao().setDndLifecycle("s", DndLifecycle.ACTIVE, 20_001)
            val dndUpdate = updates.next { it.contexts["s"]?.dndLifecycle == DndLifecycle.ACTIVE }
            assertEquals(expected, analyze(dndUpdate))
            // Fixture mutations test read invalidation, not production permission to alter ended facts.
            database.withTransaction {
                database.openHelper.writableDatabase.execSQL("UPDATE session_segments SET type = 'BREAK' WHERE sessionId = 's'")
            }
            val segmentUpdate = updates.next { it.segments["s"]?.firstOrNull()?.type == SessionSegmentType.BREAK }
            assertEquals(0L, analyze(segmentUpdate).effectiveFocusMillis)
            database.focusDao().setCoverage("s", MonitoringCoverage.PARTIAL, 19_999, 20_002)
            val lost = updates.next { it.contexts["s"]?.monitoringStatus == MonitoringCoverage.PARTIAL }
            assertEquals(TimelineTrust.MONITORING_INCOMPLETE, analyze(lost).trust)
        } finally { collector.cancelAndJoin(); updates.close() }
    }

    @Test fun incompleteFlowSourceCannotBecomeTrusted() = runTest {
        insertItem("book"); insertSession("s", facts = false)
        val updates = Channel<EffectiveReadingSource>(Channel.UNLIMITED)
        val collector = launch { source().collect { updates.send(it) } }
        try {
            assertNull(analyze(updates.next { it.sessions.isNotEmpty() }).effectiveFocusMillis)
            database.focusDao().insertContext(context("s", 20_000))
            val missingSegments = updates.next { it.contexts["s"] != null }
            assertEquals(TimelineTrust.STRUCTURE_INVALID, analyze(missingSegments).trust)
            assertNull(analyze(missingSegments).effectiveFocusMillis)
            database.focusDao().insertSegment(segment("s", 20_000).copy(endedAt = null, activeSlot = 1))
            val open = updates.next { it.segments["s"]?.isNotEmpty() == true }
            assertNull(analyze(open).effectiveFocusMillis)
            database.withTransaction {
                database.openHelper.writableDatabase.execSQL("UPDATE session_segments SET endedAt = 20000, activeSlot = NULL WHERE sessionId = 's'")
            }
            val closed = updates.next { it.segments["s"]?.firstOrNull()?.endedAt == 20_000L }
            assertEquals(TimelineTrust.COMPLETE_TRUSTED, analyze(closed).trust)
        } finally { collector.cancelAndJoin(); updates.close() }
    }

    @Test fun parentRefreshUsesOnlyTheNewCapturedSessionSet() = runTest {
        insertItem("book"); insertSession("s")
        val updates = Channel<EffectiveReadingSource>(Channel.UNLIMITED)
        val collector = launch { source().collect { updates.send(it) } }
        try {
            updates.next { it.sessions.size == 1 }
            database.withTransaction { insertSession("new", endedAt = 20_001) }
            val next = updates.next { it.sessions.size == 2 }
            assertEquals(setOf("s", "new"), next.contexts.keys)
            assertEquals(setOf("s", "new"), next.segments.keys)
            assertTrue(next.sessions.all { validator.analyze(it, next.contexts[it.id], next.segments[it.id].orEmpty()).trust == TimelineTrust.COMPLETE_TRUSTED })
        } finally { collector.cancelAndJoin(); updates.close() }
    }

    private fun source() = repository.observeEffectiveRecentForItem("book", 0, 100_000)
    private fun analyze(source: EffectiveReadingSource) = validator.analyze(source.sessions.single(), source.contexts["s"], source.segments["s"].orEmpty())

    private suspend fun Channel<EffectiveReadingSource>.next(predicate: (EffectiveReadingSource) -> Boolean): EffectiveReadingSource =
        withContext(Dispatchers.IO) {
            // Room queries use real executors; a virtual timeout races ahead of their results.
            withTimeout(10_000) { var value = receive(); while (!predicate(value)) value = receive(); value }
        }

    private fun assertComplete(source: EffectiveReadingSource, count: Int) {
        assertEquals(count, source.sessions.size)
        assertEquals(count, source.contexts.size)
        assertEquals(count, source.segments.size)
        assertEquals(count, source.segments.values.sumOf { it.size })
        source.sessions.forEach { assertEquals(TimelineTrust.COMPLETE_TRUSTED,
            validator.analyze(it, source.contexts[it.id], source.segments[it.id].orEmpty()).trust) }
    }

    private fun assertBudget(count: Int, expected: Int) {
        val snapshot = synchronized(queries) { queries.toList() }
        assertEquals("N=$count: $snapshot", expected, snapshot.size)
        assertEquals(1, snapshot.count { "from study_sessions" in it })
        println("Effective source N=$count initial business SELECTs=${snapshot.size}")
    }

    private suspend fun insertItem(id: String) = database.learningItemDao().insert(
        LearningItemEntity(id, id, LearningItemStatus.IN_PROGRESS, 320, 5, null, "", 1, 1, null))

    private suspend fun insertSession(id: String, itemId: String = "book", endedAt: Long = 20_000,
        endType: SessionEndType = SessionEndType.NORMAL, facts: Boolean = true) {
        database.intentDao().insert(StudyIntentEntity("i-$id", itemId, 1, 1, 1, 1, IntentOutcome.CONVERTED, null))
        database.sessionDao().insert(StudySessionEntity(id, itemId, "i-$id", endedAt - 600, null, endedAt,
            1, 5, 5, endType, null, null))
        if (facts) {
            database.focusDao().insertContext(context(id, endedAt))
            database.focusDao().insertSegment(segment(id, endedAt))
        }
    }

    private fun context(id: String, end: Long) = SessionFocusContextEntity(sessionId = id,
        monitoringStatus = MonitoringCoverage.FULL, monitoringLostAt = null, priorDndInterruptionFilter = null,
        dndRuleId = null, requestedEndPage = null, closeoutStartedAt = null, lastHeartbeatAt = end,
        createdAt = end - 600, updatedAt = end)

    private fun segment(id: String, end: Long) = SessionSegmentEntity("seg-$id", id, SessionSegmentType.FOCUS,
        end - 600, end, null, null, null, relatedSegmentId = null, activeSlot = null)
}
