package com.guanyi.mirra.data.repository

import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.model.ReadingSessionProjection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class SevenDaySource(
    val sessions: List<ReadingSessionProjection>,
    val currentNoteCount: Int,
    val previousNoteCount: Int,
)

interface ReadingAnalyticsRepository {
    fun observeHistory(learningItemId: String): Flow<List<ReadingSessionProjection>>

    fun observeRecentForItem(
        learningItemId: String,
        fromInclusive: Long,
        toInclusive: Long,
    ): Flow<List<ReadingSessionProjection>>

    fun observeFourteenDaySource(
        fromInclusive: Long,
        currentPeriodStart: Long,
        toExclusive: Long,
    ): Flow<SevenDaySource>
}

class DefaultReadingAnalyticsRepository(
    private val database: MirraDatabase,
) : ReadingAnalyticsRepository {
    override fun observeHistory(learningItemId: String): Flow<List<ReadingSessionProjection>> =
        database.sessionDao().observeHistory(learningItemId)

    override fun observeRecentForItem(
        learningItemId: String,
        fromInclusive: Long,
        toInclusive: Long,
    ): Flow<List<ReadingSessionProjection>> =
        database.sessionDao().observeEndedForItemBetween(learningItemId, fromInclusive, toInclusive)

    override fun observeFourteenDaySource(
        fromInclusive: Long,
        currentPeriodStart: Long,
        toExclusive: Long,
    ): Flow<SevenDaySource> = combine(
        database.sessionDao().observeEndedBetween(fromInclusive, toExclusive),
        database.noteDao().observeCreatedCountBetween(currentPeriodStart, toExclusive),
        database.noteDao().observeCreatedCountBetween(fromInclusive, currentPeriodStart),
    ) { sessions, currentNotes, previousNotes ->
        SevenDaySource(sessions, currentNotes, previousNotes)
    }
}
