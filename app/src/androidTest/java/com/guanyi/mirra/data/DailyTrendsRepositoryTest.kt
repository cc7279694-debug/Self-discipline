package com.guanyi.mirra.data

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.repository.DefaultTrendsRepository
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.trends.TrendsRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.Executor
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DailyTrendsRepositoryTest {
    private lateinit var database: MirraDatabase
    private data class Query(val sql: String, val argumentCount: Int, val onMainThread: Boolean)
    private val queries = Collections.synchronizedList(mutableListOf<Query>())
    private val time = AnalyticsTimeContext(Instant.parse("2026-10-07T12:00:00Z"), ZoneId.of("Asia/Shanghai"))

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java)
            .setQueryCallback({ sql, args ->
                val normalized = sql.trim().replace(Regex("\\s+"), " ").lowercase()
                if (normalized.startsWith("select") && listOf("study_intents", "study_sessions", "session_focus_contexts",
                    "session_segments", "focus_events").any { "from $it" in normalized }) {
                    queries.add(Query(sql, args.size, Looper.myLooper() == Looper.getMainLooper()))
                }
            }, Executor { it.run() }).build()
    }

    @After fun tearDown() { database.close() }

    @Test fun dailyDurationsShareTheBoundedSessionScanAcrossEightHundredRowBoundary() = runTest {
        database.withTransaction {
            insertBook()
            repeat(801) { index ->
                val id = "session-${index.toString().padStart(4, '0')}"
                val end = Instant.parse(if (index < 400) "2026-10-01T01:10:00Z" else "2026-10-06T01:10:00Z").toEpochMilli()
                val session = session(id, end - 600_000, end)
                insertIntent(session)
                database.sessionDao().insert(session)
                database.focusDao().insertContext(context(session))
                database.focusDao().insertSegment(SessionSegmentEntity("segment-$id", id, SessionSegmentType.FOCUS,
                    session.startedAt, end, null, null, null, relatedSegmentId = null, activeSlot = null))
            }
        }
        queries.clear()
        val actual = DefaultTrendsRepository(database).load(TrendsRange.SEVEN_DAYS, time)
        assertEquals(7, actual.daily.size)
        assertEquals(240_000_000L, actual.daily.first().normalReading.totalDurationMillis)
        assertEquals(240_600_000L, actual.daily[5].normalReading.totalDurationMillis)
        assertEquals(480_600_000L, actual.normalReading.totalDurationMillis)
        assertEquals(801L, actual.normalReading.sessionCount)
        assertEquals(801L, actual.current.maintain.trustedSessionCount)
        assertEquals(480_600_000L, actual.current.maintain.effectiveFocusMillis)
        assertEquals(2, queries.count { "FROM study_intents" in it.sql })
        assertEquals(2, queries.count { "FROM study_sessions" in it.sql })
        for (table in listOf("session_focus_contexts", "session_segments", "focus_events")) {
            val batches = queries.filter { "FROM $table" in it.sql }
            assertEquals(2, batches.size)
            assertTrue(batches.all { it.argumentCount <= 800 })
        }
        assertEquals(10, queries.size)
        assertTrue(queries.none { it.onMainThread })
    }

    @Test fun persistedLegacyReadingHasDurationWithoutInventedEffectiveFocus() = runTest {
        database.withTransaction {
            insertBook()
            val end = Instant.parse("2026-10-03T01:10:00Z").toEpochMilli()
            val excluded = SessionEndType.entries.filter { it != SessionEndType.NORMAL }.map { type ->
                session("excluded-$type", end - 600_000, end).copy(endType = type)
            }
            for (session in listOf(session("legacy", end - 600_000, end),
                session("invalid", end - 600_000, end).copy(endPage = null)) + excluded) {
                insertIntent(session)
                database.sessionDao().insert(session)
            }
        }
        val actual = DefaultTrendsRepository(database).load(TrendsRange.ALL, time)
        val daily = actual.daily.single()
        assertEquals(LocalDate.of(2026, 10, 3), daily.date)
        assertEquals(600_000L, daily.normalReading.totalDurationMillis)
        assertEquals(1L, daily.normalReading.sessionCount)
        assertEquals(1L, daily.normalReading.dataIssueCount)
        assertEquals(0L, daily.period.maintain.trustedSessionCount)
        assertEquals(6L, daily.period.maintain.unavailableSessionCount)
        assertNull(daily.period.maintain.effectiveFocusMillis)
        assertNull(actual.previousNormalReading)
    }

    private suspend fun insertBook() {
        database.learningItemDao().insert(LearningItemEntity("book", "每日阅读受控数据", LearningItemStatus.IN_PROGRESS,
            100, 1, null, "", 1, 1, null))
    }

    private fun session(id: String, start: Long, end: Long) =
        StudySessionEntity(id, "book", "intent-$id", start, null, end, 1, 2, 2, SessionEndType.NORMAL, null, null)

    private suspend fun insertIntent(session: StudySessionEntity) {
        database.intentDao().insert(StudyIntentEntity(session.intentId, "book", session.startedAt - 1_000,
            null, session.startedAt, session.startedAt, IntentOutcome.CONVERTED, null))
    }

    private fun context(session: StudySessionEntity) = SessionFocusContextEntity(sessionId = session.id,
        monitoringStatus = MonitoringCoverage.FULL, monitoringLostAt = null, priorDndInterruptionFilter = null,
        dndRuleId = null, closeoutState = FocusCloseoutState.COMPLETED, requestedEndPage = null,
        closeoutStartedAt = null, lastHeartbeatAt = session.endedAt!!,
        createdAt = session.startedAt, updatedAt = session.endedAt!!)
}
