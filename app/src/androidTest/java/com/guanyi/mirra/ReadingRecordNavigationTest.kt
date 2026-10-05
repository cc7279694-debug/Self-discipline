package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.feature.session.*
import com.guanyi.mirra.di.AppContainer
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class ReadingRecordNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var container: TestAppContainer
    @Before fun before() { container = TestAppContainer(ApplicationProvider.getApplicationContext()) }
    @After fun after() { rule.activityRule.scenario.close(); container.close() }

    @Test fun threeEntryPointsRenderSameFactsReturnToOwnOriginAndNeverReopenSession() {
        runBlocking { insertReadingRecordFixture(container) }
        val before = runBlocking { container.readingRecordRepository.observe("s").first() }!!
        val beforeCounts = rowCounts()
        val writes = mutableListOf<String>()
        val readOnlyWorkflow = Proxy.newProxyInstance(StudyWorkflowRepository::class.java.classLoader,
            arrayOf(StudyWorkflowRepository::class.java)) { _, method, args ->
            if (!method.name.startsWith("observe") && !method.name.startsWith("get")) {
                writes += method.name
                error("Record navigation must not write through workflow: ${method.name}")
            }
            method.invoke(container.studyWorkflowRepository, *(args ?: emptyArray()))
        } as StudyWorkflowRepository
        val readOnlyContainer = object : AppContainer by container {
            override val studyWorkflowRepository = readOnlyWorkflow
        }
        val vm = ReadingRecordViewModel("s", container.readingRecordRepository, container.readingRecordService)
        var summary by mutableStateOf(true)
        try {
            rule.setContent { MirraTheme {
                if (summary) Scaffold(containerColor = MirraTheme.colors.background) { padding ->
                    Box(Modifier.padding(padding)) { SessionSummaryScreen(vm, container.dndUserActions, { summary = false }) }
                }
                else MirraApp(readOnlyContainer, TopLevelDestination.Knowledge, {})
            } }
            await("本次阅读已保存"); assertRecord()
            clickExit("完成")
            await("统一记录测试"); rule.onNodeWithText("统一记录测试").performClick()
            await("当前第 58 页，共 320 页"); openHistory()
            await("阅读记录"); assertRecord()
            rule.waitForIdle(); captureReadingRecordEvidence("history-record")
            clickExit("返回")
            await("阅读历史")
            clickExit("返回")
            await("搜索知识"); rule.onNodeWithText("搜索知识").performClick()
            rule.onNode(hasSetTextAction() and hasText("搜索笔记、内容、Topic 或阅读总结")).performTextInput("记录测试总结")
            await("统一记录测试"); rule.onNodeWithText("统一记录测试").performClick()
            await("阅读记录"); assertRecord()
            rule.waitForIdle(); captureReadingRecordEvidence("search-record")
            clickExit("返回")
            await("统一记录测试")
            assertTrue(writes.isEmpty())
            assertEquals(before, runBlocking { container.readingRecordRepository.observe("s").first() })
            assertEquals(beforeCounts, rowCounts())
            assertNull(runBlocking { container.studyWorkflowRepository.observeActiveSession().first() })
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun summaryExpandsInlineAndDoneReturnsStartWithoutRestoringSession() {
        val session = runBlocking {
            val book = container.learningItemRepository.create("原地展开测试", 320)
            val intent = container.studyWorkflowRepository.createIntent(book.id)
            container.studyWorkflowRepository.startSession(intent.id, 40)
        }
        rule.setContent { MirraTheme { MirraApp(container, TopLevelDestination.Start, {}) } }
        await("继续学习"); rule.onNodeWithText("继续学习").performClick()
        await("结束本次阅读"); rule.onNodeWithText("结束本次阅读").performScrollTo().performClick()
        await("结束本次阅读？"); rule.onNodeWithTag("confirm-session-finish").performClick()
        await("本次阅读已保存")
        rule.onNodeWithText("查看本次记录").performScrollTo().performClick()
        rule.onNodeWithText("本次阅读已保存").assertExists()
        rule.onNodeWithText("阅读记录").assertDoesNotExist()
        rule.onNodeWithText("监测中断").assertExists()
        rule.onNodeWithText("完成").performScrollTo().performClick()
        rule.onNode(hasText("开始") and hasClickAction()).assertIsSelected()
        assertNull(runBlocking { container.studyWorkflowRepository.observeActiveSession().first() })
        assertEquals(SessionEndType.NORMAL, runBlocking { container.database.sessionDao().get(session.id) }!!.endType)
    }

    @Test fun abnormalHistoryCanBeReadButNeverShowsEffectiveTime() {
        runBlocking { insertReadingRecordFixture(container, SessionEndType.ABNORMAL) }
        rule.setContent { MirraTheme { MirraApp(container, TopLevelDestination.Knowledge, {}) } }
        await("统一记录测试"); rule.onNodeWithText("统一记录测试").performClick()
        await("当前第 58 页，共 320 页"); openHistory(); await("阅读记录")
        rule.onNodeWithText("异常结束 · 不参与有效统计").assertExists()
        rule.onNodeWithText("有效专注时间：", substring = true).assertDoesNotExist()
        clickExit("返回"); await("阅读历史")
    }

    @Test fun bookDetailShowsRepositoryBackedEffectivePaceAndKeepsNaturalPredictionSeparate() {
        runBlocking {
            insertReadingRecordFixture(container)
            val original = readingRecordTestSource()
            val now = System.currentTimeMillis()
            for (index in 0..2) {
                val id = "recent-$index"
                val end = now - 3_600_000L - index * 86_400_000L
                val start = end - 1_800_000
                val delta = start - original.session.startedAt
                container.database.intentDao().insert(StudyIntentEntity("intent-$id", "book", start, start, start, start, IntentOutcome.CONVERTED, null))
                container.database.sessionDao().insert(original.session.copy(id = id, intentId = "intent-$id", startedAt = start, endedAt = end))
                container.database.focusDao().insertContext(original.context!!.copy(sessionId = id, lastHeartbeatAt = end, createdAt = start, updatedAt = end))
                original.segments.forEach { segment -> container.database.focusDao().insertSegment(segment.copy(
                    id = "$id-${segment.id}", sessionId = id, startedAt = segment.startedAt + delta, endedAt = segment.endedAt!! + delta)) }
            }
        }
        rule.setContent { MirraTheme { MirraApp(container, TopLevelDestination.Knowledge, {}) } }
        await("统一记录测试"); rule.onNodeWithText("统一记录测试").performClick()
        await("当前第 58 页，共 320 页")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("有效阅读速度约 54 页/小时"))
        rule.onNodeWithText("根据最近 7 天 3 次完整阅读").assertExists()
        rule.onNodeWithText("预计还需约 4 小时 52 分钟 有效阅读").assertExists()
        rule.onNodeWithText("最近约 36 页/小时").assertDoesNotExist()
        rule.onNodeWithText("近期节奏仍在积累，暂不估算完成日期").assertExists()
        rule.waitForIdle(); captureReadingRecordEvidence("book-effective-pace")
    }

    private fun await(text: String) = rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun clickExit(text: String) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        rule.onNodeWithText(text).performClick()
    }
    private fun openHistory() {
        val text = "40 → 58 页 · 18 页 · 2 条笔记"
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        rule.onNodeWithText(text).performClick()
    }
    private fun assertRecord() {
        for (text in listOf("40 → 58 页 · 18 页", "阅读时长：30 分钟", "有效专注时间：20 分钟", "2 条笔记",
            "休息 1 次 · 临时使用 1 次 · 分心 1 次")) rule.onNodeWithText(text).assertExists()
        rule.onNodeWithText("查看本次记录").performScrollTo().performClick()
        rule.onNodeWithText("分心 · 历史浏览器").assertExists()
        rule.onNodeWithText("正在回到学习").assertExists()
        val formatter = DateTimeFormatter.ofPattern("M月d日 HH:mm:ss").withZone(ZoneId.systemDefault())
        val source = readingRecordTestSource()
        val record = container.readingRecordService.project(source)
        val labels = listOf("阅读", "休息", "临时使用 · 历史浏览器", "分心 · 历史浏览器", "正在回到学习", "阅读")
        val labelNodes = rule.onAllNodes(hasText("阅读") or hasText("休息") or
            hasText("临时使用 · 历史浏览器") or hasText("分心 · 历史浏览器") or hasText("正在回到学习"))
            .fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text }
        assertEquals(labels, labelNodes)
        for (interval in record.timeline) {
            val text = "${formatter.format(Instant.ofEpochMilli(interval.startedAt))}–${formatter.format(Instant.ofEpochMilli(interval.endedAt))}"
            rule.onNodeWithText(text).assertExists()
        }
    }
    private fun rowCounts() = runBlocking {
        listOf("study_intents", "study_sessions", "session_segments").map { table ->
            container.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
        }
    }
}

internal suspend fun insertReadingRecordFixture(container: TestAppContainer, endType: SessionEndType = SessionEndType.NORMAL) {
    val source = readingRecordTestSource()
    container.database.learningItemDao().insert(LearningItemEntity("book", "统一记录测试", LearningItemStatus.IN_PROGRESS,
        320, 58, null, "", source.session.startedAt, source.session.endedAt!!, null))
    container.database.intentDao().insert(StudyIntentEntity("i", "book", source.session.startedAt, source.session.startedAt,
        source.session.startedAt, source.session.startedAt, IntentOutcome.CONVERTED, null))
    container.database.sessionDao().insert(source.session.copy(endType = endType))
    container.database.focusDao().insertContext(source.context!!)
    source.segments.forEach { container.database.focusDao().insertSegment(it) }
    container.database.focusDao().insertRiskSnapshots(source.riskSnapshots)
    for (i in 1..2) container.database.noteDao().insert(NoteEntity("note-$i", "book", "s", NoteSemanticType.UNDERSTANDING,
        "受控测试笔记 $i", 40, source.session.startedAt, source.session.startedAt))
    container.searchRepository.rebuildIndex()
}
