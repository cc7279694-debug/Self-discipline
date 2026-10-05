package com.guanyi.mirra.data.repository

import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.model.ReadingRecordSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

interface ReadingRecordRepository {
    fun observe(sessionId: String): Flow<ReadingRecordSource?>
}

class DefaultReadingRecordRepository(private val database: MirraDatabase) : ReadingRecordRepository {
    override fun observe(sessionId: String): Flow<ReadingRecordSource?> = combine(
        database.sessionDao().observe(sessionId),
        database.focusDao().observeContext(sessionId),
        database.focusDao().observeSegments(sessionId),
        database.focusDao().observeRiskSnapshots(sessionId),
        database.noteDao().observeNonBlankCountForSession(sessionId),
    ) { session, context, segments, snapshots, count ->
        session?.let { ReadingRecordSource(it, context, segments, snapshots, count) }
    }.distinctUntilChanged()
}
