package com.guanyi.mirra.data

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.repository.DefaultReadingAnalyticsRepository
import com.guanyi.mirra.data.repository.DefaultReadingInsightRepository
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.insights.ReadingPaceInsightDirection
import com.guanyi.mirra.domain.insights.ReadingPaceInsightResult
import com.guanyi.mirra.domain.insights.ReadingPaceInsightUnavailableReason
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
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.minutes

@RunWith(AndroidJUnit4::class)
class ReadingInsightRepositoryTest {
    private lateinit var database: MirraDatabase
    private lateinit var repository: DefaultReadingInsightRepository
    private data class Query(val sql: String, val args: List<Any?>, val onMainThread: Boolean)
    private val queries = Collections.synchronizedList(mutableListOf<Query>())
    private val time = AnalyticsTimeContext(Instant.parse("2026-10-07T12:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val now get() = time.now.toEpochMilli()
    private val focusMillis = 720_000L

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java)
            .setQueryCallback({ sql, args ->
                val normalized = sql.trim().replace(Regex("\\s+"), " ").lowercase()
                if (normalized.startsWith("select ") && listOf("study_sessions", "session_focus_contexts", "session_segments")
                        .any { "from $it" in normalized }) {
                    queries.add(Query(normalized, args.toList(), Looper.myLooper() == Looper.getMainLooper()))
                }
            }, Executor { it.run() }).build()
        repository = DefaultReadingInsightRepository(DefaultReadingAnalyticsRepository(database))
    }

    @After fun tearDown() = database.close()

    @Test fun normalFullTrustedFactsProduceWeightedSameBookInsight() = runTest {
        insertItem("book")
        insertCohort(8)
        val insight = requireNotNull(read().insight)
        assertEquals(ReadingPaceInsightDirection.SLOWER, insight.direction)
        assertEquals(listOf("s-00000", "s-00001", "s-00002"), insight.recent.sessionIds)
        assertEquals(listOf("s-00003", "s-00004", "s-00005", "s-00006", "s-00007"), insight.baseline.sessionIds)
        assertEquals(21L, insight.recent.totalPagesRead)
        assertEquals(2_160_000L, insight.recent.totalEffectiveFocusMillis)
        assertEquals(50L, insight.baseline.totalPagesRead)
        assertEquals(3_600_000L, insight.baseline.totalEffectiveFocusMillis)
        assertEquals(0.70, insight.ratio, 0.000001)
    }

    @Test fun unqualifiedAndOtherBookFactsCannotDisplaceComparableSamples() = runTest {
        insertItem("book"); insertItem("other")
        insertCohort(8)
        database.withTransaction {
            insertSession("partial", now, pages = 100, coverage = MonitoringCoverage.PARTIAL)
            insertSession("none", now, pages = 100, coverage = MonitoringCoverage.NONE)
            insertSession("abnormal", now, pages = 100, endType = SessionEndType.ABNORMAL)
            insertSession("gap", now, pages = 100, timeline = "gap")
            insertSession("overlap", now, pages = 100, timeline = "overlap")
            insertSession("unmonitored", now, pages = 100, timeline = "unmonitored")
            insertSession("future", now + 1, pages = 100)
            insertSession("zero", now, pages = 0)
            insertSession("other", now, pages = 100, itemId = "other")
            insertSession("missing", now, pages = 100, facts = false)
        }
        val insight = requireNotNull(read().insight)
        assertEquals(ReadingPaceInsightDirection.SLOWER, insight.direction)
        assertEquals(listOf("s-00000", "s-00001", "s-00002"), insight.recent.sessionIds)
        assertEquals(5, insight.baseline.sampleCount)
        // Read-only projection: excluded rows and their original histories are retained.
        assertEquals(18, database.sessionDao().observeEndedEntitiesForItemBetween("book", 0, now + 1).first().size +
            database.sessionDao().observeEndedEntitiesForItemBetween("other", 0, now + 1).first().size)
    }

