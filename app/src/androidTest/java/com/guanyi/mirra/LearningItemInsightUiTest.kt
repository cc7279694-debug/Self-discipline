package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.repository.ReadingInsightRepository
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.insights.*
import com.guanyi.mirra.feature.knowledge.*
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

/** Controlled test fixture using the real detail VM and in-memory repositories, never installed user data. */
class LearningItemInsightUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var container: TestAppContainer
    private var vm: LearningItemDetailViewModel? = null
    private val slower = "最近 3 次可比较阅读，比此前这本书的有效阅读速度低约 30%。"
    private val faster = "最近 3 次可比较阅读，比此前这本书的有效阅读速度高约 35%。"
    private val note = "仅比较完整监测且可计算有效阅读速度的记录。"
    @Before fun before() { container = TestAppContainer(ApplicationProvider.getApplicationContext()) }
    @After fun after() { rule.activityRule.scenario.close(); vm?.viewModelScope?.cancel(); container.close() }

    @Test fun detailReachable320Normal() = reachable(320, 1f)
    @Test fun detailReachable320Double() = reachable(320, 2f)
    @Test fun detailReachable360Normal() = reachable(360, 1f)
    @Test fun detailReachable360Double() = reachable(360, 2f)
    @Test fun detailReachable411Normal() = reachable(411, 1f)
    @Test fun detailReachable411Double() = reachable(411, 2f)
    @Test fun fasterAppearsWithoutSlowerDescription() {
        val item = runBlocking { container.learningItemRepository.create("较快节奏测试", 320) }
        val vm = detail(item.id) { flowOf(result(ReadingPaceInsightDirection.FASTER)) }
        rule.setContent { MirraTheme { LearningItemDetailScreen(vm, {}, {}, {}, {}) } }
        awaitItem(vm)
        scroll(faster); rule.onNodeWithText(faster).assertIsDisplayed()
        rule.onNodeWithText("近期阅读节奏变快").assertExists()
        rule.onNodeWithText("近期阅读节奏变慢").assertDoesNotExist()
    }
    private fun reachable(width: Int, fontScale: Float) {
        val results = MutableStateFlow(result())
        val item = runBlocking { container.learningItemRepository.create(
            "一本名字很长的受控测试书，用于验证近期阅读节奏描述在大字模式下完整换行以及书籍详情操作可达性", 320) }
        val vm = detail(item.id) { results }
        var backs = 0
        var notes = 0
        val density = rule.activity.resources.displayMetrics.density
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) { MirraTheme {
            Box(Modifier.width(width.dp).height(560.dp).testTag("insight-detail-viewport")) {
                LearningItemDetailScreen(vm, {}, { notes++ }, {}, { backs++ })
            }
        } } }
        awaitItem(vm)
        assertEquals(width.toFloat(), rule.onNodeWithTag("insight-detail-viewport").fetchSemanticsNode().boundsInRoot.width / density, 0.5f)
        scroll("开始阅读")
        val start = rule.onNode(hasText("开始阅读") and hasClickAction())
        start.assertIsDisplayed(); assertTrue(start.fetchSemanticsNode().boundsInRoot.height / density >= 47.9f)
        scroll("查看这本书的笔记")
        val notesAction = rule.onNode(hasText("查看这本书的笔记") and hasClickAction())
        notesAction.assertIsDisplayed(); assertTrue(notesAction.fetchSemanticsNode().boundsInRoot.height / density >= 47.9f)
        notesAction.performClick(); assertEquals(1, notes)
        scroll(slower); rule.onNodeWithText(slower).assertIsDisplayed()
        scroll(note); rule.onNodeWithText(note).assertIsDisplayed()
        rule.runOnIdle { results.value = result(ReadingPaceInsightDirection.FASTER) }
        awaitInsight(vm, ReadingPaceInsightDirection.FASTER)
        scroll(faster); rule.onNodeWithText(faster).assertIsDisplayed()
        rule.onNodeWithText("近期阅读节奏变慢").assertDoesNotExist()
        scroll("返回")
        val back = rule.onNode(hasText("返回") and hasClickAction())
        back.assertIsDisplayed(); assertTrue(back.fetchSemanticsNode().boundsInRoot.height / density >= 47.9f)
        back.performClick(); assertEquals(1, backs)
    }

    @Test fun loadingUnavailableAndFailureKeepExistingDetailAndNotesWorking() {
        val gate = CompletableDeferred<Unit>()
        val item = runBlocking { container.learningItemRepository.create("读取隔离测试", 320) }
        var attempts = 0
        val vm = detail(item.id) { ++attempts; flow {
            gate.await(); emit(ReadingPaceInsightResult(null, ReadingPaceInsightUnavailableReason.INSUFFICIENT_BASELINE))
            throw IllegalStateException("controlled read failure")
        } }
        var notes = 0
        rule.setContent { MirraTheme { LearningItemDetailScreen(vm, {}, { notes++ }, {}, {}) } }
        awaitItem(vm)
        assertHidden()
        scroll("查看这本书的笔记"); rule.onNodeWithText("查看这本书的笔记").performClick()
        assertEquals(1, notes)
        gate.complete(Unit)
        rule.waitForIdle()
        assertHidden()
        val beforeRetry = attempts
        rule.runOnIdle { vm.refreshTimeContext() }
        rule.waitUntil(5_000) { attempts > beforeRetry }
        assertHidden(); assertNull(vm.uiState.value.analyticsError)
        assertNotNull(vm.uiState.value.analytics)
    }

    @Test fun greaterThan100PercentIsReadableWithoutFalsePrecision() {
        val item = runBlocking { container.learningItemRepository.create("变化幅度测试", 320) }
        val result = result(ReadingPaceInsightDirection.FASTER).let { it.copy(insight = it.insight!!.copy(
            ratio = 2.47, roundedChangePercent = 145, changeExceeds100Percent = true)) }
        val vm = detail(item.id) { flowOf(result) }
        rule.setContent { MirraTheme { LearningItemDetailScreen(vm, {}, {}, {}, {}) } }
        awaitItem(vm)
        val body = "最近 3 次可比较阅读，比此前这本书的有效阅读速度高超过 100%。"
        scroll(body); rule.onNodeWithText(body).assertIsDisplayed()
        rule.onNodeWithText("145%", substring = true).assertDoesNotExist()
    }

    @Test fun realContainerBookDetailInsightAndSystemBackReturnToKnowledgeWithoutWrites() {
        runBlocking { insertInsightFacts() }
        val factsBefore = facts()
        rule.setContent { MirraTheme { MirraApp(container, TopLevelDestination.Knowledge, {}) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("真实书籍节奏测试").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("真实书籍节奏测试").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("当前第 58 页，共 320 页").fetchSemanticsNodes().isNotEmpty() }
        scroll(slower); rule.onNodeWithText(slower).assertIsDisplayed()
        scroll("阅读历史"); rule.onNodeWithText("阅读历史").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("搜索知识").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("真实书籍节奏测试").assertExists()
        assertEquals(factsBefore, facts())
        assertNull(runBlocking { container.studyWorkflowRepository.observeActiveSession().first() })
    }

    private fun detail(id: String, read: () -> Flow<ReadingPaceInsightResult>): LearningItemDetailViewModel {
        val repository = object : ReadingInsightRepository {
            override fun observeForItem(itemId: String, time: AnalyticsTimeContext) = read()
        }
        return LearningItemDetailViewModel(id, container.learningItemRepository, container.studyWorkflowRepository,
            container.readingAnalyticsRepository, container.readingAnalyticsService, container.completionPredictionService,
            container.analyticsTimeProvider, container.effectiveReadingService, repository).also { vm = it }
    }
    private fun awaitItem(vm: LearningItemDetailViewModel) = rule.waitUntil(5_000) { vm.uiState.value.item != null }
    private fun awaitInsight(vm: LearningItemDetailViewModel, direction: ReadingPaceInsightDirection) =
        rule.waitUntil(5_000) { vm.uiState.value.insight?.direction == direction }
    private fun scroll(text: String) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        rule.onNodeWithText(text).performScrollTo()
    }
    private fun assertHidden() {
        rule.onNodeWithText("近期阅读节奏变慢").assertDoesNotExist()
        rule.onNodeWithText("近期阅读节奏变快").assertDoesNotExist()
        rule.onNodeWithText(note).assertDoesNotExist()
    }
    private fun result(direction: ReadingPaceInsightDirection = ReadingPaceInsightDirection.SLOWER) = ReadingPaceInsightResult(
        ReadingPaceInsight(direction, if (direction == ReadingPaceInsightDirection.SLOWER) .7 else 1.35,
            ReadingPaceWindowEvidence(listOf("r1", "r2", "r3"), 21, 2_160_000, 35.0),
            ReadingPaceWindowEvidence(listOf("b1", "b2", "b3", "b4", "b5"), 50, 3_600_000, 50.0),
            if (direction == ReadingPaceInsightDirection.SLOWER) 30 else 35, false), null)
    private suspend fun insertInsightFacts() {
        val now = System.currentTimeMillis()
        container.database.learningItemDao().insert(LearningItemEntity("book", "真实书籍节奏测试", LearningItemStatus.IN_PROGRESS,
            320, 58, null, "", now, now, null))
        repeat(8) { index ->
            val id = "insight-$index"
            val end = now - 3_600_000 - index * 86_400_000L
            val start = end - 720_000L
            val pages = if (index < 3) 7 else 10
            container.database.intentDao().insert(StudyIntentEntity("intent-$id", "book", start, start, start, start, IntentOutcome.CONVERTED, null))
            container.database.sessionDao().insert(StudySessionEntity(id, "book", "intent-$id", start, null, end,
                1, 1 + pages, 1 + pages, SessionEndType.NORMAL, null, null))
            container.database.focusDao().insertContext(SessionFocusContextEntity(sessionId = id, monitoringStatus = MonitoringCoverage.FULL,
                monitoringLostAt = null, priorDndInterruptionFilter = null, dndRuleId = null, requestedEndPage = null,
                closeoutStartedAt = null, lastHeartbeatAt = end, createdAt = start, updatedAt = end))
            container.database.focusDao().insertSegment(SessionSegmentEntity("segment-$id", id, SessionSegmentType.FOCUS,
                start, end, null, null, null, relatedSegmentId = null, activeSlot = null))
        }
        container.searchRepository.rebuildIndex()
    }
    private fun facts() = runBlocking {
        listOf("learning_items", "study_intents", "study_sessions", "session_focus_contexts", "session_segments").map { table ->
            container.database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
                }
            }
        }
    }
}
