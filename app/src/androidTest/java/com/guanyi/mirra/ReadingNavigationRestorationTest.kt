package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadingNavigationRestorationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun restoredReadingRouteKeepsSameSessionDraftAndFocusDisplay() {
        val container = TestAppContainer(ApplicationProvider.getApplicationContext())
        try {
            runBlocking { container.learningItemRepository.create("重建阅读测试书", 300, 40,
                "翻到第 40 页，读这一页的第一段。", setAsMainline = true) }
            val restoration = StateRestorationTester(rule)
            restoration.setContent { MirraTheme { MirraApp(container, TopLevelDestination.Start, {}) } }
            rule.waitUntil(5_000) { rule.onAllNodes(hasText("开始准备") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("开始准备").performScrollTo().performClick()
            rule.waitUntil(5_000) { rule.onAllNodes(hasText("我已完成，开始阅读") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("我已完成，开始阅读").performScrollTo().performClick()
            rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("session-reading-timer")).fetchSemanticsNodes().isNotEmpty() }
            val originalSession = runBlocking { container.studyWorkflowRepository.observeActiveSession().first() }!!
            rule.onNodeWithText("记录想法").performScrollTo().performClick()
            rule.onNode(hasSetTextAction() and hasText("写下摘录或想法")).performTextInput("重建后仍在的草稿")
            rule.waitUntil(5_000) { runBlocking { container.noteRepository.observeForSession(originalSession.id).first() }.size == 1 }
            val originalNote = runBlocking { container.noteRepository.observeForSession(originalSession.id).first() }.single()
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.onNodeWithText("收起笔记").performClick()
            rule.onNodeWithText("专注显示").performScrollTo().performClick()
            rule.onNodeWithText("退出专注显示").assertExists()

            restoration.emulateSavedInstanceStateRestore()

            rule.onNodeWithText("退出专注显示").assertExists()
            assertEquals(originalSession.id, runBlocking { container.studyWorkflowRepository.observeActiveSession().first() }!!.id)
            rule.onNodeWithText("退出专注显示").performClick()
            rule.onNodeWithText("记录想法").performScrollTo().performClick()
            rule.onNode(hasSetTextAction() and hasText("写下摘录或想法")).assertTextContains(originalNote.content)
            val after = runBlocking { container.noteRepository.observeForSession(originalSession.id).first() }.single()
            assertEquals(originalNote.id, after.id)
            assertEquals(originalNote.content, after.content)
            assertNull(runBlocking { container.studyWorkflowRepository.observeSession(originalSession.id).first() }!!.endedAt)
        } finally {
            rule.activityRule.scenario.close()
            container.close()
        }
    }
}
