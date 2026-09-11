package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.navigation.TopLevelDestination
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ModuleTwoAFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: TestAppContainer

    @Before
    fun setUp() {
        container = TestAppContainer(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = container.close()

    @Test
    fun learningItemCanPauseResumeAndCompleteWithoutRestoringMainline() {
        val item = runBlocking {
            container.learningItemRepository.create("生命周期", 100, 10).also {
                container.learningItemRepository.setMainline(it.id)
            }
        }
        launchKnowledge()
        composeRule.onNodeWithText("生命周期").performClick()

        composeRule.onNodeWithText("状态：进行中").assertExists()
        composeRule.onNodeWithText("暂停").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { container.learningItemRepository.get(item.id)?.status } == LearningItemStatus.PAUSED
        }
        composeRule.onNodeWithText("状态：已暂停").assertExists()
        assertTextAbsent("开始阅读")
        composeRule.onNodeWithText("恢复为进行中").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { container.learningItemRepository.get(item.id)?.status } == LearningItemStatus.IN_PROGRESS
        }
        assertNull(runBlocking { container.learningItemRepository.get(item.id)?.mainlineSlot })

        composeRule.onNodeWithText("标记为已完成").performClick()
        composeRule.onNodeWithText("确认完成").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { container.learningItemRepository.get(item.id)?.status } == LearningItemStatus.COMPLETED
        }
        composeRule.onNodeWithText("状态：已完成").assertExists()
        assertTextAbsent("恢复为进行中")
        assertTextAbsent("开始阅读")
    }

    @Test
    fun standaloneNoteCreatesOnlyAfterRequiredFieldsAndCanBeEditedAndDeleted() {
        val item = runBlocking { container.learningItemRepository.create("笔记所属书", 120, 40) }
        launchKnowledge()
        composeRule.onNodeWithText("全部笔记").performClick()
        composeRule.onNodeWithText("还没有笔记").assertExists()
        composeRule.onNodeWithText("新建笔记").performClick()

        composeRule.onNodeWithText("选择学习内容").performClick()
        composeRule.onNodeWithText("笔记所属书").performClick()
        composeRule.onNode(hasText("笔记内容") and hasSetTextAction()).performTextInput("为什么损失更强？")
        composeRule.onNode(hasText("页码（可选）") and hasSetTextAction()).performTextInput("12")
        composeRule.onNodeWithText("问题").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("已自动保存")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.activity.onBackPressedDispatcher.onBackPressed()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("为什么损失更强？")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(40, runBlocking { container.learningItemRepository.get(item.id)?.currentPage })
        composeRule.onNodeWithText("为什么损失更强？").performClick()
        composeRule.onNode(hasText("笔记内容") and hasSetTextAction()).performTextReplacement("这是修改后的理解")
        composeRule.onNode(hasText("页码（可选）") and hasSetTextAction()).performTextReplacement("8")
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("已自动保存")).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("删除笔记").performClick()
        composeRule.onNodeWithText("删除后无法恢复。").assertExists()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.onNode(hasText("笔记内容") and hasSetTextAction()).assertExists()
        composeRule.onNodeWithText("删除笔记").performClick()
        composeRule.onNodeWithText("确认删除").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { container.noteRepository.observeAll().first() }.isEmpty()
        }
    }

    @Test
    fun noteListSupportsSemanticAndLearningItemFilters() {
        val first = runBlocking { container.learningItemRepository.create("第一本", 100) }
        val second = runBlocking { container.learningItemRepository.create("第二本", 100) }
        runBlocking {
            container.noteRepository.createStandalone(first.id, "第一本摘录", NoteSemanticType.QUOTE, 3)
            container.noteRepository.createStandalone(first.id, "第一本问题", NoteSemanticType.QUESTION, 4)
            container.noteRepository.createStandalone(second.id, "第二本问题", NoteSemanticType.QUESTION, 5)
        }
        launchKnowledge()
        composeRule.onNodeWithText("全部笔记").performClick()

        composeRule.onNodeWithText("问题").performClick()
        assertTextAbsent("第一本摘录")
        composeRule.onNodeWithText("第一本问题").assertExists()
        composeRule.onNodeWithText("第二本问题").assertExists()

        composeRule.onNodeWithText("全部学习内容").performClick()
        composeRule.onNodeWithText("第一本").performClick()
        composeRule.onNodeWithText("第一本问题").assertExists()
        assertTextAbsent("第二本问题")
    }

    @Test
    fun createNoteDoesNotPersistBlankPlaceholder() {
        runBlocking { container.learningItemRepository.create("空白测试", 100) }
        launchKnowledge()
        composeRule.onNodeWithText("全部笔记").performClick()
        composeRule.onNodeWithText("新建笔记").performClick()
        composeRule.onNodeWithText("选择学习内容").performClick()
        composeRule.onNodeWithText("空白测试").performClick()
        composeRule.onNode(hasText("笔记内容") and hasSetTextAction()).performTextInput("   ")
        assertTextAbsent("已自动保存")
        composeRule.activity.onBackPressedDispatcher.onBackPressed()

        assertEquals(0, runBlocking { container.noteRepository.observeAll().first().size })
    }

    @Test
    fun standaloneDraftFlushesWhenActivityGoesToBackground() {
        runBlocking { container.learningItemRepository.create("后台保存", 100) }
        launchKnowledge()
        composeRule.onNodeWithText("全部笔记").performClick()
        composeRule.onNodeWithText("新建笔记").performClick()
        composeRule.onNodeWithText("选择学习内容").performClick()
        composeRule.onNodeWithText("后台保存").performClick()
        composeRule.onNode(hasText("笔记内容") and hasSetTextAction()).performTextInput("最后一次输入")

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.waitUntil(5_000) {
            runBlocking { container.noteRepository.observeAll().first() }
                .singleOrNull()?.note?.content == "最后一次输入"
        }
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    @Test
    fun activeIntentBlocksPauseAndShowsReason() {
        val item = runBlocking {
            container.learningItemRepository.create("启动冲突", 100).also {
                container.studyWorkflowRepository.createIntent(it.id)
            }
        }
        launchKnowledge()
        composeRule.onNodeWithText("启动冲突").performClick()
        composeRule.onNodeWithText("暂停").performClick()

        composeRule.onNodeWithText("请先取消这本书当前的启动").assertExists()
        assertEquals(LearningItemStatus.IN_PROGRESS, runBlocking { container.learningItemRepository.get(item.id)?.status })
    }

    private fun launchKnowledge() {
        composeRule.setContent {
            MirraApp(container, TopLevelDestination.Knowledge, onDestinationChanged = {})
        }
    }

    private fun assertTextAbsent(text: String) {
        assertEquals(0, composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().size)
    }
}
