package com.guanyi.mirra.data.repository

import com.guanyi.mirra.data.local.MirraDatabase
import androidx.room.withTransaction
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.trends.TrendsRange
import com.guanyi.mirra.domain.trends.TrendsService
import com.guanyi.mirra.domain.trends.TrendsSnapshot
import com.guanyi.mirra.domain.trends.StartTrendSource
import com.guanyi.mirra.domain.trends.SessionTrendSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface TrendsRepository {
    suspend fun load(range: TrendsRange, time: AnalyticsTimeContext): TrendsSnapshot
}

class DefaultTrendsRepository(private val database: MirraDatabase,
    private val service: TrendsService = TrendsService()) : TrendsRepository {
    override suspend fun load(range: TrendsRange, time: AnalyticsTimeContext): TrendsSnapshot = withContext(Dispatchers.IO) {
        val windows = service.windows(range, time)
        val from = windows.previous?.startInclusive ?: windows.current.startInclusive
        val until = time.now.toEpochMilli()
        val accumulator = service.accumulator(range, time)
        // Both cohorts and their facts come from one database snapshot, not independently emitted flows.
        database.withTransaction {
            var intentTime: Long? = null
            var intentId: String? = null
            do {
                currentCoroutineContext().ensureActive()
                val lookahead = database.intentDao().loadTrendPage(from, until, intentTime, intentId)
                val page = lookahead.take(FACT_BATCH_SIZE)
                accumulator.addStartPage(page.map { StartTrendSource(it.intent, listOfNotNull(it.session)) })
                val last = page.lastOrNull()
                intentTime = last?.intent?.createdAt
                intentId = last?.intent?.id
            } while (lookahead.size > FACT_BATCH_SIZE)

            var sessionTime: Long? = null
            var sessionId: String? = null
            do {
                currentCoroutineContext().ensureActive()
                val lookahead = database.sessionDao().loadTrendPage(from, until, sessionTime, sessionId)
                val page = lookahead.take(FACT_BATCH_SIZE)
                if (page.isNotEmpty()) {
                    val ids = page.map { it.id }
                    val contexts = database.focusDao().getContextsForSessions(ids).associateBy { it.sessionId }
                    val segments = database.focusDao().getSegmentsForSessions(ids).groupBy { it.sessionId }
                    val events = database.focusDao().getEventsForSessions(ids).groupBy { it.sessionId }
                    accumulator.addSessionPage(page.map { SessionTrendSource(it, contexts[it.id],
                        segments[it.id].orEmpty(), events[it.id].orEmpty()) })
                }
                val last = page.lastOrNull()
                sessionTime = last?.endedAt
                sessionId = last?.id
            } while (lookahead.size > FACT_BATCH_SIZE)
        }
        accumulator.finish()
    }

    private companion object { const val FACT_BATCH_SIZE = 800 }
}
