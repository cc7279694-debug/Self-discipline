package com.guanyi.mirra.feature.knowledge

import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.local.model.*
import com.guanyi.mirra.data.repository.*
import com.guanyi.mirra.domain.*
import com.guanyi.mirra.domain.insights.*
import java.lang.reflect.Proxy
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Real detail VM; only storage boundaries are controlled to exercise independent emissions. */
@OptIn(ExperimentalCoroutinesApi::class)
class LearningItemInsightTest {
    private val dispatcher = StandardTestDispatcher()
    private val time = AnalyticsTimeContext(Instant.parse("2026-10-07T12:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val book = MutableStateFlow<LearningItemEntity?>(LearningItemEntity("book", "历史阅读", LearningItemStatus.IN_PROGRESS,
        320, 50, null, "", 0, 0, null))
    private val history = listOf(ReadingSessionProjection("s", "book", time.now.minusSeconds(1800).toEpochMilli(),
        time.now.toEpochMilli(), 1, 11, SessionEndType.NORMAL, 0))
    private var baseGate: CompletableDeferred<Unit>? = null
    private val calls = mutableListOf<AnalyticsTimeContext>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private inline fun <reified T> proxy(noinline block: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> block(method.name) } as T
    private fun vm(read: (Int) -> Flow<ReadingPaceInsightResult>): LearningItemDetailViewModel {
        val insightRepository = object : ReadingInsightRepository {
            override fun observeForItem(itemId: String, time: AnalyticsTimeContext): Flow<ReadingPaceInsightResult> {
                assertEquals("book", itemId)
                calls += time
                return read(calls.size)
            }
        }
        val analytics = proxy<ReadingAnalyticsRepository> { method -> when (method) {
            "observeHistory", "observeRecentForItem" -> flow { baseGate?.await(); emit(history) }
            "observeEffectiveRecentForItem" -> flow { baseGate?.await(); emit(EffectiveReadingSource(emptyList(), emptyMap(), emptyMap())) }
            else -> error(method)
        } }
        return LearningItemDetailViewModel("book", proxy<LearningItemRepository> { if (it == "observe") book else error(it) },
            proxy<StudyWorkflowRepository> { if (it in setOf("observeLatestSummaryForItem", "observeActiveIntent", "observeActiveSession")) flowOf(null) else error(it) }, analytics,
            ReadingAnalyticsService(), CompletionPredictionService(), AnalyticsTimeProvider(Clock.fixed(time.now, time.zoneId)) { time.zoneId },
            EffectiveReadingService(), insightRepository)
    }
    private suspend fun TestScope.check(read: (Int) -> Flow<ReadingPaceInsightResult>, block: suspend (LearningItemDetailViewModel) -> Unit) {
        val vm = vm(read)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        try { runCurrent(); block(vm) } finally { vm.viewModelScope.cancel() }
    }
    @Test fun slowerAndFasterReachExistingDetailWithoutChangingHistory() = runTest(dispatcher) {
        val results = MutableStateFlow(result(ReadingPaceInsightDirection.SLOWER))
        check({ results }) { vm ->
            assertEquals(ReadingPaceInsightDirection.SLOWER, vm.uiState.value.insight?.direction)
            results.value = result(ReadingPaceInsightDirection.FASTER); runCurrent()
            assertEquals(ReadingPaceInsightDirection.FASTER, vm.uiState.value.insight?.direction)
            assertEquals("s", vm.uiState.value.history.single().id)
            assertNotNull(vm.uiState.value.analytics)
        }
    }
    @Test fun unavailableRemovesPreviousInsight() = runTest(dispatcher) {
        val results = MutableStateFlow(result())
        check({ results }) { vm ->
            assertNotNull(vm.uiState.value.insight)
            results.value = ReadingPaceInsightResult(null, ReadingPaceInsightUnavailableReason.NO_NOTABLE_CHANGE); runCurrent()
            assertNull(vm.uiState.value.insight)
            assertEquals("历史阅读", vm.uiState.value.item?.name)
        }
    }
    @Test fun slowInsightDoesNotDelayBaseDetail() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        check({ flow { gate.await(); emit(result()) } }) { vm ->
            assertEquals("历史阅读", vm.uiState.value.item?.name)
            assertFalse(vm.uiState.value.isAnalyticsLoading)
            assertEquals(1, vm.uiState.value.history.size)
            assertNull(vm.uiState.value.insight)
            gate.complete(Unit); runCurrent(); assertNotNull(vm.uiState.value.insight)
        }
    }
    @Test fun asynchronousFailureHidesInsightAndLeavesAnalyticsAvailable() = runTest(dispatcher) {
        check({ flow { emit(result()); throw IllegalStateException("private failure") } }) { vm ->
            assertNull(vm.uiState.value.insight)
            assertNull(vm.uiState.value.analyticsError)
            assertEquals(1, vm.uiState.value.history.size)
            assertNotNull(vm.uiState.value.analytics)
        }
    }
    @Test fun synchronousRepositoryFailureIsIsolatedAndSameClockRetryRecovers() = runTest(dispatcher) {
        check({ attempt -> if (attempt == 1) throw IllegalStateException("provider failure") else flowOf(result()) }) { vm ->
            assertNull(vm.uiState.value.analyticsError)
            assertNotNull(vm.uiState.value.analytics)
            vm.refreshTimeContext(); runCurrent()
            assertNotNull(vm.uiState.value.insight)
            assertEquals(listOf(time, time), calls)
        }
    }
    @Test fun domainMappingExceptionDoesNotFailBaseDetail() = runTest(dispatcher) {
        check({ flowOf(Unit).map { throw ArithmeticException("domain calculation") } }) { vm ->
            assertNull(vm.uiState.value.insight)
            assertNull(vm.uiState.value.analyticsError)
            assertEquals("历史阅读", vm.uiState.value.item?.name)
            assertEquals(1, vm.uiState.value.history.size)
        }
    }
    @Test fun sameClockRefreshClearsOldInsightWhileNewReadWaits() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        check({ attempt -> if (attempt == 1) flowOf(result()) else flow { gate.await(); emit(result(ReadingPaceInsightDirection.FASTER)) } }) { vm ->
            assertNotNull(vm.uiState.value.insight)
            vm.refreshTimeContext(); runCurrent()
            assertNull(vm.uiState.value.insight)
            assertEquals(1, vm.uiState.value.history.size)
            gate.complete(Unit); runCurrent()
            assertEquals(ReadingPaceInsightDirection.FASTER, vm.uiState.value.insight?.direction)
            assertEquals(listOf(time, time), calls)
        }
    }
    @Test fun newInsightCannotAttachToOldBaseGeneration() = runTest(dispatcher) {
        check({ attempt -> flowOf(result(if (attempt == 1) ReadingPaceInsightDirection.SLOWER else ReadingPaceInsightDirection.FASTER)) }) { vm ->
            assertEquals(ReadingPaceInsightDirection.SLOWER, vm.uiState.value.insight?.direction)
            baseGate = CompletableDeferred()
            vm.refreshTimeContext(); runCurrent()
            assertNull(vm.uiState.value.insight)
            assertEquals(1, vm.uiState.value.history.size)
            baseGate!!.complete(Unit); runCurrent()
            assertEquals(ReadingPaceInsightDirection.FASTER, vm.uiState.value.insight?.direction)
        }
    }
    @Test fun oldReadIsCancelledAndCannotReappearAfterRefresh() = runTest(dispatcher) {
        val oldGate = CompletableDeferred<Unit>()
        var cancelled = false
        check({ attempt -> if (attempt == 1) flow {
            try { oldGate.await(); emit(result()) } finally { cancelled = true }
        } else flowOf(result(ReadingPaceInsightDirection.FASTER)) }) { vm ->
            vm.refreshTimeContext(); runCurrent()
            assertTrue(cancelled)
            oldGate.complete(Unit); runCurrent()
            assertEquals(ReadingPaceInsightDirection.FASTER, vm.uiState.value.insight?.direction)
        }
    }
    @Test fun pausedAndCompletedKeepHistoricalInsight() = runTest(dispatcher) {
        check({ flowOf(result()) }) { vm ->
            for (status in listOf(LearningItemStatus.PAUSED, LearningItemStatus.COMPLETED)) {
                book.value = book.value!!.copy(status = status); runCurrent()
                assertNotNull(vm.uiState.value.insight)
                assertEquals(status, vm.uiState.value.item?.status)
            }
        }
    }
    private fun result(direction: ReadingPaceInsightDirection = ReadingPaceInsightDirection.SLOWER) = ReadingPaceInsightResult(
        ReadingPaceInsight(direction, if (direction == ReadingPaceInsightDirection.SLOWER) 0.7 else 1.35,
            ReadingPaceWindowEvidence(listOf("r1", "r2", "r3"), 21, 2_160_000, 35.0),
            ReadingPaceWindowEvidence(listOf("b1", "b2", "b3", "b4", "b5"), 50, 3_600_000, 50.0),
            if (direction == ReadingPaceInsightDirection.SLOWER) 30 else 35, false), null)
}
