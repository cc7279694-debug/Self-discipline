package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.feature.session.*
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SessionCloseoutUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun start(c: TestAppContainer) = runBlocking {
        val item = c.learningItemRepository.create("结束确认测试", 320)
        val intent = c.studyWorkflowRepository.createIntent(item.id)
        c.studyWorkflowRepository.startSession(intent.id, 40)
    }
    private fun vm(c: TestAppContainer, id: String) = SessionViewModel(id, c.studyWorkflowRepository,
        c.noteRepository, c.sessionManager, focusActions = c.focusSessionActions)

    @Test fun firstClickSavesDraftButContinueDoesNotEndReading() {
        val c = TestAppContainer(ApplicationProvider.getApplicationContext()); val s = start(c); val vm = vm(c, s.id)
        try {
            rule.setContent { MirraTheme { SessionScreen(vm, {}, {}, {}) } }
            rule.waitUntil(5_000) { vm.currentPageText == "40" }
            rule.runOnIdle { vm.changeContent("确认前的最后一笔") }
            rule.onNodeWithText("结束本次阅读").performScrollTo().performClick()
            rule.onNodeWithText("结束本次阅读？").assertExists()
            assertEquals(FocusCloseoutState.ACTIVE, runBlocking { c.studyWorkflowRepository.getCloseoutState(s.id) })
            assertEquals("确认前的最后一笔", runBlocking { c.noteRepository.observeForSession(s.id).first() }.single().content)
            rule.onNodeWithText("继续阅读").performClick()
            rule.onNodeWithText("结束本次阅读？").assertDoesNotExist()
            assertNull(runBlocking { c.database.sessionDao().get(s.id) }!!.endedAt)
        } finally { rule.activityRule.scenario.close(); vm.viewModelScope.cancel(); c.close() }
    }
    @Test fun lowerEndPageIsExplicitlyRejectedThenNormalSummaryKeepsMonotonicProgress() {
        val c = TestAppContainer(ApplicationProvider.getApplicationContext()); val s = start(c); val vm = vm(c, s.id)
        var completed = false
        try {
            rule.setContent { MirraTheme { SessionScreen(vm, { completed = true }, {}, {}) } }
            rule.waitUntil(5_000) { vm.currentPageText == "40" }
            rule.onNodeWithText("结束本次阅读").performScrollTo().performClick()
            rule.onNode(hasSetTextAction() and hasText("结束页码")).performTextReplacement("35")
            rule.onNodeWithTag("confirm-session-finish").performClick()
            rule.onNodeWithText("不能低于已经记录的阅读位置").assertExists()
            assertNull(runBlocking { c.database.sessionDao().get(s.id) }!!.endedAt)
            rule.onNode(hasSetTextAction() and hasText("结束页码")).performTextReplacement("42")
            rule.onNodeWithTag("confirm-session-finish").performClick()
            rule.waitUntil(5_000) { completed }
            val ended = runBlocking { c.database.sessionDao().get(s.id) }!!
            assertEquals(SessionEndType.NORMAL, ended.endType); assertEquals(42, ended.endPage)
            assertEquals(42, runBlocking { c.database.learningItemDao().get(s.learningItemId) }!!.currentPage)
        } finally { rule.activityRule.scenario.close(); vm.viewModelScope.cancel(); c.close() }
    }
    @Test fun recreatedPendingBackCannotResumeAndRetryUsesFrozenDecision() {
        val c = TestAppContainer(ApplicationProvider.getApplicationContext()); val s = start(c)
        val snapshot = runBlocking { c.studyWorkflowRepository.beginCloseout(s.id, 42, s.startedAt + 2_000) }
        val vm = vm(c, s.id); var left = false; var completed = false
        try {
            rule.setContent { MirraTheme { SessionScreen(vm, { completed = true }, {}, { left = true }) } }
            rule.onNodeWithText("阅读已结束").assertExists()
            rule.onNodeWithText("重试保存").assertExists()
            rule.onNodeWithText("快速笔记").assertDoesNotExist()
            rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.runOnIdle { assertFalse(left) }
            assertEquals(snapshot, vm.closeoutSnapshot)
            rule.onNodeWithText("重试保存").performClick()
            rule.waitUntil(5_000) { completed }
            assertEquals(snapshot.closeoutStartedAt, runBlocking { c.database.sessionDao().get(s.id) }!!.endedAt)
        } finally { rule.activityRule.scenario.close(); vm.viewModelScope.cancel(); c.close() }
    }
    @Test fun occupiedPendingOverridesRestoredKnowledgeWithoutResumingSession() {
        val c = TestAppContainer(ApplicationProvider.getApplicationContext()); val s = start(c)
        runBlocking { c.studyWorkflowRepository.beginCloseout(s.id, 42, s.startedAt + 2_000) }
        var restored by mutableStateOf(TopLevelDestination.Start)
        try {
            rule.setContent { MirraTheme { MirraApp(c, restored, {}) } }
            rule.waitUntil(5_000) { rule.onAllNodes(hasText("重试保存")).fetchSemanticsNodes().isNotEmpty() }
            rule.runOnIdle { restored = TopLevelDestination.Knowledge }
            rule.onNodeWithText("阅读已结束").assertExists()
            rule.onNodeWithText("重试保存").assertExists()
            rule.onNodeWithText("快速笔记").assertDoesNotExist()
            assertEquals(FocusCloseoutState.PENDING, runBlocking { c.studyWorkflowRepository.getCloseoutState(s.id) })
        } finally { rule.activityRule.scenario.close(); c.close() }
    }
    @Test fun lateInitialCloseoutQualificationStillSeedsFirstNotePage() {
        val c = TestAppContainer(ApplicationProvider.getApplicationContext()); val s = start(c)
        val delayed = object : StudyWorkflowRepository by c.studyWorkflowRepository {
            override fun observeCloseoutState(sessionId: String) = flow {
                delay(100); emit(FocusCloseoutState.ACTIVE)
            }
        }
        val vm = SessionViewModel(s.id, delayed, c.noteRepository, c.sessionManager, focusActions = c.focusSessionActions)
        try {
            rule.setContent { MirraTheme { SessionScreen(vm, {}, {}, {}) } }
            rule.waitUntil(5_000) { vm.currentPageText == "40" && vm.draftPage == "40" }
            rule.runOnIdle { vm.changeContent("首条笔记继承当前页") }
            rule.onNodeWithText("结束本次阅读").performScrollTo().performClick()
            rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("confirm-session-finish")).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(40, runBlocking { c.noteRepository.observeForSession(s.id).first() }.single().pageNumber)
            rule.onNodeWithText("继续阅读").performClick()
        } finally { rule.activityRule.scenario.close(); vm.viewModelScope.cancel(); c.close() }
    }
}