    @Test fun millisecondOrderingPrecedesIdAndExactTiesUseDescendingId() = runTest {
        insertItem("book")
        database.withTransaction {
            insertSession("a-newest", now - 1, 7)
            insertSession("z-tie", now - 2, 7)
            insertSession("b-tie", now - 2, 7)
            insertSession("zz-older", now - 3, 10)
            repeat(4) { insertSession("old-$it", now - 4 - it, 10) }
        }
        val insight = requireNotNull(read().insight)
        assertEquals(listOf("a-newest", "z-tie", "b-tie"), insight.recent.sessionIds)
        assertEquals(listOf("zz-older", "old-0", "old-1", "old-2", "old-3"), insight.baseline.sessionIds)
    }

    @Test fun sourceUsesInclusiveNinetyLocalDaysAndFrozenNow() = runTest {
        insertItem("book")
        val start = Instant.parse("2026-07-09T16:00:00Z").toEpochMilli() // July 10 local midnight.
        database.withTransaction {
            repeat(3) { insertSession("recent-$it", now - it, 7) }
            repeat(4) { insertSession("baseline-$it", now - 86_400_000L * (it + 1), 10) }
            insertSession("boundary", start, 10)
            insertSession("before-boundary", start - 1, 100)
            insertSession("after-now", now + 1, 100)
        }
        queries.clear()
        val insight = requireNotNull(read().insight)
        assertEquals(5, insight.baseline.sampleCount)
        assertTrue("boundary" in insight.baseline.sessionIds)
        val parent = snapshot().single { "from study_sessions" in it.sql }
        assertEquals(listOf("book", start, now), parent.args)
        assertEquals(3, snapshot().size)
        assertEquals(8, snapshot().first { "session_focus_contexts" in it.sql }.args.size)
    }

    @Test fun initialQueryBudgetsRemainBatchedAtZeroEightHundredAndOverflowBoundaries() = runTest {
        insertItem("book")
        var previous = 0
        for ((count, budget) in listOf(0 to 1, 800 to 3, 801 to 5, 1601 to 7)) {
            insertCohort(count, previous)
            queries.clear()
            val result = read()
            if (count == 0) assertNull(result.insight) else {
                val insight = requireNotNull(result.insight)
                assertEquals(ReadingPaceInsightDirection.SLOWER, insight.direction)
                assertEquals(10, insight.baseline.sampleCount)
            }
            assertBudget(count, budget)
            previous = count
        }
    }

    @Test fun contextInvalidationRefreshesAllContextBatchesWithoutReloadingParentOrSegments() = runTest {
        checkInvalidation(context = true)
    }

    @Test fun segmentInvalidationRefreshesAllSegmentBatchesWithoutReloadingParentOrContexts() = runTest {
        checkInvalidation(context = false)
    }

    @Test fun tenThousandNinetyDayFactsAreLoadedInBatchesAndMeasuredBeforeClassification() = runTest(timeout = 3.minutes) {
        insertItem("book")
        insertCohort(10_001)
        // Out-of-window facts must never increase child query cohorts.
        database.withTransaction { repeat(100) { insertSession("ancient-$it", now - 100L * 86_400_000, 100) } }
        repeat(3) { iteration ->
            queries.clear()
            val started = System.nanoTime()
            val insight = requireNotNull(read().insight)
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertEquals(ReadingPaceInsightDirection.SLOWER, insight.direction)
            assertEquals(3, insight.recent.sampleCount)
            assertEquals(10, insight.baseline.sampleCount)
            assertBudget(10_001, 27)
            Log.i("MirraInsightSource", "90-day N=10001 iteration=$iteration readAndAnalyzeMillis=$elapsedMillis businessSELECTs=27")
        }
    }

