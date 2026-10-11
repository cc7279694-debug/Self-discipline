package com.guanyi.mirra.feature.profile

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.TestAppContainer
import com.guanyi.mirra.data.repository.FocusRepository
import com.guanyi.mirra.platform.focus.CapabilityStatus
import com.guanyi.mirra.platform.focus.MonitoringPlatformRuntime
import com.guanyi.mirra.platform.focus.ServicePhase
import com.guanyi.mirra.ui.theme.MirraTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LearningProtectionScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var container: TestAppContainer
    private var profile: ProfileViewModel? = null
    private var isolatedRuntime: MonitoringPlatformRuntime? = null

    @Before fun setUp() {
        container = TestAppContainer(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() {
        rule.activityRule.scenario.close()
        profile?.viewModelScope?.cancel()
        isolatedRuntime?.let { runBlocking { it.closeMaintenanceOwners() } }
        container.close()
    }

    @Test fun mineUsesOneProtectionEntryInsteadOfInlineSettings() {
        var opened = 0
        val vm = ProfileViewModel(container.readingAnalyticsRepository, container.readingAnalyticsService,
            container.analyticsTimeProvider, container.dndUserActions).also { profile = it }
        rule.setContent { MirraTheme {
            ProfileScreen(vm, riskRepository = container.focusRepository, onOpenLearningProtection = { opened++ })
        } }

        rule.onNodeWithText("学习时自动开启勿扰").assertDoesNotExist()
        rule.onNodeWithText("编辑风险 App").assertDoesNotExist()
        rule.onNodeWithText("学习保护").performScrollTo().performClick()
        assertEquals(1, opened)
        captureIfRequested("mine-protection-entry")
    }

    @Test fun protectionPageShowsSavedRiskNamesBeforeOpeningEditor() {
        runBlocking { container.focusRepository.replaceRiskApp("v11.test.shortvideo", "短视频测试") }
        renderPage()
        waitForText("短视频测试")

        captureIfRequested("learning-protection-top")
        rule.onNodeWithText("短视频测试").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("修改影响下一次学习，本次仍使用开始时的选择。").assertExists()
        rule.onNodeWithText("选择风险 App").assertDoesNotExist()
        captureIfRequested("learning-protection-risks")
    }

    @Test fun permissionsAndTwoNotificationChannelsStayDistinctWhenPreferencesAreOff() {
        val permissions = LearningProtectionPermissionsUiState(usage = CapabilityStatus.AVAILABLE,
            dndGranted = true, overlayGranted = false, notificationGranted = true,
            monitoringNotificationUsable = false, interventionNotificationUsable = true)
        rule.setContent { MirraTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LearningProtectionStatusContent(LearningProtectionMonitoringUiState(), permissions, {}, {}, {}, {}, {}, {})
                DndSettingsContent(DndSettingsUiState(enabled = false, policyAccessGranted = true), {}, {}, {}, {},
                    showHeading = false)
            }
        } }

        for (tag in listOf("permission-usage", "permission-dnd", "permission-overlay", "permission-notification")) {
            rule.onNodeWithTag(tag).assertExists()
        }
        rule.onNode(hasText("已授权") and hasAnyAncestor(hasTestTag("permission-dnd"))).assertExists()
        rule.onNode(hasText("已授权") and hasAnyAncestor(hasTestTag("permission-notification"))).assertExists()
        rule.onNodeWithTag("monitoring-notification-channel").assertTextEquals("监测状态通知：尚不可用，请检查通知设置")
        rule.onNodeWithTag("intervention-notification-channel").assertTextEquals("学习提醒通知：可用")
        rule.onNodeWithTag("learning-monitoring-service").assertTextEquals("监测服务未用于活动学习")
        rule.onNodeWithTag("dnd-toggle").assertIsOff()
    }

    @Test fun narrowLargeFontKeepsEveryDirectPermissionRepairReachable() {
        val opened = mutableListOf<String>()
        rule.setContent { MirraTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        LearningProtectionStatusContent(LearningProtectionMonitoringUiState(),
                            LearningProtectionPermissionsUiState(usage = CapabilityStatus.NEEDS_USER_ACTION,
                                dndGranted = false, overlayGranted = false, notificationGranted = false),
                            { opened += "usage" }, { opened += "dnd" }, { opened += "overlay" },
                            { opened += "notification" }, { opened += "monitoring-channel" }, { opened += "intervention-channel" })
                    }
                }
            }
        } }
        for (label in listOf("打开使用情况设置", "打开勿扰设置", "打开悬浮设置", "检查通知权限",
            "监测通知设置", "学习提醒通知设置")) {
            rule.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
        }
        assertEquals(listOf("usage", "dnd", "overlay", "notification", "monitoring-channel", "intervention-channel"), opened)
        captureIfRequested("learning-protection-large-font")
    }

    @Test fun failedRiskSaveShowsFeedbackAndKeepsThePersistedSelection() {
        runBlocking { container.focusRepository.replaceRiskApp("v11.test.shortvideo", "短视频测试") }
        val failing = object : FocusRepository by container.focusRepository {
            override suspend fun removeRiskApp(packageName: String) { error("controlled risk save failure") }
        }
        renderPage(failing)
        waitForText("短视频测试")
        rule.onNodeWithText("编辑风险 App").performScrollTo().performClick()
        rule.onNodeWithTag("risk-editor-v11.test.shortvideo")
            .performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
        waitForText("风险 App 保存失败，请重试")

        assertEquals("v11.test.shortvideo", runBlocking { container.focusRepository.observeRiskApps().first() }.single().packageName)
        rule.onNodeWithText("完成").performClick()
        rule.onNodeWithText("风险 App 保存失败，请重试").assertExists()
        rule.onNodeWithText("短视频测试").assertExists()
    }

    @Test fun removingSelectedAppPersistsEvenIfItIsNoLongerInTheLauncherCatalog() {
        runBlocking { container.focusRepository.replaceRiskApp("v11.test.uninstalled", "已移除的测试 App") }
        renderPage()
        waitForText("已移除的测试 App")
        rule.onNodeWithText("编辑风险 App").performScrollTo().performClick()
        rule.onNodeWithTag("risk-editor-v11.test.uninstalled")
            .performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
        rule.waitUntil(5_000) {
            runBlocking { container.focusRepository.observeRiskApps().first() }.isEmpty()
        }
        rule.onNodeWithText("完成").performClick()
        rule.onNodeWithText("已移除的测试 App").assertDoesNotExist()
        rule.onNodeWithText("还没有选择风险 App").assertExists()
    }

    @Test fun openingProtectionDoesNotStartAServiceOrCreateMonitoringEvidence() {
        val runtime = MonitoringPlatformRuntime(ApplicationProvider.getApplicationContext()).also { isolatedRuntime = it }
        renderPage(runtime = runtime)
        rule.waitForIdle()

        assertEquals(ServicePhase.STOPPED, runtime.lifecycle.state.value.phase)
        assertFalse(runtime.capabilities.state.value.monitor.running)
        assertEquals(0L, runtime.capabilities.state.value.monitor.queryGeneration)
        assertNull(runtime.capabilities.state.value.monitor.lastSuccessfulQueryElapsed)
        assertNull(runtime.binding)
        rule.onNodeWithTag("learning-monitoring-coverage").assertTextEquals("当前没有可确认的活动学习")
    }

    private fun renderPage(repository: FocusRepository = container.focusRepository, runtime: MonitoringPlatformRuntime? = null) {
        rule.setContent { MirraTheme {
            LearningProtectionScreen(runtime, container.studyWorkflowRepository, repository,
                container.dndUserActions, null, {})
        } }
    }

    private fun waitForText(text: String) {
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText(text).assertExists(); true }.getOrDefault(false) }
    }

    /** Opt-in controlled fixtures only; no installed user records or settings are captured. */
    private fun captureIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("v11CaptureScreenshots") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "v11-monitoring-ui-captures")
        assertTrue(directory.isDirectory || directory.mkdirs())
        FileOutputStream(File(directory, "$name.png")).use { output ->
            assertTrue(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
