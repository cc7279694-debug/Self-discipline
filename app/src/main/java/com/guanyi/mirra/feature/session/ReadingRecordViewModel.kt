package com.guanyi.mirra.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.repository.ReadingRecordRepository
import com.guanyi.mirra.domain.ReadingRecordProjection
import com.guanyi.mirra.domain.ReadingRecordService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

sealed interface ReadingRecordUiState {
    data object Loading : ReadingRecordUiState
    data class Ready(val record: ReadingRecordProjection) : ReadingRecordUiState
    data object Missing : ReadingRecordUiState
    data object Error : ReadingRecordUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingRecordViewModel(val sessionId: String, repository: ReadingRecordRepository, service: ReadingRecordService) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    val uiState: StateFlow<ReadingRecordUiState> = refresh.flatMapLatest {
        flow { emitAll(repository.observe(sessionId)) }
            .map<com.guanyi.mirra.data.local.model.ReadingRecordSource?, ReadingRecordUiState> { source ->
                source?.let { ReadingRecordUiState.Ready(service.project(it)) } ?: ReadingRecordUiState.Missing
            }.onStart { emit(ReadingRecordUiState.Loading) }.catch { failure ->
                if (failure is CancellationException) throw failure
                emit(ReadingRecordUiState.Error)
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingRecordUiState.Loading)
    fun retry() { refresh.value++ }
}
