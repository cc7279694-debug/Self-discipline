package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.data.repository.TrendsRepository
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.AnalyticsTimeProvider
import com.guanyi.mirra.domain.trends.*
import com.guanyi.mirra.feature.profile.ProfileSummaryContent
import com.guanyi.mirra.feature.profile.ProfileUiState
import com.guanyi.mirra.feature.profile.RiskAppsSettings
import com.guanyi.mirra.feature.profile.TrendsScreen
import com.guanyi.mirra.feature.profile.TrendsViewModel
import com.guanyi.mirra.ui.theme.MirraTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Counts must be readable before a percentage; missing facts must never become zero. */
class V111StartCountsPresentationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val time = AnalyticsTimeContext(Instant.parse("2026-10-07T04:00:00Z"), ZoneId.of("Asia/Shanghai"))

    @Test fun oneSuccessfulStartShowsCountsBeforeSecondaryPercentageInTrends() {
        val vm = trendsViewModel(snapshot(1, 1, 1.0))
        try {
            rule.setContent { MirraTheme { TrendsScreen(vm, {}) } }
            rule.waitUntil(5_000) { vm.uiState.value.snapshot != null }
            rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("trend-start-count"))
            val count = rule.onNodeWithTag("trend-start-count").assertTextEquals("成功开始 1 次").fetchSemanticsNode()
            rule.onNodeWithTag("trend-resolved-count").assertTextEquals("已结束计划 1 次")
            rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("trend-conversion"))
            val rate = rule.onNodeWithTag("trend-conversion").assertTextEquals("1 / 1 · 100%").fetchSemanticsNode()
            assertTrue("Start count must precede its percentage", count.boundsInRoot.top < rate.boundsInRoot.top)
            assertTrue("Count should be the primary text", count.boundsInRoot.height > rate.boundsInRoot.height)
        } finally {
            rule.activityRule.scenario.close()
            vm.viewModelScope.cancel()
        }
    }

    @Test fun emptyResolvedSampleShowsTrueZeroCountsWithoutInventingPercentageInTrends() {
        val vm = trendsViewModel(snapshot(0, 0, null))
        try {
            rule.setContent { MirraTheme { TrendsScreen(vm, {}) } }
            rule.waitUntil(5_000) { vm.uiState.value.snapshot != null }
            rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("trend-start-count"))
            rule.onNodeWithTag("trend-start-count").assertTextEquals("成功开始 0 次")
            rule.onNodeWithTag("trend-resolved-count").assertTextEquals("已结束计划 0 次")
            rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("trend-conversion"))
            rule.onNodeWithTag("trend-conversion").assertTextEquals("数据积累中")
            rule.onNodeWithText("0%").assertDoesNotExist()
        } finally {
            rule.activityRule.scenario.close()
            vm.viewModelScope.cancel()
        }
    }

    @Test fun weeklySummaryShowsOneSuccessAndResolvedCountBeforePercentage() {
        showProfile(ProfileUiState(trends = snapshot(1, 1, 1.0)))
        rule.onNodeWithTag("weekly-start-count").assertTextEquals("成功开始 1 次").performScrollTo()
        rule.onNodeWithTag("weekly-resolved-count").assertTextEquals("已结束计划 1 次")
        val count = rule.onNodeWithTag("weekly-start-count").fetchSemanticsNode()
        val rate = rule.onNodeWithTag("weekly-start").assertTextEquals("100%").fetchSemanticsNode()
        assertTrue(count.boundsInRoot.top < rate.boundsInRoot.top)
        assertTrue(count.boundsInRoot.height > rate.boundsInRoot.height)
    }

    @Test fun weeklySummaryKeepsKnownZeroCountsAndUnknownPercentageDistinct() {
        showProfile(ProfileUiState(trends = snapshot(0, 0, null)))
        rule.onNodeWithTag("weekly-start-count").assertTextEquals("成功开始 0 次").performScrollTo()
        rule.onNodeWithTag("weekly-resolved-count").assertTextEquals("已结束计划 0 次")
        rule.onNodeWithTag("weekly-start").assertTextEquals("数据积累中")
        rule.onNodeWithText("0%").assertDoesNotExist()
    }

    @Test fun unavailableWeeklyStartSourceDoesNotInventZeroCounts() {
        showProfile(ProfileUiState(trendsError = "暂时无法读取启动与恢复记录"))
        rule.onNodeWithTag("weekly-start-count").assertTextEquals("暂无可确认数据").performScrollTo()
        rule.onNodeWithText("成功开始 0 次").assertDoesNotExist()
        rule.onNodeWithText("已结束计划 0 次").assertDoesNotExist()
        rule.onNodeWithTag("weekly-start").assertTextEquals("暂无可确认数据")
    }

    @Test fun savedRiskListExplainsPersistenceBeforeOpeningEditor() {
        val container = TestAppContainer(ApplicationProvider.getApplicationContext())
        try {
            runBlocking { container.focusRepository.replaceRiskApp("v111.presentation.video", "测试短视频") }
            rule.setContent { MirraTheme { RiskAppsSettings(container.focusRepository) } }
            rule.waitUntil(5_000) { rule.onAllNodesWithText("测试短视频").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("测试短视频").assertIsDisplayed()
            rule.onNodeWithText("名单长期保存，不需要每次重新设置。").assertIsDisplayed()
            rule.onNodeWithText("修改影响下一次学习，本次仍使用开始时的选择。").assertExists()
        } finally {
            rule.activityRule.scenario.close()
            container.close()
        }
    }

    private fun showProfile(state: ProfileUiState) {
        rule.setContent { MirraTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { ProfileSummaryContent(state) }
        } }
    }

    private fun trendsViewModel(snapshot: TrendsSnapshot) = TrendsViewModel(object : TrendsRepository {
        override suspend fun load(range: TrendsRange, time: AnalyticsTimeContext) = snapshot.copy(range = range)
    }, AnalyticsTimeProvider(Clock.fixed(time.now, ZoneId.of("UTC"))) { time.zoneId })

    private fun snapshot(converted: Long, resolved: Long, rate: Double?) = TrendsSnapshot(
        TrendsRange.SEVEN_DAYS, time, TrendsWindows(TrendsWindow(null, time.now.toEpochMilli()), null),
        TrendsPeriod(
            StartTrends(converted, resolved - converted, 0, 0, TrendFraction(converted, resolved, rate),
                converted, null, 0, 0, converted, 0, null),
            MaintainTrends(0, 0, 0, null, null, 0, null, false),
            RecoverTrends(0, 0, 0, 0, 0, 0, TrendFraction(0, 0, null), null),
        ), null, null,
    )
}