    private suspend fun checkInvalidation(context: Boolean) {
        insertItem("book"); insertCohort(801)
        val updates = Channel<ReadingPaceInsightResult>(Channel.UNLIMITED)
        val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
            repository.observeForItem("book", time).collect { updates.send(it) }
        }
        try {
            updates.next { it.insight != null }
            assertBudget(801, 5)
            queries.clear()
            // In-memory fixtures only: this exercises invalidation, not permission to mutate ended production facts.
            if (context) database.focusDao().setCoverage("s-00000", MonitoringCoverage.PARTIAL, now - 1000, now)
            else database.withTransaction {
                database.openHelper.writableDatabase.execSQL("UPDATE session_segments SET type = 'BREAK' WHERE sessionId = 's-00000'")
            }
            val changed = updates.next { it.insight == null }
            assertEquals(ReadingPaceInsightUnavailableReason.NO_NOTABLE_CHANGE, changed.unavailableReason)
            val after = snapshot()
            assertEquals(2, after.size)
            assertTrue(after.all { if (context) "session_focus_contexts" in it.sql else "session_segments" in it.sql })
            assertTrue(after.all { it.args.size <= 800 })
            Log.i("MirraInsightSource", "N=801 ${if (context) "context" else "segment"} invalidation businessSELECTs=2 parent=0 otherChild=0")
        } finally { collector.cancelAndJoin(); updates.close() }
    }

    private suspend fun read(): ReadingPaceInsightResult = withContext(Dispatchers.IO) {
        withTimeout(60_000) { repository.observeForItem("book", time).first() }
    }

    private suspend fun Channel<ReadingPaceInsightResult>.next(predicate: (ReadingPaceInsightResult) -> Boolean): ReadingPaceInsightResult =
        withContext(Dispatchers.IO) {
            withTimeout(30_000) { var value = receive(); while (!predicate(value)) value = receive(); value }
        }

    private fun snapshot(): List<Query> = synchronized(queries) { queries.toList() }

    private fun assertBudget(count: Int, expected: Int) {
        val rows = snapshot()
        assertEquals("N=$count", expected, rows.size)
        assertTrue("Business queries must not run on the UI thread", rows.none { it.onMainThread })
        assertEquals(1, rows.count { "from study_sessions" in it.sql })
        for (table in listOf("session_focus_contexts", "session_segments")) {
            val child = rows.filter { table in it.sql }
            assertEquals(count, child.sumOf { it.args.size })
            assertTrue(child.all { it.args.size <= 800 })
        }
        Log.i("MirraInsightSource", "N=$count initial businessSELECTs=$expected maxIdsPerChildQuery=800")
    }

    private suspend fun insertItem(id: String) = database.learningItemDao().insert(
        LearningItemEntity(id, id, LearningItemStatus.IN_PROGRESS, 500, 1, null, "", 1, 1, null))

    private suspend fun insertCohort(count: Int, from: Int = 0) = database.withTransaction {
        for (i in from until count) insertSession("s-${i.toString().padStart(5, '0')}", now - i * 300_000L - 10_000,
            if (i < 3) 7 else 10)
    }

    private suspend fun insertSession(id: String, end: Long, pages: Int, itemId: String = "book",
        coverage: MonitoringCoverage = MonitoringCoverage.FULL, endType: SessionEndType = SessionEndType.NORMAL,
        timeline: String = "focus", facts: Boolean = true) {
        val start = end - focusMillis
        database.intentDao().insert(StudyIntentEntity("i-$id", itemId, start, 1, 1, 1, IntentOutcome.CONVERTED, null))
        database.sessionDao().insert(StudySessionEntity(id, itemId, "i-$id", start, null, end,
            1, pages + 1, pages + 1, endType, null, null))
        if (!facts) return
        database.focusDao().insertContext(SessionFocusContextEntity(sessionId = id, monitoringStatus = coverage,
            monitoringLostAt = if (coverage == MonitoringCoverage.PARTIAL) end - 1 else null,
            priorDndInterruptionFilter = null, dndRuleId = null, requestedEndPage = null, closeoutStartedAt = null,
            lastHeartbeatAt = end, createdAt = start, updatedAt = end))
        fun segment(suffix: String, from: Long, to: Long, type: SessionSegmentType = SessionSegmentType.FOCUS) =
            SessionSegmentEntity("seg-$id-$suffix", id, type, from, to, null, null, null, relatedSegmentId = null, activeSlot = null)
        when (timeline) {
            "gap" -> {
                database.focusDao().insertSegment(segment("1", start, start + 300_000))
                database.focusDao().insertSegment(segment("2", start + 300_001, end))
            }
            "overlap" -> {
                database.focusDao().insertSegment(segment("1", start, start + 300_000))
                database.focusDao().insertSegment(segment("2", start + 299_999, end))
            }
            "unmonitored" -> database.focusDao().insertSegment(segment("1", start, end, SessionSegmentType.UNMONITORED))
            else -> database.focusDao().insertSegment(segment("1", start, end))
        }
    }
}
