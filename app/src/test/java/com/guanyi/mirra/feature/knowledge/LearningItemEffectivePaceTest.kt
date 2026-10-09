package com.guanyi.mirra.feature.knowledge

import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.local.model.*
import com.guanyi.mirra.data.repository.*
import com.guanyi.mirra.domain.*
import java.lang.reflect.Proxy
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class LearningItemEffectivePaceTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-10-05T12:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val item = MutableStateFlow<LearningItemEntity?>(LearningItemEntity("book", "节奏测试", LearningItemStatus.IN_PROGRESS,
        320, 50, null, "", 0, 0, null))
    private val normal = MutableStateFlow(listOf(1, 3, 7, 8, 10).mapIndexed { i, days -> projection("s$i", days) })
    private val effective = MutableStateFlow(effectiveSource())
    private var failEffective = false
    private val bounds = mutableListOf<Pair<String, List<Any?>>>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private inline fun <reified T> proxy(noinline block: (String, List<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> block(method.name, args?.toList().orEmpty()) } as T
    private fun vm(time: AnalyticsTimeProvider = AnalyticsTimeProvider(Clock.fixed(now, zone)) { zone }): LearningItemDetailViewModel {
        val analytics = proxy<ReadingAnalyticsRepository> { name, args -> when (name) {
            "observeRecentForItem" -> { bounds += name to args; normal }
            "observeHistory" -> normal
            "observeEffectiveRecentForItem" -> { bounds += name to args; flow<EffectiveReadingSource> {
                if (failEffective) throw IllegalStateException("private storage detail")
                emitAll(effective)
            } }
            else -> error(name)
        } }
        return LearningItemDetailViewModel("book", proxy<LearningItemRepository> { name, _ ->
            if (name == "observe") item else error(name)
        }, proxy<StudyWorkflowRepository> { name, _ ->
            if (name in setOf("observeLatestSummaryForItem", "observeActiveIntent", "observeActiveSession")) flowOf(null) else error(name)
        }, analytics, ReadingAnalyticsService(), CompletionPredictionService(), time, EffectiveReadingService())
    }
    private suspend fun TestScope.withVm(vm: LearningItemDetailViewModel = vm(), check: suspend (LearningItemDetailViewModel) -> Unit) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        try { runCurrent(); check(vm) } finally { vm.viewModelScope.cancel() }
    }
    @Test fun availableEffectiveWindowOverridesOverallEvenWithoutNaturalDate() = runTest(dispatcher) {
        normal.value = normal.value.take(3)
        withVm { vm ->
            val ui = vm.uiState.value.analytics!!
            assertEquals("有效阅读速度约 30 页/小时", ui.speedText)
            assertEquals("根据最近 7 天 3 次完整阅读", ui.speedBasisText)
            assertEquals("预计还需约 9 小时 有效阅读", ui.remainingTimeText)
            assertNull(ui.completionDateText)
            assertFalse(ui.speedText!!.contains("最近约"))
        }
    }
    @Test fun zeroEffectiveSpeedStillUsesEffectiveWindowNotPositiveOverall() = runTest(dispatcher) {
        effective.value = effectiveSource(pages = 0)
        withVm { vm ->
            assertEquals("有效阅读速度约 0 页/小时", vm.uiState.value.analytics!!.speedText)
            assertNull(vm.uiState.value.analytics!!.remainingTimeText)
        }
    }
    @Test fun pausedCompletedAndLastPageKeepEffectiveFactsWithoutFutureTime() = runTest(dispatcher) {
        withVm { vm ->
            for (status in listOf(LearningItemStatus.PAUSED, LearningItemStatus.COMPLETED)) {
                item.value = item.value!!.copy(status = status); runCurrent()
                assertEquals("有效阅读速度约 30 页/小时", vm.uiState.value.analytics!!.speedText)
                assertNull(vm.uiState.value.analytics!!.remainingTimeText)
                assertNull(vm.uiState.value.analytics!!.completionDateText)
            }
            item.value = item.value!!.copy(status = LearningItemStatus.IN_PROGRESS, currentPage = 320); runCurrent()
            assertEquals("有效阅读速度约 30 页/小时", vm.uiState.value.analytics!!.speedText)
            assertNull(vm.uiState.value.analytics!!.remainingTimeText)
        }
    }
    @Test fun insufficientEffectiveWindowKeepsExistingPhaseTwoPace() = runTest(dispatcher) {
        effective.value = EffectiveReadingSource(emptyList(), emptyMap(), emptyMap())
        withVm { vm ->
            assertEquals("最近约 20 页/小时", vm.uiState.value.analytics!!.speedText)
            assertEquals("预计剩余阅读时间约 13 小时 30 分钟", vm.uiState.value.analytics!!.remainingTimeText)
        }
    }
    @Test fun naturalDateAndConfidenceAreIdenticalAfterEffectiveSourceChanges() = runTest(dispatcher) {
        effective.value = EffectiveReadingSource(emptyList(), emptyMap(), emptyMap())
        withVm { vm ->
            val old = vm.uiState.value.analytics!!
            assertNotNull(old.completionDateText)
            effective.value = effectiveSource(); runCurrent()
            val next = vm.uiState.value.analytics!!
            assertEquals("有效阅读速度约 30 页/小时", next.speedText)
            assertEquals(old.completionDateText, next.completionDateText)
            assertEquals(old.confidence, next.confidence)
        }
    }
    @Test fun effectiveQueryFailureIsExplicitAndSameClockRetryCanRecover() = runTest(dispatcher) {
        failEffective = true
        withVm { vm ->
            assertEquals("暂时无法读取有效阅读节奏", vm.uiState.value.analyticsError)
            assertNull(vm.uiState.value.analytics)
            assertFalse(vm.uiState.value.isAnalyticsLoading)
            failEffective = false; vm.refreshTimeContext(); runCurrent()
            assertNull(vm.uiState.value.analyticsError)
            assertEquals("有效阅读速度约 30 页/小时", vm.uiState.value.analytics!!.speedText)
        }
    }
    @Test fun oldAndEffectiveQueriesShareOneNowZoneAndLocalDayBoundaryAcrossDst() = runTest(dispatcher) {
        val dstNow = Instant.parse("2026-11-02T12:00:00Z")
        val dstZone = ZoneId.of("America/New_York")
        val time = AnalyticsTimeProvider(Clock.fixed(dstNow, dstZone)) { dstZone }
        withVm(vm(time)) {
            val old = bounds.single { it.first == "observeRecentForItem" }.second
            val effective = bounds.single { it.first == "observeEffectiveRecentForItem" }.second
            assertEquals(old, effective)
            assertEquals(dstNow.atZone(dstZone).toLocalDate().minusDays(29).atStartOfDay(dstZone).toInstant().toEpochMilli(), old[1])
            assertEquals(dstNow.toEpochMilli(), old[2])
        }
    }
    @Test fun effectiveArithmeticFailureIsNotPresentedAsInsufficientSamples() = runTest(dispatcher) {
        val extremeNow = Instant.ofEpochMilli(Long.MAX_VALUE)
        val original = effectiveSource()
        val sessions = original.sessions.map { it.copy(startedAt = 0, endedAt = Long.MAX_VALUE) }
        effective.value = original.copy(sessions = sessions,
            contexts = original.contexts.mapValues { (_, context) -> context.copy(lastHeartbeatAt = Long.MAX_VALUE) },
            segments = sessions.associate { s -> s.id to listOf(original.segments.getValue(s.id).first().copy(
                startedAt = 0, endedAt = Long.MAX_VALUE)) })
        withVm(vm(AnalyticsTimeProvider(Clock.fixed(extremeNow, zone)) { zone })) { vm ->
            assertEquals("暂时无法计算有效阅读节奏", vm.uiState.value.analyticsError)
            assertNull(vm.uiState.value.analytics)
        }
    }
    private fun projection(id: String, days: Int): ReadingSessionProjection {
        val end = now.minus(Duration.ofDays(days.toLong())).toEpochMilli()
        return ReadingSessionProjection(id, "book", end - 1_800_000, end, 1, 11, SessionEndType.NORMAL, 0)
    }
    private fun effectiveSource(pages: Int = 10): EffectiveReadingSource {
        val sessions = (0..2).map { i ->
            val end = now.minus(Duration.ofDays(i.toLong() + 1)).toEpochMilli()
            StudySessionEntity("e$i", "book", "i$i", end - 1_800_000, null, end, 1, 1 + pages, 1 + pages,
                SessionEndType.NORMAL, null, null)
        }
        return EffectiveReadingSource(sessions, sessions.associate { s -> s.id to SessionFocusContextEntity(
            sessionId = s.id, monitoringStatus = MonitoringCoverage.FULL, monitoringLostAt = null,
            priorDndInterruptionFilter = null, dndRuleId = null, requestedEndPage = null, closeoutStartedAt = null,
            lastHeartbeatAt = s.endedAt!!, createdAt = s.startedAt, updatedAt = s.endedAt) },
            sessions.associate { s -> s.id to listOf(
                SessionSegmentEntity("${s.id}-f", s.id, SessionSegmentType.FOCUS, s.startedAt, s.startedAt + 1_200_000,
                    null, null, null, relatedSegmentId = null, activeSlot = null),
                SessionSegmentEntity("${s.id}-b", s.id, SessionSegmentType.BREAK, s.startedAt + 1_200_000, s.endedAt,
                    null, null, null, relatedSegmentId = null, activeSlot = null)) })
    }
}
