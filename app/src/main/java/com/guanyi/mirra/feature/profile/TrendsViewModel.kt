package com.guanyi.mirra.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.repository.TrendsRepository
import com.guanyi.mirra.domain.AnalyticsTimeProvider
import com.guanyi.mirra.domain.trends.TrendsRange
import com.guanyi.mirra.domain.trends.TrendsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class TrendsUiState(
    val range: TrendsRange = TrendsRange.SEVEN_DAYS,
    val isLoading: Boolean = true,
    val snapshot: TrendsSnapshot? = null,
    val error: String? = null,
)

class TrendsViewModel(
    private val repository: TrendsRepository,
    private val timeProvider: AnalyticsTimeProvider,
) : ViewModel() {
    private val state = MutableStateFlow(TrendsUiState())
    val uiState: StateFlow<TrendsUiState> = state.asStateFlow()
    private var generation = 0L
    private var loading: Job? = null

    init { load(TrendsRange.SEVEN_DAYS) }

    fun selectRange(range: TrendsRange) {
        if (range != state.value.range) load(range)
    }

    fun retry() { load(state.value.range) }
    fun refresh() { load(state.value.range) }

    private fun load(range: TrendsRange) {
        val request = ++generation
        loading?.cancel()
        val time = timeProvider.snapshot()
        state.value = TrendsUiState(range = range)
        loading = viewModelScope.launch {
            try {
                val snapshot = repository.load(range, time)
                if (request == generation) state.value = TrendsUiState(range, false, snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) state.value = TrendsUiState(
                    range = range, isLoading = false, error = "暂时无法读取趋势",
                )
            }
        }
    }
}
