package com.guanyi.mirra.data

import android.content.Context
import android.os.SystemClock
import android.os.Looper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.repository.DefaultTrendsRepository
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.trends.TrendsRange
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.minutes

@RunWith(AndroidJUnit4::class)
class TrendsRepositoryTest {
    private lateinit var database: MirraDatabase
    private lateinit var repository: DefaultTrendsRepository
    private data class Query(val sql: String, val args: List<Any?>, val onMainThread: Boolean)
    private val queries = Collections.synchronizedList(mutableListOf<Query>())
    private val time = AnalyticsTimeContext(Instant.ofEpochMilli(1_000_000), ZoneId.of("UTC"))

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java)
            .setQueryCallback({ sql, args ->
                val normalized = sql.trim().replace(Regex("\\s+"), " ").lowercase()
                if (normalized.startsWith("select") && listOf("study_intents", "study_sessions",
                    "session_focus_contexts", "session_segments", "focus_events").any { "from $it" in normalized }) {
                    queries.add(Query(sql, args.toList(), Looper.myLooper() == Looper.getMainLooper()))
                }
            }, Executor { it.run() }).build()
        repository = DefaultTrendsRepository(database)
    }
    @After fun teardown() { database.close() }

    @Test fun convertedAbnormalAndOpenIntentRetainStartFacts() = runTest {
        seed(1)
        database.openHelper.writableDatabase.execSQL("UPDATE study_sessions SET endType = 'ABNORMAL' WHERE id = 's00000'")
        database.intentDao().insert(StudyIntentEntity("orphan", "book", 10_000, null, 10_001, 10_001, IntentOutcome.CONVERTED, null))
        database.intentDao().insert(StudyIntentEntity("open", "book", 10_000, null, null, null, null, 1))
        queries.clear()
        val actual = repository.load(TrendsRange.ALL, time)
        assertEquals(2L, actual.current.start.convertedCount)
        assertEquals(1L, actual.current.start.openCount)
        assertEquals(2L, actual.current.start.conversion.denominator)
        assertEquals(1L, actual.current.start.startDataIssueCount)
        assertEquals(1L, actual.current.start.stableConfirmedCount)
        assertEquals(0L, actual.current.maintain.trustedSessionCount)
        assertEquals(1L, actual.current.maintain.unavailableSessionCount)
    }

    @Test fun currentAndPreviousUseIndependentCohortsAndOneSnapshot() = runTest {
        seed(1)
        val snapshot = AnalyticsTimeContext(Instant.parse("2026-10-20T12:00:00Z"), ZoneId.of("UTC"))
        database.openHelper.writableDatabase.execSQL("UPDATE study_intents SET createdAt = ?", arrayOf(Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()))
        val start = Instant.parse("2026-10-14T00:00:00Z").toEpochMilli()
        database.openHelper.writableDatabase.execSQL("UPDATE study_sessions SET startedAt = ?, endedAt = ?, stableStartedAt = NULL", arrayOf(start, start + 1_000))
        database.openHelper.writableDatabase.execSQL("UPDATE session_segments SET startedAt = ?, endedAt = ?", arrayOf(start, start + 1_000))
        val actual = repository.load(TrendsRange.SEVEN_DAYS, snapshot)
        assertEquals(0L, actual.current.start.convertedCount)
        assertEquals(1L, actual.previous!!.start.convertedCount)
        assertEquals(1L, actual.current.maintain.trustedSessionCount)
        assertEquals(0L, actual.previous.maintain.trustedSessionCount)
        assertEquals(snapshot, actual.time)
    }

    @Test fun pageBudgetsAreBoundedWithoutTrailingEmptyQueries() = runTest {
        for (count in listOf(0, 1, 800, 801, 1601)) {
            database.withTransaction {
                for (table in listOf("focus_events", "session_segments", "session_focus_contexts", "study_sessions", "study_intents", "learning_items"))
                    database.openHelper.writableDatabase.execSQL("DELETE FROM $table")
            }
            seed(count)
            queries.clear()
            val runtime = Runtime.getRuntime()
            val memoryBefore = runtime.totalMemory() - runtime.freeMemory()
            val started = SystemClock.elapsedRealtime()
            val actual = repository.load(TrendsRange.ALL, time)
            val elapsed = SystemClock.elapsedRealtime() - started
            val memoryAfter = runtime.totalMemory() - runtime.freeMemory()
            assertEquals(count.toLong(), actual.current.start.convertedCount)
            assertEquals(count.toLong(), actual.current.maintain.trustedSessionCount)
            val pages = (count + 799) / 800
            assertEquals("Intent SELECT count=$count", maxOf(1, pages), queries.count { "FROM study_intents" in it.sql })
            assertEquals("Session SELECT count=$count", maxOf(1, pages), queries.count { "FROM study_sessions" in it.sql })
            for (table in listOf("session_focus_contexts", "session_segments", "focus_events")) {
                val batches = queries.filter { "FROM $table" in it.sql }
                assertEquals("$table count=$count", pages, batches.size)
                assertTrue(batches.all { it.args.size <= 800 })
            }
            assertEquals(if (count == 0) 2 else 5 * pages, queries.size)
            assertTrue("DAO reads must not execute on the UI thread", queries.none { it.onMainThread })
            println("PHASE4A_BOUNDARY facts=$count selects=${queries.size} elapsedMs=$elapsed heapDeltaBytes=${memoryAfter - memoryBefore}")
        }
    }

    // The fixture's 40k durable inserts are not the read-performance gate. Measure them separately.
    @Test fun tenThousandAndOneFactsExposeActualPlanTimeAndMemoryWithoutNPlusOne() = runTest(timeout = 3.minutes) {
        val seedStart = SystemClock.elapsedRealtime()
        seed(10_001)
        println("PHASE4A_SEED facts=10001 elapsedMs=${SystemClock.elapsedRealtime() - seedStart}")
        val runtime = Runtime.getRuntime()
        repeat(3) { repetition ->
            queries.clear()
            val memoryBefore = runtime.totalMemory() - runtime.freeMemory()
            val started = SystemClock.elapsedRealtime()
            val actual = repository.load(TrendsRange.ALL, time)
            val elapsed = SystemClock.elapsedRealtime() - started
            val memoryAfter = runtime.totalMemory() - runtime.freeMemory()
            assertEquals(10_001L, actual.current.start.convertedCount)
            assertEquals(10_001L, actual.current.maintain.trustedSessionCount)
            assertEquals(10_001_000L, actual.current.maintain.effectiveFocusMillis)
            assertEquals(65, queries.size)
            assertTrue("10k DAO reads must not execute on the UI thread", queries.none { it.onMainThread })
            assertTrue(queries.filter { " IN (" in it.sql }.all { it.args.size <= 800 })
            println("PHASE4A_BENCH run=${repetition + 1} facts=10001 selects=${queries.size} elapsedMs=$elapsed heapDeltaBytes=${memoryAfter - memoryBefore}")
            if (repetition == 0) {
                for (query in queries.distinctBy { it.sql.substringBefore("LIMIT") }.take(5)) {
                    val details = mutableListOf<String>()
                    database.openHelper.readableDatabase.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN ${query.sql}", query.args.toTypedArray())).use {
                        while (it.moveToNext()) details.add(it.getString(it.getColumnIndexOrThrow("detail")))
                    }
                    println("PHASE4A_EXPLAIN ${query.sql.trim().replace(Regex("\\s+"), " ")} => ${details.joinToString(" | ")}")
                    assertTrue(details.isNotEmpty())
                }
            }
        }
    }

    private suspend fun seed(count: Int) {
        if (count == 0) return
        database.withTransaction {
            database.learningItemDao().insert(LearningItemEntity("book", "受控趋势数据", LearningItemStatus.IN_PROGRESS, 320, 1, null, "", 1, 1, null))
            repeat(count) { index ->
                val suffix = index.toString().padStart(5, '0')
                val end = 20_000L + index
                val start = end - 1_000
                database.intentDao().insert(StudyIntentEntity("i$suffix", "book", start - 100, null, start, start, IntentOutcome.CONVERTED, null))
                database.sessionDao().insert(StudySessionEntity("s$suffix", "book", "i$suffix", start, start + 120, end, 1, 2, 2, SessionEndType.NORMAL, null, null))
                database.focusDao().insertContext(SessionFocusContextEntity(sessionId = "s$suffix", monitoringStatus = MonitoringCoverage.FULL,
                    monitoringLostAt = null, priorDndInterruptionFilter = null, dndRuleId = null, requestedEndPage = null,
                    closeoutStartedAt = null, lastHeartbeatAt = end, createdAt = start, updatedAt = end))
                database.focusDao().insertSegment(SessionSegmentEntity("seg$suffix", "s$suffix", SessionSegmentType.FOCUS, start, end,
                    null, null, null, relatedSegmentId = null, activeSlot = null))
            }
        }
    }
}
