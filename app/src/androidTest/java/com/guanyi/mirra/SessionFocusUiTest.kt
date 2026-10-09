package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.domain.monitoring.*
import com.guanyi.mirra.feature.session.*
import com.guanyi.mirra.ui.theme.MirraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class SessionFocusUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun editingCurrentPageAllowsPartialInputButNeverRollsBackRoomProgress() {
        val container = TestAppContainer(ApplicationProvider.getApplicationContext())
        val session = runBlocking {
            val item = container.learningItemRepository.create("页码编辑测试", 320, firstAction = "把书放到桌上，翻到上次阅读的位置")
            val intent = container.studyWorkflowRepository.createIntent(item.id)
            container.studyWorkflowRepository.startSession(intent.id, 40)
        }
        val vm = SessionViewModel(session.id, container.studyWorkflowRepository, container.noteRepository,
            container.sessionManager, focusActions = container.focusSessionActions)
        try {
            rule.setContent { MirraTheme { SessionScreen(vm, {}, {}, {}) } }
            rule.waitUntil(5_000) { vm.currentPageText == "40" }
            rule.onNode(hasSetTextAction() and hasText("当前页码")).performTextClearance()
            rule.onNode(hasSetTextAction() and hasText("当前页码")).performTextInput("4")
            rule.runOnIdle { assertEquals("4", vm.currentPageText) }
            assertEquals(40, runBlocking { container.studyWorkflowRepository.observeSession(session.id).first() }!!.currentPage)
            rule.onNode(hasSetTextAction() and hasText("当前页码")).performTextClearance()
            rule.onNode(hasSetTextAction() and hasText("当前页码")).performTextInput("42")
            rule.waitUntil(5_000) { vm.session.value?.currentPage == 42 }
            rule.runOnIdle { vm.changePage("35"); vm.changeContent("回看旧页") }
            rule.waitUntil(5_000) { vm.savedMessage == "已自动保存" }
            assertEquals(35, runBlocking { container.noteRepository.observeForSession(session.id).first() }.single().pageNumber)
            assertEquals(42, runBlocking { container.studyWorkflowRepository.observeSession(session.id).first() }!!.currentPage)
        } finally {
            rule.activityRule.scenario.close()
            vm.viewModelScope.cancel()
            container.close()
        }
    }

    @Test fun backDismissesPromptBeforeLeavingAndKeepsDraft() {
        val container = TestAppContainer(ApplicationProvider.getApplicationContext())
        val session = runBlocking {
            val item = container.learningItemRepository.create("Back 测试书", 100, firstAction = "把书放到桌上，翻到上次阅读的位置")
            val intent = container.studyWorkflowRepository.createIntent(item.id)
            container.studyWorkflowRepository.startSession(intent.id, 1)
        }
        val actions = container.focusSessionActions
        actions.focusStatus.value = FocusStatusUiModel(session.id, "seg", SessionSegmentType.RECOVERY, MonitoringCoverage.FULL)
        actions.intervention.value = InterventionUiModel(session.id, "p", "e", "seg", "risk", 0, 0)
        val vm = SessionViewModel(session.id, container.studyWorkflowRepository, container.noteRepository,
            container.sessionManager, focusActions = actions, learningItems = container.learningItemRepository)
        var left = false
        try {
            rule.setContent { MirraTheme { SessionScreen(vm, {}, {}, { left = true }) } }
            rule.waitUntil(5_000) { vm.session.value != null }
            rule.runOnIdle { vm.changeContent("Back 草稿"); vm.changePage("42") }
            rule.onNodeWithText("还在读《Back 测试书》").assertExists()
            rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.waitForIdle()
            rule.runOnIdle {
                assertFalse("First Back must close the prompt, not leave Session", left)
                assertEquals("Back 草稿", vm.draftContent)
                assertEquals("42", vm.draftPage)
            }
            rule.waitUntil(5_000) { actions.calls.contains("dismiss") }
            assertEquals(SessionSegmentType.RECOVERY, actions.focusStatus.value.type)
            rule.onNodeWithText("还在读《Back 测试书》").assertDoesNotExist()
            rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.waitUntil(5_000) { left }
            val persisted = runBlocking { container.noteRepository.observeForSession(session.id).first() }
            assertEquals("Back 草稿", persisted.single().content)
            assertEquals(42, persisted.single().pageNumber)
        } finally {
            rule.activityRule.scenario.close()
            vm.viewModelScope.cancel()
            container.close()
        }
    }
    @Test fun allFocusStatesArePlainAndRecoveryHasNoCountdown() {
        val status = mutableStateOf(FocusStatusUiModel(type = SessionSegmentType.FOCUS))
        rule.setContent { MirraTheme { SessionFocusContent(status.value, {}, {}, {}, {}) } }
        rule.onNodeWithText("阅读中").assertExists()
        rule.runOnIdle { status.value = status.value.copy(type = SessionSegmentType.BREAK, remainingMillis = 65_000) }
        rule.onNodeWithText("休息中 · 1:05").assertExists()
        rule.onNodeWithText("提前结束休息").assertExists()
        rule.runOnIdle { status.value = status.value.copy(type = SessionSegmentType.TEMPORARY_ALLOWANCE, canExtend = true) }
        rule.onNodeWithText("临时使用 · 1:05").assertExists()
        rule.onNodeWithText("延长 2 分钟").assertExists()
        rule.runOnIdle { status.value = status.value.copy(type = SessionSegmentType.RECOVERY) }
        rule.onNodeWithText("正在回到学习").assertExists()
        rule.onNodeWithText("1:05", substring = true).assertDoesNotExist()
        rule.runOnIdle { status.value = status.value.copy(type = SessionSegmentType.UNMONITORED, coverage = MonitoringCoverage.PARTIAL) }
        rule.onNodeWithText("监测已中断").assertExists()
        rule.runOnIdle { status.value = status.value.copy(coverage = MonitoringCoverage.NONE) }
        rule.onNodeWithText("本次未开启分心监测").assertExists()
    }
    @Test fun promptKeepsEscapeActionsAvailableDuringWaitAndReadsProjectedMinutes() {
        val prompt = mutableStateOf(InterventionUiModel("s", "p", "e", "seg", "risk", 15_000, 5_000,
            reason = AllowanceReason.REPLY, allowanceOptions = AllowanceReason.entries.map { AllowanceOption(it, 420_000) }))
        var returns = 0
        rule.setContent { MirraTheme { InterventionContent("书", prompt.value, true, {}, {}, { returns++ }, {}, {}, {}) } }
        rule.onNodeWithText("回复消息 · 7 分钟", substring = true).assertExists()
        rule.onNodeWithText("查资料 · 7 分钟").assertExists()
        rule.onNodeWithText("临时处理事情 · 7 分钟").assertExists()
        rule.onNodeWithText("随便看看 · 7 分钟").assertExists()
        rule.onNodeWithText("再等 5 秒").assertIsNotEnabled()
        rule.onNodeWithText("回到学习").performClick()
        assertEquals(1, returns)
        rule.onNodeWithText("结束本次学习").assertIsEnabled()
        rule.onNodeWithText("关闭").assertIsEnabled()
        rule.runOnIdle { prompt.value = prompt.value.copy(remainingWaitMillis = 15_000) }
        rule.onNodeWithText("再等 15 秒").assertIsNotEnabled()
        rule.runOnIdle { prompt.value = prompt.value.copy(remainingWaitMillis = 0) }
        rule.onNodeWithText("开始临时使用").assertIsEnabled()
    }
    @Test fun normalPromptHasFourActionsAndDismissIsNotRecovery() {
        var dismissed = 0
        val prompt = InterventionUiModel("s", "p", "e", "seg", "risk", 0, 0)
        rule.setContent { MirraTheme { InterventionContent("书", prompt, false, {}, {}, {}, {}, {}, { dismissed++ }) } }
        rule.onNodeWithText("还在读《书》").assertExists()
        rule.onNodeWithText("回到学习").assertIsEnabled()
        rule.onNodeWithText("临时使用").assertIsEnabled()
        rule.onNodeWithText("结束本次学习").assertIsEnabled()
        rule.onNodeWithText("关闭").performClick()
        assertEquals(1, dismissed)
    }
    @Test fun narrowLargeFontPanelKeepsEscapeActionsReachableByScrolling() {
        val prompt = InterventionUiModel("s", "p", "e", "seg", "risk", 15_000, 15_000,
            allowanceOptions = AllowanceReason.entries.map { AllowanceOption(it, 300_000) })
        rule.setContent { MirraTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        InterventionContent("很长的书名，用于验证大字体阅读", prompt, true, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        } }
        rule.onNodeWithText("关闭").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("回到学习").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("结束本次学习").performScrollTo().assertIsDisplayed()
    }
    @Test fun panelBackKeepsDraftAndNavigationAndLifecycleRevokesEvidence() {
        val container = TestAppContainer(ApplicationProvider.getApplicationContext())
        val session = runBlocking {
            val item = container.learningItemRepository.create("测试书", 100, firstAction = "把书放到桌上，翻到上次阅读的位置")
            val intent = container.studyWorkflowRepository.createIntent(item.id)
            container.studyWorkflowRepository.startSession(intent.id, 1)
        }
        val actions = container.focusSessionActions
        actions.focusStatus.value = FocusStatusUiModel(session.id, "seg", SessionSegmentType.DISTRACTION, MonitoringCoverage.FULL)
        actions.intervention.value = InterventionUiModel(session.id, "p", "e", "seg", "risk", 5_000, 5_000,
            allowanceOptions = AllowanceReason.entries.map { AllowanceOption(it, 300_000) })
        val vm = SessionViewModel(session.id, container.studyWorkflowRepository, container.noteRepository,
            container.sessionManager, focusActions = actions, learningItems = container.learningItemRepository,
            // Compose v2 drives effect delays with its test clock, not real elapsedRealtime.
            clockSample = { ClockSample(session.startedAt + rule.mainClock.currentTime, rule.mainClock.currentTime) })
        var left = false
        rule.setContent { MirraTheme { SessionScreen(vm, {}, {}, { left = true }) } }
        rule.waitUntil(5_000) { vm.session.value != null }
        rule.runOnIdle { vm.changeContent("草稿仍在"); vm.changePage("42") }
        rule.onNodeWithText("临时使用").performScrollTo().performClick()
        rule.onNodeWithText("回复消息 · 5 分钟").performClick()
        rule.waitUntil(5_000) { actions.intervention.value?.reason == AllowanceReason.REPLY }
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("再等 5 秒").assertDoesNotExist()
        rule.runOnIdle { assertFalse(left); assertEquals("草稿仍在", vm.draftContent); assertEquals("42", vm.draftPage) }
        rule.waitUntil(5_000) { actions.calls.contains("visible:false") }
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.waitUntil(5_000) { actions.calls.contains("evidence:false:false") }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil(5_000) { actions.calls.count { it == "refresh" } >= 2 }
        // Observe new periodic samples after resume; incidental lifecycle/window callbacks are
        // not a substitute for the one-second loop. Keep the original interval requirement.
        rule.mainClock.autoAdvance = false
        val beforeTick = rule.runOnIdle { actions.evidenceSamples.size }
        rule.mainClock.advanceTimeBy(2_100)
        rule.waitUntil(5_000) {
            actions.evidenceSamples.drop(beforeTick).zipWithNext().any { (a, b) ->
                b.elapsedNowMillis - a.elapsedNowMillis >= 900
            }
        }
        rule.mainClock.autoAdvance = true
        val persisted = runBlocking { container.noteRepository.observeForSession(session.id).first() }
        assertEquals("草稿仍在", persisted.single().content)
        assertEquals(42, persisted.single().pageNumber)
        rule.activityRule.scenario.close()
        vm.viewModelScope.cancel()
        container.close()
    }
}
