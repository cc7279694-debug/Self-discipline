package com.guanyi.mirra.data.repository

import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.model.GlobalReadingHistoryRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HistoryCursor(val endedAt: Long, val sessionId: String)
data class GlobalHistoryPage(val records: List<GlobalReadingHistoryRow>, val nextCursor: HistoryCursor?, val hasMore: Boolean)

interface GlobalReadingHistoryRepository {
    suspend fun loadPage(snapshotNow: Long, cursor: HistoryCursor? = null): GlobalHistoryPage
}

class DefaultGlobalReadingHistoryRepository(private val database: MirraDatabase) : GlobalReadingHistoryRepository {
    override suspend fun loadPage(snapshotNow: Long, cursor: HistoryCursor?): GlobalHistoryPage = withContext(Dispatchers.IO) {
        val lookahead = database.sessionDao().loadGlobalHistoryRows(snapshotNow, cursor?.endedAt, cursor?.sessionId)
        val records = lookahead.take(PAGE_SIZE)
        val hasMore = lookahead.size > PAGE_SIZE
        GlobalHistoryPage(records, records.lastOrNull()?.takeIf { hasMore }?.let {
            HistoryCursor(it.endedAt, it.sessionId)
        }, hasMore)
    }

    private companion object { const val PAGE_SIZE = 50 }
}
