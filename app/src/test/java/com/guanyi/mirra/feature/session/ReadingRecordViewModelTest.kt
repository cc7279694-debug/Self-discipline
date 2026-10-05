package com.guanyi.mirra.feature.session

import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.local.model.ReadingRecordSource
import com.guanyi.mirra.data.repository.ReadingRecordRepository
import com.guanyi.mirra.domain.ReadingRecordService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingRecordViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()
    @Test fun missingRecordDoesNotBecomeZeroResult() = runTest(dispatcher) {
        val vm = vm { flowOf(null) }
        try { backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }; runCurrent()
            assertEquals(ReadingRecordUiState.Missing, vm.uiState.value)
        } finally { vm.viewModelScope.cancel() }
    }
    @Test fun recordAndMetadataUpdatesUseSameProjectionService() = runTest(dispatcher) {
        val source = MutableStateFlow<ReadingRecordSource?>(source())
        val vm = vm { source }
        try { backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }; runCurrent()
            assertEquals(ReadingRecordService().project(source.value!!), (vm.uiState.value as ReadingRecordUiState.Ready).record)
            source.value = source.value!!.copy(noteCount = 3); runCurrent()
            assertEquals(3, (vm.uiState.value as ReadingRecordUiState.Ready).record.noteCount)
            source.value = null; runCurrent(); assertEquals(ReadingRecordUiState.Missing, vm.uiState.value)
        } finally { vm.viewModelScope.cancel() }
    }
    @Test fun readErrorIsSanitizedAndCanRetry() = runTest(dispatcher) {
        var failing = true
        val vm = vm { flow { if (failing) error("private note / SQL path") else emit(source()) } }
        try { backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }; runCurrent()
            assertEquals(ReadingRecordUiState.Error, vm.uiState.value)
            failing = false; vm.retry(); runCurrent()
            assertTrue(vm.uiState.value is ReadingRecordUiState.Ready)
        } finally { vm.viewModelScope.cancel() }
    }
    @Test fun loadingRemainsLoadingUntilFactsArrive() = runTest(dispatcher) {
        val events = MutableSharedFlow<ReadingRecordSource?>()
        val vm = vm { events }
        try { backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }; runCurrent()
            assertEquals(ReadingRecordUiState.Loading, vm.uiState.value)
            events.emit(source()); runCurrent(); assertTrue(vm.uiState.value is ReadingRecordUiState.Ready)
        } finally { vm.viewModelScope.cancel() }
    }
    private fun vm(flow: () -> Flow<ReadingRecordSource?>) = ReadingRecordViewModel("s", object : ReadingRecordRepository {
        override fun observe(sessionId: String) = flow()
    }, ReadingRecordService())
    private fun source() = ReadingRecordSource(StudySessionEntity("s", "b", "i", 0, null, 60_000,
        40, 58, 58, SessionEndType.NORMAL, "old summary", null), null, emptyList(), emptyList(), 2)
}
