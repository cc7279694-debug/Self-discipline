package com.guanyi.mirra.feature.profile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.TestAppContainer
import com.guanyi.mirra.ui.theme.MirraTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DndSettingsProfileTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var container: TestAppContainer

    @Before fun setUp() {
        container = TestAppContainer(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() {
        composeRule.activityRule.scenario.close()
        container.close()
    }

    @Test fun profileShowsLearningProtectionAndPersistsToggle() {
        val viewModel = ProfileViewModel(
            container.readingAnalyticsRepository,
            container.readingAnalyticsService,
            container.analyticsTimeProvider,
            container.dndUserActions,
        )
        composeRule.setContent { MirraTheme { ProfileScreen(viewModel) } }

        composeRule.onNodeWithText("学习时自动开启勿扰").assertExists()
        composeRule.onNodeWithText("关闭").assertExists()
        composeRule.onNodeWithTag("dnd-toggle").performClick()
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithText("需要系统授权").assertExists(); true }.getOrDefault(false)
        }
    }
}
