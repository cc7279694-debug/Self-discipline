package com.guanyi.mirra.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.model.GlobalReadingHistoryRow
import com.guanyi.mirra.data.repository.GlobalReadingHistoryRepository
import com.guanyi.mirra.data.repository.HistoryCursor
import com.guanyi.mirra.domain.AnalyticsTimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GlobalReadingHistoryUiState(
    val records: List<GlobalReadingHistoryRow> = emptyList(),
    val isLoading: Boolean = true,
    val loadingMore: Boolean = false,
    val initialError: Boolean = false,
    val pageError: Boolean = false,
    val hasMore: Boolean = false,
)

class GlobalReadingHistoryViewModel(
    private val repository: GlobalReadingHistoryRepository,
    timeProvider: AnalyticsTimeProvider = AnalyticsTimeProvider(),
) : ViewModel() {
    private val snapshot = timeProvider.snapshot()
    val zoneId = snapshot.zoneId
    private val mutableState = MutableStateFlow(GlobalReadingHistoryUiState())
    val uiState: StateFlow<GlobalReadingHistoryUiState> = mutableState.asStateFlow()
    private var nextCursor: HistoryCursor? = null
    private var requestInFlight = false

    init { requestPage(firstPage = true) }

    fun loadMore() {
        val state = mutableState.value
        if (state.hasMore && !state.initialError && !state.pageError) requestPage(firstPage = false)
    }

    fun retry() { requestPage(firstPage = mutableState.value.records.isEmpty()) }

    private fun requestPage(firstPage: Boolean) {
        if (requestInFlight) return
        val cursor = if (firstPage) null else nextCursor ?: return
        requestInFlight = true
        mutableState.update { it.copy(isLoading = firstPage, loadingMore = !firstPage,
            initialError = false, pageError = false) }
        viewModelScope.launch {
            try {
                val page = repository.loadPage(snapshot.now.toEpochMilli(), cursor)
                nextCursor = page.nextCursor
                mutableState.update { previous ->
                    val records = if (firstPage) page.records else previous.records + page.records
                    previous.copy(records = records.distinctBy { it.sessionId }, isLoading = false,
                        loadingMore = false, hasMore = page.hasMore && page.nextCursor != null)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                mutableState.update { it.copy(initialError = firstPage, pageError = !firstPage) }
            } finally {
                requestInFlight = false
                mutableState.update { it.copy(isLoading = false, loadingMore = false) }
            }
        }
    }
}
