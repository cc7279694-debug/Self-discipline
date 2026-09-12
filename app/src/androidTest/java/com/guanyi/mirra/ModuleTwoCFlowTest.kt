package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.navigation.TopLevelDestination
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ModuleTwoCFlowTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var container: TestAppContainer

    @Before fun setUp() {
        container = TestAppContainer(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() {
        composeRule.activityRule.scenario.close()
        container.close()
    }

    @Test fun knowledgeCanSearchAndOpenTypedResults() {
        runBlocking {
            val item = container.learningItemRepository.create("认知训练", 100)
            container.noteRepository.createStandalone(item.id, "心理账户会影响选择", pageNumber = 12)
            container.topicRepository.create("行为经济学")
        }
        launchKnowledge()

        composeRule.onNodeWithText("搜索").performClick()
        composeRule.onNode(hasText("搜索笔记、内容、Topic 或阅读总结") and hasSetTextAction()).performTextInput("账户")
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("第 12 页")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("认知训练").performClick()
        composeRule.onNodeWithText("心理账户会影响选择").assertExists()
    }

    @Test fun topicListCreatesAndOpensTopicDetail() {
        launchKnowledge()
        composeRule.onNodeWithText("Topic").performClick()
        composeRule.onNode(hasText("新 Topic") and hasSetTextAction()).performTextInput("决策")
        composeRule.onNodeWithText("创建").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("决策")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("决策").performClick()
        composeRule.onNodeWithText("这个 Topic 还没有关联笔记。").assertExists()
    }

    @Test fun noteCanLinkAndUnlinkTopicWithConfirmation() {
        val noteId = runBlocking {
            val item = container.learningItemRepository.create("关联书", 100)
            container.topicRepository.create("心理")
            container.noteRepository.createStandalone(item.id, "心理账户").id
        }
        launchKnowledge()
        composeRule.onNodeWithText("全部笔记").performClick()
        composeRule.onNodeWithText("心理账户").performClick()
        composeRule.onNodeWithText("关联 Topic").performClick()
        composeRule.onNodeWithText("心理", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { runBlocking { container.topicRepository.observeForNote(noteId).first().size } == 1 }
        composeRule.onNodeWithText("✓ 心理（解除）").performClick()
        composeRule.onNodeWithText("确认解除").performClick()
        composeRule.waitUntil(5_000) { runBlocking { container.topicRepository.observeForNote(noteId).first().isEmpty() } }
        assertEquals(0, runBlocking { container.topicRepository.observeForNote(noteId).first().size })
    }

    private fun launchKnowledge() {
        composeRule.setContent { MirraApp(container, TopLevelDestination.Knowledge, onDestinationChanged = {}) }
    }
}
