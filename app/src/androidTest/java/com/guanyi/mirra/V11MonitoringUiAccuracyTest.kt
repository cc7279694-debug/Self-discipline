package com.guanyi.mirra

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.domain.monitoring.FocusStatusUiModel
import com.guanyi.mirra.feature.profile.DefaultDndUserActions
import com.guanyi.mirra.feature.profile.ProfileScreen
import com.guanyi.mirra.feature.profile.ProfileViewModel
import com.guanyi.mirra.feature.session.SessionScreen
import com.guanyi.mirra.feature.session.SessionViewModel
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Consumer-visible regressions; these use the existing public UI while V1.1 is being added. */
class V11MonitoringUiAccuracyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var container: TestAppContainer
    private val viewModels = mutableListOf<ViewModel>()

    @Before fun setUp() {
        container = TestAppContainer(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() {
        rule.activityRule.scenario.close()
        viewModels.forEach { it.viewModelScope.cancel() }
        container.close()
    }

    @Test fun grantedDndPermissionIsNotPresentedAsMonitoringReady() {
        val actions = DefaultDndUserActions(
            preferences = container.appPreferencesRepository,
            activeRecordProvider = { null },
            pendingReleaseProvider = { false },
            applyDnd = {},
            reconcileDnd = {},
            policyAccessProvider = { true },
            apiLevel = 37,
            settingsIntentFactory = { Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS") },
        )
        runBlocking { actions.setEnabled(true) }
        val vm = profileViewModel(actions)
        rule.setContent { MirraTheme { ProfileScreen(vm) } }

        rule.onNodeWithText("已就绪").assertDoesNotExist()
        rule.onNodeWithText("已授权").assertExists()
    }

    @Test fun selectedRiskAppNamesAreVisibleWithoutOpeningAnEditor() {
        runBlocking { container.focusRepository.replaceRiskApp("v11.test.shortvideo", "短视频测试") }
        val vm = profileViewModel()
        rule.setContent {
            MirraTheme { ProfileScreen(vm, riskRepository = container.focusRepository) }
        }

        rule.onNodeWithText("短视频测试").assertExists()
        rule.onNodeWithText("选择风险 App").assertDoesNotExist()
    }

    @Test fun oldSessionMonitoringLossDoesNotDescribeTheCurrentSession() {
        val vm = sessionViewModel { currentId ->
            FocusStatusUiModel(
                sessionId = "old-$currentId",
                segmentId = "old-segment",
                type = SessionSegmentType.UNMONITORED,
                coverage = MonitoringCoverage.PARTIAL,
            )
        }
        rule.setContent { MirraTheme { SessionScreen(vm, {}, {}, {}) } }
        rule.waitUntil(5_000) { vm.session.value != null && vm.isReadingQualified }

        rule.onNodeWithText("监测已中断").assertDoesNotExist()
    }

    @Test fun currentUnmonitoredSessionOffersProtectionRepair() {
        var repairRequests = 0
        val vm = sessionViewModel { currentId ->
            FocusStatusUiModel(currentId, "current-segment", SessionSegmentType.UNMONITORED, MonitoringCoverage.NONE)
        }
        rule.setContent { MirraTheme {
            SessionScreen(vm, {}, {}, {}, onOpenLearningProtection = { repairRequests++ })
        } }
        rule.waitUntil(5_000) { vm.session.value != null && vm.isReadingQualified }

        rule.onNodeWithText("本次未开启分心监测").assertExists()
        rule.onNodeWithText("检查学习保护").performClick()
        assertEquals(1, repairRequests)
    }

    @Test fun currentInterruptedSessionOffersProtectionRepair() {
        var repairRequests = 0
        val vm = sessionViewModel { currentId ->
            FocusStatusUiModel(currentId, "current-segment", SessionSegmentType.UNMONITORED, MonitoringCoverage.PARTIAL)
        }
        rule.setContent { MirraTheme {
            SessionScreen(vm, {}, {}, {}, onOpenLearningProtection = { repairRequests++ })
        } }
        rule.waitUntil(5_000) { vm.session.value != null && vm.isReadingQualified }

        rule.onNodeWithText("监测已中断").assertExists()
        rule.onNodeWithText("检查学习保护").performClick()
        assertEquals(1, repairRequests)
    }

    private fun profileViewModel(
        actions: com.guanyi.mirra.feature.profile.DndUserActions = container.dndUserActions,
    ) = ProfileViewModel(
        container.readingAnalyticsRepository,
        container.readingAnalyticsService,
        container.analyticsTimeProvider,
        actions,
    ).also { viewModels += it }

    private fun sessionViewModel(status: (String) -> FocusStatusUiModel): SessionViewModel {
        val session = runBlocking {
            val item = container.learningItemRepository.create(
                "监测状态测试书", 100, firstAction = "把书放到桌上，翻到上次阅读的位置",
            )
            val intent = container.studyWorkflowRepository.createIntent(item.id)
            container.studyWorkflowRepository.startSession(intent.id, 1)
        }
        container.focusSessionActions.focusStatus.value = status(session.id)
        return SessionViewModel(
            session.id,
            container.studyWorkflowRepository,
            container.noteRepository,
            container.sessionManager,
            focusActions = container.focusSessionActions,
        ).also { viewModels += it }
    }
}
