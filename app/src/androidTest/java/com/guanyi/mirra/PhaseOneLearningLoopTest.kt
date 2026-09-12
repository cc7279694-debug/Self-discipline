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
import com.guanyi.mirra.navigation.TopLevelDestination
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PhaseOneLearningLoopTest {
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
    fun completeLearningLoopCreatesBookNotesProgressAndSummary() {
        composeRule.setContent {
            MirraApp(container, TopLevelDestination.Start, onDestinationChanged = {})
        }

        composeRule.onNodeWithText("添加第一本书").performClick()
        composeRule.onNode(hasText("书名") and hasSetTextAction()).performTextInput("怪诞行为学")
        composeRule.onNode(hasText("总页数") and hasSetTextAction()).performTextInput("300")
        composeRule.onNode(hasText("当前页") and hasSetTextAction()).performTextReplacement("10")
        composeRule.onNode(hasText("创建") and hasClickAction()).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("当前主线")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("开始学习").performClick()
        composeRule.onNodeWithText("我已拿起书，开始阅读").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("快速笔记")).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithText("摘录").performClick()
        composeRule.onNode(hasText("写下摘录或想法") and hasSetTextAction()).performTextInput("损失厌恶比收益更强")
        composeRule.onNode(hasText("笔记页码（可选）") and hasSetTextAction()).performTextReplacement("12")
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("已自动保存")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("保存并记下一条").performClick()
        composeRule.onNodeWithText("问题").performClick()
        composeRule.onNode(hasText("写下摘录或想法") and hasSetTextAction()).performTextInput("这个结论适用于长期选择吗？")
        composeRule.onNodeWithText("保存并记下一条").performClick()
        composeRule.onNode(hasText("当前页码") and hasSetTextAction()).performTextReplacement("25")
        composeRule.onNodeWithText("结束本次阅读").performClick()

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("本次阅读已保存")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("本次阅读 10–25 页，用时不足 1 分钟，共记录 2 条笔记。").assertExists()
        composeRule.onNodeWithText("阅读页数：15").assertExists()
        composeRule.onNodeWithText("Note 数量：2").assertExists()
        composeRule.onNodeWithText("完成").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("上次停在第 25 页 · 共 300 页")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasText("知识") and hasClickAction()).performClick()
        composeRule.onNodeWithText("怪诞行为学").performClick()
        composeRule.onNodeWithText("当前第 25 页，共 300 页").assertExists()
        composeRule.onNodeWithText("上次总结：本次阅读 10–25 页，用时不足 1 分钟，共记录 2 条笔记。").assertExists()
    }
}
