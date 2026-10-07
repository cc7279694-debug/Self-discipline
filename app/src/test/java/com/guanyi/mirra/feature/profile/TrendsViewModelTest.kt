package com.guanyi.mirra.feature.profile

import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.repository.TrendsRepository
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.AnalyticsTimeProvider
import com.guanyi.mirra.domain.trends.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TrendsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-10-07T04:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val time = AnalyticsTimeContext(now, zone)

    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun firstLoadUsesSevenDaysAndOneTimeSnapshot() = runTest(dispatcher) {
        var snapshots = 0
        var requests = 0
        val repository = object : TrendsRepository {
            override suspend fun load(range: TrendsRange, time: AnalyticsTimeContext): TrendsSnapshot {
                requests++
                assertEquals(TrendsRange.SEVEN_DAYS, range)
                assertEquals(this@TrendsViewModelTest.time, time)
                return testSnapshot(range, time, converted = 9)
            }
        }
        val provider = AnalyticsTimeProvider(Clock.fixed(now, ZoneId.of("UTC"))) { snapshots++; zone }
        val vm = TrendsViewModel(repository, provider)
        try {
            assertTrue(vm.uiState.value.isLoading)
            runCurrent()
            assertEquals(9L, vm.uiState.value.snapshot!!.current.start.convertedCount)
            assertFalse(vm.uiState.value.isLoading)
            assertEquals(1, snapshots)
            assertEquals(1, requests)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun rangeChangeReplacesFactsAndRefreshTakesNewSnapshot() = runTest(dispatcher) {
        var snapshots = 0
        val requested = mutableListOf<TrendsRange>()
        val repository = object : TrendsRepository {
            override suspend fun load(range: TrendsRange, time: AnalyticsTimeContext): TrendsSnapshot {
                requested += range
                return testSnapshot(range, time, converted = range.days?.toLong() ?: 999)
            }
        }
        val provider = AnalyticsTimeProvider(Clock.fixed(now, ZoneId.of("UTC"))) { snapshots++; zone }
        val vm = TrendsViewModel(repository, provider)
        try {
            runCurrent()
            for (range in listOf(TrendsRange.THIRTY_DAYS, TrendsRange.NINETY_DAYS, TrendsRange.ALL)) {
                vm.selectRange(range)
                assertTrue(vm.uiState.value.isLoading)
                assertNull(vm.uiState.value.snapshot)
                runCurrent()
                assertEquals(range, vm.uiState.value.snapshot!!.range)
            }
            vm.selectRange(TrendsRange.ALL)
            runCurrent()
            assertEquals(4, requested.size)
            vm.refresh()
            runCurrent()
            assertEquals(5, requested.size)
            assertEquals(5, snapshots)
            assertEquals(999L, vm.uiState.value.snapshot!!.current.start.convertedCount)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun loadFailureIsSanitizedAndRetryKeepsSelectedRange() = runTest(dispatcher) {
        var failing = true
        val repository = object : TrendsRepository {
            override suspend fun load(range: TrendsRange, time: AnalyticsTimeContext): TrendsSnapshot {
                if (failing) error("private-note / SQL-path / credential")
                return testSnapshot(range, time, converted = 30)
            }
        }
        val vm = TrendsViewModel(repository, provider())
        try {
            vm.selectRange(TrendsRange.THIRTY_DAYS)
            runCurrent()
            assertEquals(TrendsRange.THIRTY_DAYS, vm.uiState.value.range)
            assertEquals("暂时无法读取趋势", vm.uiState.value.error)
            assertFalse(vm.uiState.value.isLoading)
            assertNull(vm.uiState.value.snapshot)
            failing = false
            vm.retry()
            runCurrent()
            assertNull(vm.uiState.value.error)
            assertEquals(30L, vm.uiState.value.snapshot!!.current.start.convertedCount)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun lateCancelledResponseCannotReplaceNewRange() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<Unit>()
        val repository = object : TrendsRepository {
            override suspend fun load(range: TrendsRange, time: AnalyticsTimeContext): TrendsSnapshot {
                if (range == TrendsRange.SEVEN_DAYS) withContext(NonCancellable) { oldResponse.await() }
                return testSnapshot(range, time, converted = if (range == TrendsRange.SEVEN_DAYS) 7 else 30)
            }
        }
        val vm = TrendsViewModel(repository, provider())
        try {
            runCurrent()
            vm.selectRange(TrendsRange.THIRTY_DAYS)
            runCurrent()
            assertEquals(30L, vm.uiState.value.snapshot!!.current.start.convertedCount)
            oldResponse.complete(Unit)
            runCurrent()
            assertEquals(TrendsRange.THIRTY_DAYS, vm.uiState.value.range)
            assertEquals(30L, vm.uiState.value.snapshot!!.current.start.convertedCount)
            assertNull(vm.uiState.value.error)
        } finally { oldResponse.complete(Unit); vm.viewModelScope.cancel() }
    }

    @Test fun lateFailureCannotReplaceNewRangeWithError() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<Unit>()
        val repository = object : TrendsRepository {
            override suspend fun load(range: TrendsRange, time: AnalyticsTimeContext): TrendsSnapshot {
                if (range == TrendsRange.SEVEN_DAYS) {
                    withContext(NonCancellable) { oldResponse.await() }
                    error("late private failure")
                }
                return testSnapshot(range, time, converted = 90)
            }
        }
        val vm = TrendsViewModel(repository, provider())
        try {
            runCurrent()
            vm.selectRange(TrendsRange.NINETY_DAYS)
            runCurrent()
            oldResponse.complete(Unit)
            runCurrent()
            assertEquals(90L, vm.uiState.value.snapshot!!.current.start.convertedCount)
            assertNull(vm.uiState.value.error)
            assertFalse(vm.uiState.value.isLoading)
        } finally { oldResponse.complete(Unit); vm.viewModelScope.cancel() }
    }

    @Test fun loadingWaitsForFactsInsteadOfFabricatingZero() = runTest(dispatcher) {
        val response = CompletableDeferred<TrendsSnapshot>()
        val repository = object : TrendsRepository {
            override suspend fun load(range: TrendsRange, time: AnalyticsTimeContext) = response.await()
        }
        val vm = TrendsViewModel(repository, provider())
        try {
            runCurrent()
            assertTrue(vm.uiState.value.isLoading)
            assertNull(vm.uiState.value.snapshot)
            response.complete(testSnapshot(TrendsRange.SEVEN_DAYS, time, converted = 1))
            runCurrent()
            assertEquals(1L, vm.uiState.value.snapshot!!.current.start.convertedCount)
        } finally { response.cancel(); vm.viewModelScope.cancel() }
    }

    private fun provider() = AnalyticsTimeProvider(Clock.fixed(now, ZoneId.of("UTC"))) { zone }
}

private fun testSnapshot(range: TrendsRange, time: AnalyticsTimeContext, converted: Long): TrendsSnapshot {
    val period = TrendsPeriod(
        StartTrends(converted, 0, 0, 0, TrendFraction(converted, converted, 1.0), converted, 60_000.0, 0, 0, converted, 0, null),
        MaintainTrends(0, 0, 0, null, null, 0, null, false),
        RecoverTrends(0, 0, 0, 0, 0, 0, TrendFraction(0, 0, null), null),
    )
    return TrendsSnapshot(range, time, TrendsWindows(TrendsWindow(null, time.now.toEpochMilli()), null), period, null, null)
}
