package com.guanyi.mirra.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.repository.DefaultGlobalReadingHistoryRepository
import com.guanyi.mirra.data.repository.HistoryCursor
import java.util.Collections
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalReadingHistoryRepositoryTest {
    private lateinit var database: MirraDatabase
    private lateinit var repository: DefaultGlobalReadingHistoryRepository
    private val queries = Collections.synchronizedList(mutableListOf<String>())

    @Before fun before() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                val normalized = sql.trim().replace(Regex("\\s+"), " ").lowercase()
                if (normalized.startsWith("select") && "from study_sessions" in normalized) queries += normalized
            }, Executor { it.run() }).build()
        repository = DefaultGlobalReadingHistoryRepository(database)
    }
    @After fun after() = database.close()

    @Test fun zeroOneFortyNineFiftyAndFiftyOneUseOneSelectAndCorrectLookahead() = runTest {
        insertBook()
        var inserted = 0
        for (count in listOf(0, 1, 49, 50, 51)) {
            database.withTransaction { for (i in inserted until count) insertSession(i) }
            inserted = count; queries.clear()
            val page = repository.loadPage(100_000)
            assertEquals(count.coerceAtMost(50), page.records.size)
            assertEquals(count > 50, page.hasMore)
            assertEquals(1, queries.size)
            if (count == 51) assertEquals(HistoryCursor(10_001, "s-0001"), page.nextCursor)
            else assertNull(page.nextCursor)
        }
    }

    @Test fun identicalEndTimesAcrossPagesNeverOmitOrRepeatSessions() = runTest {
        insertBook()
        database.withTransaction { for (i in 0 until 121) insertSession(i, endedAt = 20_000) }
        val ids = mutableListOf<String>()
        var cursor: HistoryCursor? = null
        var pages = 0
        do {
            queries.clear()
            val page = repository.loadPage(30_000, cursor)
            assertEquals(1, queries.size)
            ids += page.records.map { it.sessionId }; cursor = page.nextCursor; pages++
        } while (page.hasMore)
        assertEquals(3, pages)
        assertEquals(121, ids.size)
        assertEquals(121, ids.toSet().size)
        assertEquals((120 downTo 0).map { "s-${it.toString().padStart(4, '0')}" }, ids)
    }

    @Test fun historyRetainsAbnormalPartialNoneAndLegacyButExcludesActiveFutureAndNotEnded() = runTest {
        insertBook()
        insertSession(0, endType = SessionEndType.ABNORMAL, coverage = MonitoringCoverage.FULL)
        insertSession(1, coverage = MonitoringCoverage.PARTIAL)
        insertSession(2, coverage = MonitoringCoverage.NONE)
        insertSession(3, coverage = null)
        insertSession(4, endedAt = 100_001)
        insertSession(5, activeSlot = 1)
        insertSession(6, endedAt = null)
        queries.clear()
        val page = repository.loadPage(100_000)
        assertEquals(listOf("s-0003", "s-0002", "s-0001", "s-0000"), page.records.map { it.sessionId })
        assertNull(page.records[0].monitoringStatus)
        assertEquals(MonitoringCoverage.NONE, page.records[1].monitoringStatus)
        assertEquals(MonitoringCoverage.PARTIAL, page.records[2].monitoringStatus)
        assertEquals(SessionEndType.ABNORMAL, page.records[3].endType)
        assertTrue(page.records.all { it.learningItemName == "历史书名" })
        assertEquals(1, queries.size)
    }

    @Test fun fixedSnapshotIncludesBoundaryButDoesNotAddLaterSessionsToFollowingPages() = runTest {
        insertBook()
        database.withTransaction { for (i in 0 until 51) insertSession(i, endedAt = 20_000) }
        val first = repository.loadPage(20_000)
        insertSession(100, endedAt = 20_001)
        val last = repository.loadPage(20_000, first.nextCursor)
        assertEquals(listOf("s-0000"), last.records.map { it.sessionId })
        assertFalse(last.hasMore)
        assertEquals(51, (first.records + last.records).size)
    }

    // This budget includes synthetic seeding, as in the existing same-scale trends fixture.
    // Process-wide Java heap endpoints are GC-sensitive; their sampled maximum is not a peak.
    // Debug/device-or-AVD in-memory measurements are not production or arbitrary-scale SLAs.
    @Test fun tenThousandAndOneEndedSessionsUseBoundedKeysetPagesAndExposeActualTimeAndMemory() = runTest(timeout = 3.minutes) {
        val count = 10_001
        val snapshotNow = 20_000L
        val runtime = Runtime.getRuntime()
        fun heapBytes() = runtime.totalMemory() - runtime.freeMemory()
        val seedHeapBefore = heapBytes()
        var heapSampleMax = seedHeapBefore
        val seedStarted = SystemClock.elapsedRealtime()
        database.withTransaction {
            insertBook()
            repeat(count) { insertSession(it) }
        }
        val seedElapsed = SystemClock.elapsedRealtime() - seedStarted
        val seedHeapAfter = heapBytes()
        heapSampleMax = maxOf(heapSampleMax, seedHeapAfter)
        val actualCount = database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM study_sessions WHERE endedAt IS NOT NULL",
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getLong(0) }
        assertEquals(10_001L, actualCount)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val debugBuild = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        Log.i("MirraV1History", "MIRRA_V1_HISTORY stage=seed count=$actualCount elapsedMs=$seedElapsed " +
            "heapBeforeBytes=$seedHeapBefore heapAfterBytes=$seedHeapAfter debugBuild=$debugBuild " +
            "api=${Build.VERSION.SDK_INT} hardware=${Build.HARDWARE} storage=inMemoryRoom " +
            "heapScope=wholeInstrumentedProcess heapSamples=endpoints heapPeakClaim=false arbitraryScaleSlaClaim=false")

        repeat(3) { repetition ->
            queries.clear()
            val before = heapBytes()
            val started = SystemClock.elapsedRealtime()
            val page = repository.loadPage(snapshotNow)
            val elapsed = SystemClock.elapsedRealtime() - started
            val after = heapBytes()
            heapSampleMax = maxOf(heapSampleMax, before, after)
            assertEquals(1, queries.size)
            assertEquals(50, page.records.size)
            assertTrue(page.hasMore)
            page.records.forEachIndexed { offset, row ->
                val expected = 10_000 - offset
                assertEquals("s-${expected.toString().padStart(4, '0')}", row.sessionId)
                assertEquals(10_000L + expected, row.endedAt)
            }
            assertEquals(HistoryCursor(19_951L, "s-9951"), page.nextCursor)
            Log.i("MirraV1History", "MIRRA_V1_HISTORY stage=firstPage repetition=${repetition + 1} " +
                "snapshotNow=$snapshotNow count=$actualCount rows=${page.records.size} selects=${queries.size} " +
                "elapsedMs=$elapsed heapBeforeBytes=$before heapAfterBytes=$after heapDeltaBytes=${after - before}")
        }

        var cursor: HistoryCursor? = null
        var expectedIndex = 10_000
        var traversed = 0
        var pages = 0
        var selects = 0
        val traversalHeapBefore = heapBytes()
        val traversalStarted = SystemClock.elapsedRealtime()
        do {
            queries.clear()
            val before = heapBytes()
            val started = SystemClock.elapsedRealtime()
            val page = repository.loadPage(snapshotNow, cursor)
            val elapsed = SystemClock.elapsedRealtime() - started
            val after = heapBytes()
            heapSampleMax = maxOf(heapSampleMax, before, after)
            pages++
            assertEquals("One SELECT per history page $pages", 1, queries.size)
            selects += queries.size
            assertTrue("History page must be bounded to 50 rows", page.records.size <= 50)
            assertEquals(minOf(50, expectedIndex + 1), page.records.size)
            // Check one row at a time; retain only this page and a scalar expected position.
            page.records.forEach { row ->
                assertTrue("History may not repeat or add a Session", expectedIndex >= 0)
                assertEquals("History may not omit or reorder a Session",
                    "s-${expectedIndex.toString().padStart(4, '0')}", row.sessionId)
                assertEquals(10_000L + expectedIndex, row.endedAt)
                expectedIndex--
                traversed++
            }
            assertEquals(expectedIndex >= 0, page.hasMore)
            val expectedLastIndex = expectedIndex + 1
            val expectedCursor = if (page.hasMore) HistoryCursor(10_000L + expectedLastIndex,
                "s-${expectedLastIndex.toString().padStart(4, '0')}") else null
            assertEquals(expectedCursor, page.nextCursor)
            cursor = page.nextCursor
            Log.i("MirraV1History", "MIRRA_V1_HISTORY stage=page page=$pages snapshotNow=$snapshotNow " +
                "rows=${page.records.size} selects=${queries.size} elapsedMs=$elapsed " +
                "heapBeforeBytes=$before heapAfterBytes=$after heapDeltaBytes=${after - before}")
        } while (page.hasMore)
        val traversalElapsed = SystemClock.elapsedRealtime() - traversalStarted
        val traversalHeapAfter = heapBytes()
        heapSampleMax = maxOf(heapSampleMax, traversalHeapBefore, traversalHeapAfter)
        assertEquals(201, pages)
        assertEquals(201, selects)
        assertEquals(10_001, traversed)
        assertEquals(-1, expectedIndex)
        assertNull(cursor)
        Log.i("MirraV1History", "MIRRA_V1_HISTORY stage=complete count=$actualCount traversed=$traversed " +
            "pages=$pages selects=$selects traversalElapsedMs=$traversalElapsed traversalIncludesChecksAndLogging=true " +
            "heapBeforeBytes=$traversalHeapBefore heapAfterBytes=$traversalHeapAfter " +
            "heapSampleMaxBytes=$heapSampleMax heapScope=wholeInstrumentedProcess heapSamples=endpoints " +
            "heapPeakClaim=false arbitraryScaleSlaClaim=false storage=inMemoryRoom")
    }

    private suspend fun insertBook() = database.learningItemDao().insert(
        LearningItemEntity("book", "历史书名", LearningItemStatus.IN_PROGRESS, 320, 62, null, "", 1, 1, null))

    private suspend fun insertSession(index: Int, endedAt: Long? = 10_000L + index,
        endType: SessionEndType = SessionEndType.NORMAL, coverage: MonitoringCoverage? = null, activeSlot: Int? = null) {
        val id = "s-${index.toString().padStart(4, '0')}"
        database.intentDao().insert(StudyIntentEntity("i-$id", "book", 1, 1, 1, 1, IntentOutcome.CONVERTED, null))
        database.sessionDao().insert(StudySessionEntity(id, "book", "i-$id", 1_000, null, endedAt,
            40, 62, 62, endType, null, activeSlot))
        if (coverage != null) database.focusDao().insertContext(SessionFocusContextEntity(sessionId = id,
            monitoringStatus = coverage, monitoringLostAt = null, priorDndInterruptionFilter = null, dndRuleId = null,
            requestedEndPage = null, closeoutStartedAt = null, lastHeartbeatAt = endedAt ?: 1_000,
            createdAt = 1_000, updatedAt = endedAt ?: 1_000))
    }
}
