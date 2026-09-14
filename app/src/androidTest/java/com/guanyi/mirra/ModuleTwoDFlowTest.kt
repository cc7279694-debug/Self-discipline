package com.guanyi.mirra

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.guanyi.mirra.domain.PredictionConfidence
import com.guanyi.mirra.feature.knowledge.LearningItemAnalyticsUi
import com.guanyi.mirra.feature.knowledge.LearningItemReadingSection
import com.guanyi.mirra.feature.knowledge.SessionHistoryUi
import com.guanyi.mirra.feature.profile.ProfileSummaryContent
import com.guanyi.mirra.feature.profile.ProfileUiState
import com.guanyi.mirra.ui.theme.MirraTheme
import java.time.Duration
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class ModuleTwoDFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun learningItemSectionShowsPacePredictionAndAbnormalHistoryWithoutDashboardCards() {
        composeRule.setContent {
            MirraTheme {
                LearningItemReadingSection(
                    analytics = LearningItemAnalyticsUi(
                        recentReadingText = "昨天 · 42 分钟 · 18 页",
                        speedText = "最近约 22 页/小时",
                        speedBasisText = "根据最近 14 天 6 次正常阅读",
                        remainingTimeText = "预计剩余阅读时间约 6 小时",
                        completionDateText = "预计 9月22日–9月27日自然读完",
                        confidence = PredictionConfidence.MEDIUM,
                        unavailableText = null,
                    ),
                    history = listOf(
                        SessionHistoryUi(
                            id = "normal",
                            date = LocalDate.of(2026, 9, 12),
                            duration = Duration.ofMinutes(42),
                            startPage = 100,
                            endPage = 118,
                            pagesRead = 18,
                            noteCount = 3,
                            abnormal = false,
                        ),
                        SessionHistoryUi(
                            id = "abnormal",
                            date = LocalDate.of(2026, 9, 11),
                            duration = Duration.ofMinutes(5),
                            startPage = 95,
                            endPage = 95,
                            pagesRead = 0,
                            noteCount = 0,
                            abnormal = true,
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("最近约 22 页/小时").assertIsDisplayed()
        composeRule.onNodeWithText("预计剩余阅读时间约 6 小时").assertIsDisplayed()
        composeRule.onNodeWithText("可信度：中").assertIsDisplayed()
        composeRule.onNodeWithText("异常结束 · 不参与统计").assertIsDisplayed()
        composeRule.onNodeWithText("有效专注时间").assertDoesNotExist()
    }

    @Test
    fun profileShowsOnlyFlatSevenDayFactsAndOneComparisonSentence() {
        composeRule.setContent {
            MirraTheme {
                ProfileSummaryContent(
                    state = ProfileUiState(
                        sessionCount = 4,
                        totalDurationText = "2 小时 15 分钟",
                        pagesRead = 63,
                        noteCount = 7,
                        comparisonText = "阅读时间比前 7 天多 35 分钟",
                    ),
                )
            }
        }

        composeRule.onNodeWithText("最近 7 天").assertIsDisplayed()
        composeRule.onNodeWithText("4 次").assertIsDisplayed()
        composeRule.onNodeWithText("2 小时 15 分钟").assertIsDisplayed()
        composeRule.onNodeWithText("63 页").assertIsDisplayed()
        composeRule.onNodeWithText("7 条").assertIsDisplayed()
        composeRule.onNodeWithText("阅读时间比前 7 天多 35 分钟").assertIsDisplayed()
        composeRule.onNodeWithText("自律评分").assertDoesNotExist()
    }
}
