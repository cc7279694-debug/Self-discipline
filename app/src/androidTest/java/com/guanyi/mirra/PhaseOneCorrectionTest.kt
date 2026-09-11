package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.navigation.TopLevelDestination
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PhaseOneCorrectionTest {
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
    fun preparationSeparatesKeepForLaterFromAbandon() {
        launchAtPreparation()

        composeRule.onNodeWithText("稍后再说").performClick()
        val kept = runBlocking { container.studyWorkflowRepository.observeActiveIntent().first() }
        assertEquals(null, kept?.outcome)

        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("继续启动").performClick()
        composeRule.onNodeWithText("取消本次启动").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { container.studyWorkflowRepository.observeActiveIntent().first() } == null
        }

        val abandoned = runBlocking { container.studyWorkflowRepository.observeActiveIntent().first() }
        assertNull(abandoned)
    }

    @Test
    fun newDraftInheritsLatestPageAndManualTypeWins() {
        launchAtSession(currentPage = 10)

        composeRule.onNode(hasText("笔记页码（可选）") and hasSetTextAction()).assertTextContains("10")
        composeRule.onNode(hasText("当前页码") and hasSetTextAction()).performTextReplacement("25")
        composeRule.onNode(hasText("写下摘录或想法") and hasSetTextAction()).performTextInput("为什么？")
        composeRule.onNodeWithText("问题").assertIsSelected()
        composeRule.onNodeWithText("摘录").performClick()
        composeRule.onNode(hasText("写下摘录或想法") and hasSetTextAction()).performTextReplacement("改完以后仍是问题？")
        composeRule.onNodeWithText("摘录").assertIsSelected()
        composeRule.onNodeWithText("保存并记下一条").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("改完以后仍是问题？")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("笔记页码（可选）") and hasSetTextAction()).assertTextContains("25")
        val notes = runBlocking { container.noteRepository.observeForSession(activeSessionId()).first() }
        assertEquals(NoteSemanticType.QUOTE, notes.single().semanticType)
        assertEquals(25, notes.single().pageNumber)
    }

    @Test
    fun backgroundFlushesDraftBeforeDebounce() {
        launchAtSession(currentPage = 10)
        val sessionId = activeSessionId()
        composeRule.onNode(hasText("写下摘录或想法") and hasSetTextAction()).performTextInput("切到后台也要保存")

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)

        val note = runBlocking {
            withTimeout(5_000) {
                container.noteRepository.observeForSession(sessionId).first { it.size == 1 }.single()
            }
        }
        assertEquals("切到后台也要保存", note.content)
        assertEquals(10, note.pageNumber)
    }

    @Test
    fun leavingSessionFlushesDraftBeforeNavigatingAway() {
        launchAtSession(currentPage = 10)
        val sessionId = activeSessionId()
        composeRule.onNode(hasText("写下摘录或想法") and hasSetTextAction()).performTextInput("离开页面前保存")

        composeRule.activity.onBackPressedDispatcher.onBackPressed()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("继续当前阅读")).fetchSemanticsNodes().isNotEmpty()
        }
        val note = runBlocking { container.noteRepository.observeForSession(sessionId).first().single() }
        assertEquals("离开页面前保存", note.content)
    }

    @Test
    fun finishingImmediatelyFlushesDraft() {
        launchAtSession(currentPage = 10)
        composeRule.onNode(hasText("写下摘录或想法") and hasSetTextAction()).performTextInput("最后一笔")
        composeRule.onNodeWithText("结束本次阅读").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("Note 数量：1")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun launchAtPreparation() {
        composeRule.setContent { MirraApp(container, TopLevelDestination.Start, onDestinationChanged = {}) }
        createBookAndOpenPreparation()
    }

    private fun launchAtSession(currentPage: Int) {
        composeRule.setContent { MirraApp(container, TopLevelDestination.Start, onDestinationChanged = {}) }
        createBookAndOpenPreparation(currentPage)
        composeRule.onNodeWithText("我已拿起书，开始阅读").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("快速笔记")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun createBookAndOpenPreparation(currentPage: Int = 10) {
        composeRule.onNodeWithText("创建第一本书").performClick()
        composeRule.onNode(hasText("书名") and hasSetTextAction()).performTextInput("修正测试书")
        composeRule.onNode(hasText("总页数") and hasSetTextAction()).performTextInput("100")
        composeRule.onNode(hasText("当前页") and hasSetTextAction()).performTextReplacement(currentPage.toString())
        composeRule.onNode(hasText("创建") and hasClickAction()).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("开始阅读")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("开始阅读").performClick()
    }

    private fun activeSessionId(): String = runBlocking {
        requireNotNull(container.studyWorkflowRepository.observeActiveSession().first()).id
    }
}
