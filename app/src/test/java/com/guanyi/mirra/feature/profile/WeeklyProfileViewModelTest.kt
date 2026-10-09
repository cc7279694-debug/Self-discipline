package com.guanyi.mirra.feature.profile

import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.model.ReadingSessionProjection
import com.guanyi.mirra.data.repository.ReadingAnalyticsRepository
import com.guanyi.mirra.data.repository.SevenDaySource
import com.guanyi.mirra.data.repository.TrendsRepository
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.AnalyticsTimeProvider
import com.guanyi.mirra.domain.ReadingAnalyticsService
import com.guanyi.mirra.domain.trends.TrendFraction
import com.guanyi.mirra.domain.trends.TrendsRange
import com.guanyi.mirra.domain.trends.TrendsService
import com.guanyi.mirra.domain.trends.TrendsSnapshot
import com.guanyi.mirra.domain.trends.ReadingDurationTrends
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WeeklyProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val time = AnalyticsTimeContext(now, zone)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun readingFlowUpdateReloadsRecentSevenDayTrendsInsteadOfKeepingOldPercentages() = runTest(dispatcher) {
        val source = MutableStateFlow(SevenDaySource(listOf(currentSession()), 2, 0))
        val trends = ControlledTrends(snapshot(converted = 1, abandoned = 0, conversion = 1.0))
        val vm = viewModel(source, trends)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        try {
            runCurrent()
            assertEquals(1.0, requireNotNull(vm.uiState.value.trends).current.start.conversion.value!!, 0.0)
            trends.next = snapshot(converted = 2, abandoned = 2, conversion = 0.5)
            source.value = SevenDaySource(listOf(currentSession(), secondCurrentSession()), 4, 0)
            runCurrent()
            assertEquals(2, vm.uiState.value.sessionCount)
            assertEquals(2L, requireNotNull(vm.uiState.value.trends).current.start.convertedCount)
            assertEquals(0.5, requireNotNull(vm.uiState.value.trends).current.start.conversion.value!!, 0.0)
            assertEquals(listOf(TrendsRange.SEVEN_DAYS, TrendsRange.SEVEN_DAYS), trends.requests.map { it.first })
            assertEquals(listOf(time, time), trends.requests.map { it.second })
        } finally { collector.cancel(); vm.viewModelScope.cancel() }
    }

    @Test fun failureAfterReadingUpdateClearsOldTrendPercentagesAndKeepsReadingFacts() = runTest(dispatcher) {
        val source = MutableStateFlow(SevenDaySource(listOf(currentSession()), 2, 0))
        val trends = ControlledTrends(snapshot(converted = 1, abandoned = 0, conversion = 1.0))
        val vm = viewModel(source, trends)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        try {
            runCurrent()
            assertNotNull(vm.uiState.value.trends)
            trends.failure = IllegalStateException("private database path / private note")
            source.value = source.value.copy(currentNoteCount = 5)
            runCurrent()
            assertNull(vm.uiState.value.trends)
            assertNotNull(vm.uiState.value.trendsError)
            assertFalse(requireNotNull(vm.uiState.value.trendsError).contains("private"))
            assertNull(vm.uiState.value.error)
            assertEquals(1, vm.uiState.value.sessionCount)
            assertEquals(4L, vm.uiState.value.pagesRead)
            assertEquals(5, vm.uiState.value.noteCount)
            assertEquals("10 分钟", vm.uiState.value.totalDurationText)
        } finally { collector.cancel(); vm.viewModelScope.cancel() }
    }

    @Test fun initialTrendFailureDoesNotFabricateZeroOrHundredPercent() = runTest(dispatcher) {
        val source = MutableStateFlow(SevenDaySource(listOf(currentSession()), 2, 0))
        val trends = ControlledTrends(snapshot(converted = 1, abandoned = 0, conversion = 1.0)).apply {
            failure = IllegalStateException("private database path")
        }
        val vm = viewModel(source, trends)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        try {
            runCurrent()
            assertNull(vm.uiState.value.trends)
            assertNotNull(vm.uiState.value.trendsError)
            assertFalse(vm.uiState.value.isLoading)
            assertNull(vm.uiState.value.error)
            assertEquals(1, vm.uiState.value.sessionCount)
        } finally { collector.cancel(); vm.viewModelScope.cancel() }
    }

    @Test fun explicitRefreshReloadsEvenWhenTheClockSnapshotHasNotChanged() = runTest(dispatcher) {
        val source = MutableStateFlow(SevenDaySource(listOf(currentSession()), 2, 0))
        val trends = ControlledTrends(snapshot(converted = 1, abandoned = 0, conversion = 1.0))
        val vm = viewModel(source, trends)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        try {
            runCurrent()
            assertEquals(1L, requireNotNull(vm.uiState.value.trends).current.start.convertedCount)
            trends.next = snapshot(converted = 3, abandoned = 1, conversion = 0.75)
            vm.refreshTimeContext()
            runCurrent()
            assertEquals(3L, requireNotNull(vm.uiState.value.trends).current.start.convertedCount)
            assertEquals(0.75, requireNotNull(vm.uiState.value.trends).current.start.conversion.value!!, 0.0)
            assertEquals(listOf(time, time), trends.requests.map { it.second })
        } finally { collector.cancel(); vm.viewModelScope.cancel() }
    }

    @Test fun weeklyTrendsPreserveFrozenPhaseTwoPagesNotesAndWholeReadingDuration() = runTest(dispatcher) {
        val current = currentSession()
        val source = MutableStateFlow(SevenDaySource(
            sessions = listOf(current, secondCurrentSession(),
                ReadingSessionProjection("previous", "book", millis("2026-09-28T01:00:00Z"),
                    millis("2026-09-28T01:40:00Z"), 1, 11, SessionEndType.NORMAL, 30),
                current.copy(sessionId = "abnormal", endPage = 99, endType = SessionEndType.ABNORMAL),
                current.copy(sessionId = "future", startedAt = now.toEpochMilli(), endedAt = now.toEpochMilli() + 60_000,
                    endPage = 99),
                current.copy(sessionId = "missing-page", endPage = null)),
            currentNoteCount = 6,
            previousNoteCount = 3,
        ))
        val trends = ControlledTrends(snapshot(converted = 2, abandoned = 2, conversion = 0.5))
        val vm = viewModel(source, trends)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        try {
            runCurrent()
            val state = vm.uiState.value
            assertEquals(2, state.sessionCount)
            assertEquals(9L, state.pagesRead)
            assertEquals(6, state.noteCount)
            assertEquals("30 分钟", state.totalDurationText)
            assertEquals("阅读时间比前 7 天少 10 分钟", state.comparisonText)
            assertFalse(state.isEmpty)
            assertNull(state.error)
            assertNotNull(state.trends)
        } finally { collector.cancel(); vm.viewModelScope.cancel() }
    }

    @Test fun positiveTwentySecondDifferenceIsSlightlyMoreInsteadOfEqual() = runTest(dispatcher) {
        assertSubMinuteComparison(currentSeconds = 20, previousSeconds = 0,
            expected = "阅读时间比前 7 天稍多（不足 1 分钟）")
    }

    @Test fun negativeTenSecondDifferenceIsSlightlyLessInsteadOfEqual() = runTest(dispatcher) {
        assertSubMinuteComparison(currentSeconds = 20, previousSeconds = 30,
            expected = "阅读时间比前 7 天稍少（不足 1 分钟）")
    }

    @Test fun equalTwentySecondDurationsStillReportEqual() = runTest(dispatcher) {
        assertSubMinuteComparison(currentSeconds = 20, previousSeconds = 20,
            expected = "阅读时间与前 7 天相同")
    }

    private suspend fun TestScope.assertSubMinuteComparison(currentSeconds: Long, previousSeconds: Long, expected: String) {
        val currentStart = millis("2026-10-06T01:00:00Z")
        val previousStart = millis("2026-09-28T01:00:00Z")
        val current = ReadingSessionProjection("seconds-current", "book", currentStart,
            currentStart + currentSeconds * 1_000, 1, 2, SessionEndType.NORMAL, 0)
        val previous = if (previousSeconds == 0L) emptyList() else listOf(ReadingSessionProjection(
            "seconds-previous", "book", previousStart, previousStart + previousSeconds * 1_000, 1, 2, SessionEndType.NORMAL, 0))
        val source = MutableStateFlow(SevenDaySource(listOf(current) + previous, 0, 0))
        val base = TrendsService().accumulator(TrendsRange.SEVEN_DAYS, time).finish()
        val overview = base.copy(
            normalReading = ReadingDurationTrends(1, currentSeconds * 1_000),
            previousNormalReading = ReadingDurationTrends(if (previousSeconds == 0L) 0 else 1, previousSeconds * 1_000),
        )
        val vm = viewModel(source, ControlledTrends(overview))
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        try {
            runCurrent()
            assertNull(vm.uiState.value.error)
            assertEquals("不足 1 分钟", vm.uiState.value.totalDurationText)
            assertEquals(expected, vm.uiState.value.comparisonText)
        } finally { collector.cancel(); vm.viewModelScope.cancel() }
    }

    private fun viewModel(source: MutableStateFlow<SevenDaySource>, trends: TrendsRepository): ProfileViewModel {
        val repository = requireNotNull(ReadingAnalyticsRepository::class.java.cast(Proxy.newProxyInstance(
            ReadingAnalyticsRepository::class.java.classLoader, arrayOf(ReadingAnalyticsRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "observeFourteenDaySource" -> {
                    assertEquals(listOf(millis("2026-09-23T16:00:00Z"), millis("2026-09-30T16:00:00Z"),
                        now.toEpochMilli() + 1), args?.toList())
                    source
                }
                else -> error("Unexpected reading repository call: ${method.name}")
            }
        }))
        return ProfileViewModel(repository, ReadingAnalyticsService(),
            AnalyticsTimeProvider(Clock.fixed(now, ZoneId.of("UTC"))) { zone }, trendsRepository = trends)
    }

    private class ControlledTrends(var next: TrendsSnapshot) : TrendsRepository {
        var failure: Throwable? = null
        val requests = mutableListOf<Pair<TrendsRange, AnalyticsTimeContext>>()
        override suspend fun load(range: TrendsRange, time: AnalyticsTimeContext): TrendsSnapshot {
            requests.add(range to time)
            failure?.let { throw it }
            return next
        }
    }

    private fun snapshot(converted: Long, abandoned: Long, conversion: Double): TrendsSnapshot {
        val base = TrendsService().accumulator(TrendsRange.SEVEN_DAYS, time).finish()
        return base.copy(current = base.current.copy(start = base.current.start.copy(convertedCount = converted,
            abandonedCount = abandoned, conversion = TrendFraction(converted, converted + abandoned, conversion))))
    }

    private fun currentSession() = ReadingSessionProjection("current", "book", millis("2026-10-06T01:00:00Z"),
        millis("2026-10-06T01:10:00Z"), 5, 9, SessionEndType.NORMAL, 30)

    private fun secondCurrentSession() = ReadingSessionProjection("second", "book", millis("2026-10-07T01:00:00Z"),
        millis("2026-10-07T01:20:00Z"), 10, 15, SessionEndType.NORMAL, 40)

    private fun millis(value: String): Long = Instant.parse(value).toEpochMilli()
}
