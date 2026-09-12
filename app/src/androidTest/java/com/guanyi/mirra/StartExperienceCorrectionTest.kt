package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.navigation.TopLevelDestination
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class StartExperienceCorrectionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: TestAppContainer

    @Before
    fun setUp() {
        container = TestAppContainer(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        composeRule.activityRule.scenario.close()
        container.close()
    }

    @Test
    fun emptyLibraryCreatesFirstBookWithExplicitDefaultMainlineChoice() {
        launchStart()
        waitForText("开始你的第一次学习")
        composeRule.onNodeWithText("开始你的第一次学习").assertExists()
        composeRule.onNodeWithText("添加第一本书").performClick()
        composeRule.onNode(hasText("书名") and hasSetTextAction()).performTextInput("第一本")
        composeRule.onNode(hasText("总页数") and hasSetTextAction()).performTextInput("100")
        composeRule.onNode(hasText("创建") and hasClickAction()).performClick()

        composeRule.waitUntil(5_000) {
            runBlocking { container.learningItemRepository.observeMainline().first() }?.name == "第一本"
        }
        composeRule.onNodeWithText("当前主线").assertExists()
    }

    @Test
    fun firstBookCanBeCreatedWithoutMainline() {
        launchStart()
        waitForText("添加第一本书")
        composeRule.onNodeWithText("添加第一本书").performClick()
        composeRule.onNode(hasText("书名") and hasSetTextAction()).performTextInput("非主线")
        composeRule.onNode(hasText("总页数") and hasSetTextAction()).performTextInput("100")
        composeRule.onNode(isToggleable()).performClick()
        composeRule.onNode(hasText("创建") and hasClickAction()).performClick()

        composeRule.waitUntil(5_000) {
            runBlocking { container.learningItemRepository.observeAll().first() }.size == 1
        }
        assertNull(runBlocking { container.learningItemRepository.observeMainline().first() })
        composeRule.onNodeWithText("这次想学什么？").assertExists()
    }

    @Test
    fun choosingThisStudyDoesNotImplicitlyChangeMainline() {
        val chosen = runBlocking { container.learningItemRepository.create("本次学习", 120, 18) }
        launchStart()
        waitForText("本次学习")
        composeRule.onNodeWithText("本次学习").performClick()
        composeRule.onNodeWithText("开始学习").performClick()

        composeRule.waitUntil(5_000) {
            runBlocking { container.studyWorkflowRepository.observeActiveIntent().first() }?.learningItemId == chosen.id
        }
        assertNull(runBlocking { container.learningItemRepository.observeMainline().first() })
    }

    @Test
    fun activeIntentAndSessionUseContinuationActions() {
        val item = runBlocking { container.learningItemRepository.create("继续测试", 100, 8) }
        val intent = runBlocking { container.studyWorkflowRepository.createIntent(item.id) }
        launchStart()
        waitForText("继续准备")
        composeRule.onNodeWithText("继续准备").assertExists()

        runBlocking { container.studyWorkflowRepository.startSession(intent.id, 8) }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("继续学习")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("当前第 8 页 · 已进行 0 分钟").assertExists()
    }

    @Test
    fun noInProgressContentRoutesToKnowledge() {
        val item = runBlocking { container.learningItemRepository.create("暂停书", 100) }
        runBlocking { container.learningItemRepository.pause(item.id) }
        launchStart()

        waitForText("暂无正在学习的内容")
        composeRule.onNodeWithText("暂无正在学习的内容").assertExists()
        composeRule.onNodeWithText("查看学习内容").performClick()
        composeRule.onNodeWithText("暂停书").assertExists()
    }

    private fun launchStart() {
        composeRule.setContent { MirraApp(container, TopLevelDestination.Start, onDestinationChanged = {}) }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
