package com.guanyi.mirra.data

import android.content.Context
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
