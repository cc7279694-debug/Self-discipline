package com.guanyi.mirra.feature.profile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.guanyi.mirra.domain.intervention.DeliveryCapabilities
import com.guanyi.mirra.ui.theme.MirraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CrossAppInterventionSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun deniedChannelsExposeVisiblePermissionActionsAndNextSessionHint() {
        var toggled: Boolean? = null
        var overlay = 0; var notification = 0; var settings = 0
        rule.setContent { MirraTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            CrossAppInterventionSettingsContent(CrossAppInterventionSettingsState(true,
                DeliveryCapabilities(37, false, false, false), activeSession = true),
                { toggled = it }, { overlay++ }, { notification++ }, { settings++ })
        } } }
        rule.onNodeWithText("仅回到 Mirra 后提示").assertExists()
        rule.onNodeWithText("修改将在下一次学习时生效").assertExists()
        rule.onNodeWithText("允许悬浮显示").performClick()
        rule.onNodeWithText("允许通知").performClick()
        rule.onNodeWithText("打开通知设置").performClick()
        rule.onNodeWithTag("cross-app-toggle").performClick()
        assertEquals(false, toggled); assertEquals(1, overlay); assertEquals(1, notification); assertEquals(1, settings)
    }
    @Test fun defaultOffDoesNotBuildPermissionWall() {
        rule.setContent { MirraTheme { CrossAppInterventionSettingsContent(CrossAppInterventionSettingsState(), {}, {}, {}, {}) } }
        rule.onNodeWithText("关闭").assertExists()
        rule.onNodeWithText("允许通知").assertDoesNotExist()
        rule.onNodeWithText("允许悬浮显示").assertDoesNotExist()
    }
    @Test fun oldApiOnlyShowsNotificationSettingsAndNeverLegacyOverlay() {
        rule.setContent { MirraTheme { CrossAppInterventionSettingsContent(
            CrossAppInterventionSettingsState(true, DeliveryCapabilities(25, true, true, true)), {}, {}, {}, {}) } }
        rule.onNodeWithText("将使用通知提醒").assertExists()
        rule.onNodeWithText("允许悬浮显示").assertDoesNotExist()
        rule.onNodeWithText("允许通知").assertDoesNotExist()
        rule.onNodeWithText("打开通知设置").assertExists()
    }
}
