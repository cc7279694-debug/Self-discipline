package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.domain.intervention.*
import com.guanyi.mirra.domain.monitoring.*
import com.guanyi.mirra.feature.session.*
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExternalInterventionSessionUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun scenario(action: InterventionNavigationAction, stale: Boolean = false,
        assertions: (TestAppContainer, SessionViewModel) -> Unit) {
        val c = TestAppContainer(ApplicationProvider.getApplicationContext())
        val session = runBlocking {
            val item = c.learningItemRepository.create("外部入口测试书", 100)
            val intent = c.studyWorkflowRepository.createIntent(item.id)
            c.studyWorkflowRepository.startSession(intent.id, 1)
        }
        val actions = c.focusSessionActions
        actions.focusStatus.value = FocusStatusUiModel(session.id, "seg", SessionSegmentType.RECOVERY, MonitoringCoverage.FULL)
        actions.intervention.value = InterventionUiModel(session.id, "token", "token", "seg", "risk", 0, 0,
            allowanceOptions = AllowanceReason.entries.map { AllowanceOption(it, 180_000) })
        val vm = SessionViewModel(session.id, c.studyWorkflowRepository, c.noteRepository, c.sessionManager,
            focusActions = actions, learningItems = c.learningItemRepository)
        try {
            rule.setContent { MirraTheme { SessionScreen(vm, {}, {}, {},
                InterventionNavigationRequest(session.id, if (stale) "old" else "token", action)) } }
            rule.waitUntil(5_000) { vm.session.value != null }
            assertions(c, vm)
            assertEquals(SessionSegmentType.RECOVERY, actions.focusStatus.value.type)
            assertTrue(actions.calls.none { it.startsWith("grant") })
            assertNotNull(runBlocking { c.database.sessionDao().getActive() })
        } finally { rule.activityRule.scenario.close(); vm.viewModelScope.cancel(); c.close() }
    }
    @Test fun allowanceOpensExistingReasonsWithoutGranting() = scenario(InterventionNavigationAction.OPEN_ALLOWANCE) { _, _ ->
        rule.onNodeWithText("回复消息 · 3 分钟").assertExists()
        rule.onNodeWithText("开始临时使用").assertIsNotEnabled()
    }
    @Test fun finishOpensSameConfirmationWithoutEndingOrGranting() = scenario(InterventionNavigationAction.OPEN_FINISH) { _, _ ->
        rule.onNodeWithText("结束本次阅读？").assertIsDisplayed()
        rule.onNodeWithText("继续阅读").performClick()
        rule.onNodeWithText("结束本次阅读？").assertDoesNotExist()
    }
    @Test fun staleRequestDoesNotOpenReasons() = scenario(InterventionNavigationAction.OPEN_ALLOWANCE, true) { _, _ ->
        rule.onNodeWithText("回复消息 · 3 分钟").assertDoesNotExist()
        rule.onNodeWithText("还在读《外部入口测试书》").assertExists()
    }
}
