package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.model.GlobalReadingHistoryRow
import com.guanyi.mirra.feature.profile.GlobalReadingHistoryContent
import com.guanyi.mirra.feature.profile.GlobalReadingHistoryUiState
import com.guanyi.mirra.ui.theme.MirraTheme
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlobalReadingHistoryUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun rowsShowLightweightHistoryAndOpenTheSelectedExistingRecord() {
        var selected: String? = null
        var exits = 0
        rule.setContent { MirraTheme {
            GlobalReadingHistoryContent(GlobalReadingHistoryUiState(records = listOf(row()), isLoading = false), ZoneOffset.UTC,
                {}, {}, { selected = it }, { exits++ })
        } }
        rule.onNodeWithText("测试书").assertExists()
        rule.onNodeWithText("2023-11-14 · 23:11").assertExists()
        rule.onNodeWithText("40 → 62 页 · 58 分钟").assertExists()
        rule.onNodeWithTag("history-row-one").performClick()
        assertEquals("one", selected)
        rule.onNodeWithText("返回").performClick(); assertEquals(1, exits)
        for (text in listOf("有效专注", "速度", "评分", "结束本次阅读"))
            rule.onNodeWithText(text, substring = true).assertDoesNotExist()
    }

    @Test fun abnormalAndIncompleteHistoryRemainVisibleWithoutInventingMetrics() {
        rule.setContent { MirraTheme {
            GlobalReadingHistoryContent(GlobalReadingHistoryUiState(records = listOf(
                row().copy(endType = SessionEndType.ABNORMAL, monitoringStatus = MonitoringCoverage.PARTIAL)),
                isLoading = false), ZoneOffset.UTC, {}, {}, {}, {})
        } }
        rule.onNodeWithText("异常结束 · 监测不完整").assertExists()
        rule.onNodeWithText("40 → 62 页 · 58 分钟").assertExists()
    }

    @Test fun loadingEmptyAndErrorStatesStayDistinctAndRetryIsReachable() {
        var state by mutableStateOf(GlobalReadingHistoryUiState())
        var retries = 0
        rule.setContent { MirraTheme {
            GlobalReadingHistoryContent(state, ZoneOffset.UTC, {}, { retries++ }, {}, {})
        } }
        rule.onNodeWithText("正在读取阅读记录…").assertExists()
        rule.onNodeWithText("还没有阅读记录").assertDoesNotExist()
        rule.runOnIdle { state = GlobalReadingHistoryUiState(isLoading = false, initialError = true) }
        rule.onNodeWithText("暂时无法读取阅读记录").assertExists()
        rule.onNodeWithText("重试").performClick(); assertEquals(1, retries)
        rule.runOnIdle { state = GlobalReadingHistoryUiState(isLoading = false) }
        rule.onNodeWithText("还没有阅读记录").assertExists()
        rule.onNodeWithText("重试").assertDoesNotExist()
    }

    @Test fun pageErrorKeepsRowsAndOffersRetryForOnlyTheNextPage() {
        var retries = 0
        rule.setContent { MirraTheme {
            GlobalReadingHistoryContent(GlobalReadingHistoryUiState(records = listOf(row()), isLoading = false,
                pageError = true, hasMore = true), ZoneOffset.UTC, {}, { retries++ }, {}, {})
        } }
        rule.onNodeWithText("测试书").assertExists()
        rule.onNodeWithText("重试加载更多").performScrollTo().performClick()
        assertEquals(1, retries)
        rule.onNodeWithText("加载更多").assertDoesNotExist()
    }

    @Test fun historyReachableAt320dpNormalFont() = reachable(320, 1f)
    @Test fun historyReachableAt320dpDoubleFont() = reachable(320, 2f)
    @Test fun historyReachableAt360dpNormalFont() = reachable(360, 1f)
    @Test fun historyReachableAt360dpDoubleFont() = reachable(360, 2f)
    @Test fun historyReachableAt411dpNormalFont() = reachable(411, 1f)
    @Test fun historyReachableAt411dpDoubleFont() = reachable(411, 2f)

    private fun reachable(width: Int, fontScale: Float) {
        var pages = 0
        var selections = 0
        var exits = 0
        val density = rule.activity.resources.displayMetrics.density
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) { MirraTheme {
            Box(Modifier.width(width.dp).height(560.dp).testTag("history-test-viewport")) {
                GlobalReadingHistoryContent(GlobalReadingHistoryUiState(records = listOf(row().copy(
                    learningItemName = "一本名字很长的受控测试书，用于验证大字模式下完整换行和阅读记录入口的可达性",
                    endType = SessionEndType.ABNORMAL, monitoringStatus = null)), isLoading = false, hasMore = true),
                    ZoneOffset.UTC, { pages++ }, {}, { selections++ }, { exits++ })
            }
        } } }
        assertEquals(width.toFloat(), rule.onNodeWithTag("history-test-viewport").fetchSemanticsNode()
            .boundsInRoot.width / density, 0.5f)
        val row = rule.onNodeWithTag("history-row-one").performScrollTo()
        row.assertIsDisplayed()
        assertTrue(row.fetchSemanticsNode().boundsInRoot.height / density >= 47.9f)
        row.performClick(); assertEquals(1, selections)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("加载更多"))
        val more = rule.onNode(hasText("加载更多") and hasClickAction())
        more.assertIsDisplayed(); assertTrue(more.fetchSemanticsNode().boundsInRoot.height / density >= 47.9f)
        more.performClick(); assertEquals(1, pages)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("返回"))
        rule.onNodeWithText("返回").performClick(); assertEquals(1, exits)
    }

    private fun row() = GlobalReadingHistoryRow("one", "测试书", 1_700_000_000_000,
        1_700_003_480_000, 40, 62, SessionEndType.NORMAL, MonitoringCoverage.FULL)
}
