package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.trends.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.guanyi.mirra.feature.profile.ProfileSummaryContent
import com.guanyi.mirra.feature.profile.ProfileUiState
import com.guanyi.mirra.ui.theme.MirraTheme
import org.junit.Rule
import org.junit.Test

class WeeklyOverviewUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun mineExposesWeeklyOverviewAndSecondaryAnalysisWithoutNavigationFirst() {
        show(ProfileUiState(sessionCount = 2, totalDurationText = "30 分钟"))
        rule.onNodeWithText("本周学习").assertIsDisplayed()
        rule.onNodeWithText("30 分钟").assertIsDisplayed()
        rule.onNodeWithText("详细分析").assertHasClickAction()
        rule.onNodeWithText("开始转化率").assertIsDisplayed()
        rule.onNodeWithText("分心后恢复").assertIsDisplayed()
    }

    @Test fun failedReadDoesNotAdvertiseZeroDurationOrZeroConversionAsFacts() {
        show(ProfileUiState(error = "暂时无法读取阅读摘要", isEmpty = true))
        rule.onNodeWithText("暂时无法读取阅读摘要").assertIsDisplayed()
        rule.onNodeWithText("0 分钟").assertDoesNotExist()
        rule.onNodeWithText("0%").assertDoesNotExist()
    }

    @Test fun damagedNormalReadingIsUnavailableNotZeroMinutes() {
        val original = overview()
        show(ProfileUiState(isEmpty = true, trends = original.copy(normalReading = ReadingDurationTrends(
            totalDurationMillis = null, dataIssueCount = 1))))
        rule.onNodeWithTag("weekly-reading-duration").assertTextEquals("暂无可确认数据")
        rule.onNodeWithText("0 分钟").assertDoesNotExist()
        rule.onNodeWithText("最近 7 天还没有正常结束的阅读记录。").assertDoesNotExist()
    }

    @Test fun realDailyValuesAndUnknownMarkersRemainDistinct() {
        val original = overview()
        val date = original.daily.last().date
        show(ProfileUiState(trends = original.copy(daily = original.daily.dropLast(1) +
            original.daily.last().copy(normalReading = ReadingDurationTrends(totalDurationMillis = null, dataIssueCount = 1)))))
        rule.onNodeWithTag("weekly-reading-chart-$date-unknown", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("weekly-reading-chart-$date-bar", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("${date.minusDays(1)}，0 分钟").assertExists()
    }

    @Test fun allUnknownDatesCannotAdvertiseAKnownZeroMaximum() {
        val original = overview()
        show(ProfileUiState(trends = original.copy(daily = original.daily.map {
            it.copy(normalReading = ReadingDurationTrends(totalDurationMillis = null, dataIssueCount = 1))
        })))
        rule.onNodeWithText("暂无可确认最高时长").assertExists()
        rule.onNodeWithText("最高 0 分钟").assertDoesNotExist()
    }

    @Test fun unreadablePreviousPeriodCannotProduceFalseGrowthComparison() {
        show(ProfileUiState(comparisonText = "阅读时间比前 7 天多 20 分钟", trends = overview().copy(
            previousNormalReading = ReadingDurationTrends(totalDurationMillis = null, dataIssueCount = 1))))
        rule.onNodeWithText("阅读时间比前 7 天多 20 分钟").assertDoesNotExist()
    }

    @Test fun twoThirdsConversionRoundsTheSameAsDetailedTrends() {
        val original = overview()
        show(ProfileUiState(trends = original.copy(current = original.current.copy(start = original.current.start.copy(
            convertedCount = 2, abandonedCount = 1, conversion = TrendFraction(2, 3, 2.0 / 3))))))
        rule.onNodeWithTag("weekly-start").assertTextEquals("67%")
    }

    @Test fun mixedLengthDatesKeepEqualDurationBarsOnOneBaselineAtDoubleFont() {
        val original = overview()
        val date = LocalDate.of(2026, 9, 7)
        val points = original.daily.mapIndexed { index, point -> point.copy(date = date.plusDays(index.toLong()),
            normalReading = ReadingDurationTrends(1, 600_000)) }
        val density = rule.activity.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) { MirraTheme {
                Box(Modifier.width(320.dp)) {
                    com.guanyi.mirra.feature.profile.DailyReadingChart(points, "baseline-chart")
                }
            } }
        }
        val bottoms = points.map { rule.onNodeWithTag("baseline-chart-${it.date}-bar", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.bottom }
        org.junit.Assert.assertEquals(bottoms.first(), bottoms.last(), 0.5f)
    }

    @Test fun weeklyActionsReachable320Normal() = reachable(320, 1f)
    @Test fun weeklyActionsReachable320Double() = reachable(320, 2f)
    @Test fun weeklyActionsReachable360Normal() = reachable(360, 1f)
    @Test fun weeklyActionsReachable360Double() = reachable(360, 2f)
    @Test fun weeklyActionsReachable411Normal() = reachable(411, 1f)
    @Test fun weeklyActionsReachable411Double() = reachable(411, 2f)

    private fun reachable(width: Int, fontScale: Float) {
        val density = rule.activity.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MirraTheme { Box(Modifier.width(width.dp).height(560.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ProfileSummaryContent(ProfileUiState(trends = overview()))
                    }
                } }
            }
        }
        for (text in listOf("详细分析", "更多阅读数据")) {
            val node = rule.onNodeWithText(text).performScrollTo()
            node.assertIsDisplayed().assertHasClickAction()
            org.junit.Assert.assertTrue(node.fetchSemanticsNode().boundsInRoot.height / density >= 47.9f)
        }
        if (width == 320 && fontScale == 2f) captureReadingRecordEvidence("v1-experience-weekly-320-font2")
    }

    private fun overview(): TrendsSnapshot {
        val time = AnalyticsTimeContext(Instant.parse("2026-10-09T08:00:00Z"), ZoneId.of("Asia/Shanghai"))
        return TrendsService().accumulator(TrendsRange.SEVEN_DAYS, time).finish()
    }

    private fun show(state: ProfileUiState) {
        rule.setContent {
            MirraTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ProfileSummaryContent(state) } }
        }
    }
}
