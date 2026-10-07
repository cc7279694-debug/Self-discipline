package com.guanyi.mirra.data.repository

import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.insights.ReadingPaceInsightResult
import com.guanyi.mirra.domain.insights.ReadingPaceInsightService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

interface ReadingInsightRepository {
    fun observeForItem(itemId: String, time: AnalyticsTimeContext): Flow<ReadingPaceInsightResult>
}

/** Independent read-only projection over the frozen batched effective source. */
class DefaultReadingInsightRepository(
    private val readingAnalytics: ReadingAnalyticsRepository,
    private val service: ReadingPaceInsightService = ReadingPaceInsightService(),
) : ReadingInsightRepository {
    override fun observeForItem(itemId: String, time: AnalyticsTimeContext): Flow<ReadingPaceInsightResult> {
        val fromInclusive = time.now.atZone(time.zoneId).toLocalDate().minusDays(89)
            .atStartOfDay(time.zoneId).toInstant().toEpochMilli()
        return readingAnalytics.observeEffectiveRecentForItem(itemId, fromInclusive, time.now.toEpochMilli())
            .map { source -> service.analyze(itemId, source, time) }
            .flowOn(Dispatchers.Default)
    }
}
