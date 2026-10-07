package com.guanyi.mirra.feature.profile

import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.model.GlobalReadingHistoryRow
import com.guanyi.mirra.data.repository.GlobalHistoryPage
import com.guanyi.mirra.data.repository.GlobalReadingHistoryRepository
import com.guanyi.mirra.data.repository.HistoryCursor
import com.guanyi.mirra.domain.AnalyticsTimeProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GlobalReadingHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<GlobalReadingHistoryViewModel>()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }

    @Test fun emptyHistoryFinishesLoadingWithoutInventingRows() = runTest(dispatcher) {
        val vm = model { _, _ -> GlobalHistoryPage(emptyList(), null, false) }
        runCurrent()
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.records.isEmpty())
        assertFalse(vm.uiState.value.initialError)
        assertFalse(vm.uiState.value.hasMore)
    }

    @Test fun initialReadErrorIsSanitizedAndRetryLoadsHistory() = runTest(dispatcher) {
        var fail = true
        val vm = model { _, _ -> if (fail) error("private note / database path") else page("one") }
        runCurrent()
        assertTrue(vm.uiState.value.initialError)
        assertTrue(vm.uiState.value.records.isEmpty())
        fail = false; vm.retry(); runCurrent()
        assertFalse(vm.uiState.value.initialError)
        assertEquals(listOf("one"), vm.uiState.value.records.map { it.sessionId })
    }

    @Test fun pagingErrorRetainsRowsAndRetriesTheSameCursor() = runTest(dispatcher) {
        val cursor = HistoryCursor(20_000, "one")
        var fail = true
        val requests = mutableListOf<HistoryCursor?>()
        val vm = model { _, requested ->
            requests += requested
            if (requested == null) page("one", cursor)
            else if (fail) error("private path") else page("two")
        }
        runCurrent(); vm.loadMore(); runCurrent()
        assertEquals(listOf("one"), vm.uiState.value.records.map { it.sessionId })
        assertTrue(vm.uiState.value.pageError)
        fail = false; vm.retry(); runCurrent()
        assertEquals(listOf(null, cursor, cursor), requests)
        assertEquals(listOf("one", "two"), vm.uiState.value.records.map { it.sessionId })
        assertFalse(vm.uiState.value.pageError)
    }

    @Test fun repeatedLoadMoreSerializesRequestsAndKeepsTheOriginalTimeSnapshot() = runTest(dispatcher) {
        val pending = CompletableDeferred<GlobalHistoryPage>()
        val clock = MutableHistoryClock(99_000)
        val requests = mutableListOf<Pair<Long, HistoryCursor?>>()
        val cursor = HistoryCursor(20_000, "one")
        val vm = model(clock) { now, requested ->
            requests += now to requested
            if (requested == null) page("one", cursor) else pending.await()
        }
        runCurrent(); clock.now = 199_000
        vm.loadMore(); vm.loadMore(); runCurrent(); vm.loadMore(); runCurrent()
        assertEquals(listOf(99_000L to null, 99_000L to cursor), requests)
        assertTrue(vm.uiState.value.loadingMore)
        pending.complete(page("two")); runCurrent()
        assertEquals(listOf("one", "two"), vm.uiState.value.records.map { it.sessionId })
        assertFalse(vm.uiState.value.loadingMore)
    }

    @Test fun pageAppendDoesNotDuplicateAnAlreadyDisplayedSession() = runTest(dispatcher) {
        val vm = model { _, cursor ->
            if (cursor == null) page("one", HistoryCursor(20_000, "one"))
            else GlobalHistoryPage(listOf(row("one"), row("two")), null, false)
        }
        runCurrent(); vm.loadMore(); runCurrent()
        assertEquals(listOf("one", "two"), vm.uiState.value.records.map { it.sessionId })
        vm.loadMore(); runCurrent()
        assertEquals(2, vm.uiState.value.records.size)
    }

    @Test fun cancellationDoesNotBecomeAnErrorOrLeavePagingLocked() = runTest(dispatcher) {
        var cancel = true
        val vm = model { _, _ -> if (cancel) throw CancellationException("cancel") else page("one") }
        runCurrent()
        assertFalse(vm.uiState.value.initialError)
        assertFalse(vm.uiState.value.isLoading)
        cancel = false; vm.retry(); runCurrent()
        assertEquals(listOf("one"), vm.uiState.value.records.map { it.sessionId })
    }

    private fun model(clock: Clock = MutableHistoryClock(99_000), load: suspend (Long, HistoryCursor?) -> GlobalHistoryPage) =
        GlobalReadingHistoryViewModel(object : GlobalReadingHistoryRepository {
            override suspend fun loadPage(snapshotNow: Long, cursor: HistoryCursor?) = load(snapshotNow, cursor)
        }, AnalyticsTimeProvider(clock) { ZoneOffset.UTC }).also { models += it }

    private fun page(id: String, cursor: HistoryCursor? = null) = GlobalHistoryPage(listOf(row(id)), cursor, cursor != null)
    private fun row(id: String) = GlobalReadingHistoryRow(id, "测试书", 1_000, 20_000, 40, 62,
        SessionEndType.NORMAL, MonitoringCoverage.FULL)
}

private class MutableHistoryClock(var now: Long) : Clock() {
    override fun instant(): Instant = Instant.ofEpochMilli(now)
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
