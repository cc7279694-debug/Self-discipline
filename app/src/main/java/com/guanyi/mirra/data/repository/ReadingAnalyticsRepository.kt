package com.guanyi.mirra.data.repository

import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.model.ReadingSessionProjection
import com.guanyi.mirra.data.local.model.EffectiveReadingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi

data class SevenDaySource(
    val sessions: List<ReadingSessionProjection>,
    val currentNoteCount: Int,
    val previousNoteCount: Int,
)

interface ReadingAnalyticsRepository {
    fun observeEffectiveRecentForItem(
        learningItemId: String,
        fromInclusive: Long,
        toInclusive: Long,
    ): Flow<EffectiveReadingSource>

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
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeEffectiveRecentForItem(
        learningItemId: String,
        fromInclusive: Long,
        toInclusive: Long,
    ): Flow<EffectiveReadingSource> = database.sessionDao()
        .observeEndedEntitiesForItemBetween(learningItemId, fromInclusive, toInclusive)
        .flatMapLatest { sessions ->
            if (sessions.isEmpty()) {
                flowOf(EffectiveReadingSource(emptyList(), emptyMap(), emptyMap()))
            } else {
                // Capture one parent generation. Wait for every batch, never emit partial initialization.
                // Independent Room Flows are not a cross-table transaction snapshot; ended learning
                // facts are immutable under the frozen Closeout boundary. DND metadata may refresh.
                val batches = sessions.map { it.id }.chunked(800)
                val contexts = combine(batches.map { database.focusDao().observeContextsForSessions(it) }) {
                    rows -> rows.flatMap { it }.associateBy { it.sessionId }
                }
                val segments = combine(batches.map { database.focusDao().observeSegmentsForSessions(it) }) {
                    rows -> rows.flatMap { it }.groupBy { it.sessionId }
                }
                combine(contexts, segments) { contextMap, segmentMap ->
                    EffectiveReadingSource(sessions, contextMap, segmentMap)
                }
            }
        }

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
