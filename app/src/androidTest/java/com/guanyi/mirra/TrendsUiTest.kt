package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.repository.TrendsRepository
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.AnalyticsTimeProvider
import com.guanyi.mirra.domain.trends.*
import com.guanyi.mirra.feature.profile.TrendsScreen
import com.guanyi.mirra.feature.profile.TrendsViewModel
import com.guanyi.mirra.ui.theme.MirraTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TrendsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val time = AnalyticsTimeContext(Instant.parse("2026-10-07T04:00:00Z"), ZoneId.of("Asia/Shanghai"))

    @Test fun overviewHasThreeTaskSectionsAndSecondaryDetails() {
        val vm = vm { range -> snapshot(range, trustedPeriod()) }
        try {
            show(vm)
            awaitReady(vm, expand = false)
            for (heading in listOf("启动", "专注", "恢复")) {
                scrollTo(heading)
                rule.onNodeWithText(heading).assertIsDisplayed()
            }
            scrollTo("展开启动详情")
            rule.onNodeWithText("展开启动详情").assertHasClickAction()
        } finally { close(vm) }
    }

    @Test fun sectionsExplainTheirIntentAndEndedReadingDateCohorts() {
        val vm = vm { range -> snapshot(range, trustedPeriod()) }
        try {
            show(vm)
            awaitReady(vm)
            for ((section, explanation) in listOf(
                "start" to "按学习意图创建日期统计",
                "maintain" to "按阅读结束日期统计",
                "recover" to "按阅读结束日期统计",
            )) {
                val tag = "trends-cohort-$section"
                rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
                rule.onNodeWithTag(tag).assertIsDisplayed().assertTextEquals(explanation)
            }
        } finally { close(vm) }
    }

    @Test fun startShowsConversionOpenIntentAndStableCountsWithoutInventingStableRate() {
        val vm = vm { range -> snapshot(range, trustedPeriod()) }
        try {
            show(vm)
            awaitReady(vm)
            rule.waitForIdle(); captureReadingRecordEvidence("phase4a-trends-normal")
            scrollTo("9 / 12 · 75%")
            rule.onNodeWithTag("trend-conversion").assertTextEquals("9 / 12 · 75%")
            scrollTo("1 个学习意图仍在进行，不计入转化率")
            rule.onNodeWithText("1 个学习意图仍在进行，不计入转化率").assertExists()
            scrollTo("已确认稳定开始")
            rule.onNodeWithTag("trend-stable-confirmed").assertTextEquals("3 次")
            rule.onNodeWithTag("trend-stable-unconfirmed").assertTextEquals("6 次")
            scrollTo("典型稳定耗时")
            rule.onNodeWithTag("trend-stable-latency").assertTextEquals("2 分钟")
            rule.onNodeWithText("稳定开始成功率", substring = true).assertDoesNotExist()
        } finally { close(vm) }
    }

    @Test fun trustedZeroFocusAndDistractionRemainRealZeros() {
        val full = trustedPeriod().copy(maintain = MaintainTrends(1, 1, 0, 0, 0, 0, 0, false))
        val vm = vm { range -> snapshot(range, full) }
        try {
            show(vm)
            awaitReady(vm)
            scrollTo("有效专注时间")
            rule.onNodeWithTag("trend-effective-focus").assertTextEquals("0 分钟")
            scrollTo("明确分心次数")
            rule.onNodeWithTag("trend-distraction-count").assertTextEquals("0 次")
        } finally { close(vm) }
    }

    @Test fun noneOrPartialCannotDisplayZeroAsConfirmedFocusOrDistraction() {
        val period = trustedPeriod().copy(maintain = MaintainTrends(2, 0, 2, null, null, 0, null, false))
        val vm = vm { range -> snapshot(range, period) }
        try {
            show(vm)
            awaitReady(vm)
            scrollTo("有效专注时间")
            rule.onNodeWithTag("trend-effective-focus").assertTextEquals("暂无可确认数据")
            rule.waitForIdle(); captureReadingRecordEvidence("phase4a-trends-unavailable")
            scrollTo("明确分心次数")
            rule.onNodeWithTag("trend-distraction-count").assertTextEquals("暂无可确认数据")
            scrollTo("无法完整分析的记录")
            rule.onNodeWithTag("trend-unavailable-count").assertTextEquals("2 次")
        } finally { close(vm) }
    }

    @Test fun recoveryShowsUnknownSeparatelyAndKnownOutcomeFractionOnly() {
        val vm = vm { range -> snapshot(range, trustedPeriod()) }
        try {
            show(vm)
            awaitReady(vm)
            scrollTo("无法确认")
            rule.onNodeWithTag("trend-recovery-unknown").assertTextEquals("1 次")
            scrollTo("已确认结果中的成功")
            rule.onNodeWithTag("trend-recovery-known-fraction").assertTextEquals("2 / 3 · 67%")
            scrollTo("典型恢复耗时")
            rule.onNodeWithTag("trend-recovery-latency").assertTextEquals("1 分钟 30 秒")
        } finally { close(vm) }
    }

    @Test fun knownRecoveryBeforeLaterMonitoringLossRemainsVisible() {
        val period = trustedPeriod().copy(maintain = MaintainTrends(2, 0, 2, null, null, 0, null, false))
        val vm = vm { range -> snapshot(range, period) }
        try {
            show(vm)
            awaitReady(vm)
            scrollTo("已确认结果中的成功")
            rule.onNodeWithTag("trend-recovery-known-fraction").assertTextEquals("2 / 3 · 67%")
            scrollTo("典型恢复耗时")
            rule.onNodeWithTag("trend-recovery-latency").assertTextEquals("1 分钟 30 秒")
        } finally { close(vm) }
    }

    @Test fun confirmedStableStartsDoNotRequireNormallyEndedReading() {
        val period = trustedPeriod().copy(maintain = MaintainTrends(0, 0, 0, null, null, 0, null, false))
        val vm = vm { range -> snapshot(range, period) }
        try {
            show(vm)
            awaitReady(vm)
            scrollTo("已确认稳定开始")
            rule.onNodeWithTag("trend-stable-confirmed").assertTextEquals("3 次")
            scrollTo("典型稳定耗时")
            rule.onNodeWithTag("trend-stable-latency").assertTextEquals("2 分钟")
        } finally { close(vm) }
    }

    @Test fun noKnownRecoveryOrStableTimeDoesNotInventZeroOrPercent() {
        val base = trustedPeriod()
        val period = base.copy(
            start = base.start.copy(stableConfirmedCount = 0, stableUnconfirmedCount = 9, medianStableLatencyMillis = null),
            recover = RecoverTrends(3, 0, 0, 3, 0, 0, TrendFraction(0, 0, null), null),
        )
        val vm = vm { range -> snapshot(range, period) }
        try {
            show(vm)
            awaitReady(vm)
            scrollTo("典型稳定耗时")
            rule.onNodeWithTag("trend-stable-latency").assertTextEquals("暂无可确认数据")
            scrollTo("已确认结果中的成功")
            rule.onNodeWithTag("trend-recovery-known-fraction").assertTextEquals("暂无可确认数据")
            scrollTo("典型恢复耗时")
            rule.onNodeWithTag("trend-recovery-latency").assertTextEquals("暂无可确认数据")
        } finally { close(vm) }
    }

    @Test fun emptyHistoryUsesAccumulatingStateAndHasNoFalseComparison() {
        val vm = vm { range -> snapshot(range, emptyPeriod()) }
        try {
            show(vm)
            awaitReady(vm)
            scrollTo("开始转化")
            rule.onNodeWithTag("trend-conversion").assertTextEquals("数据积累中")
            assertTrue(rule.onAllNodesWithText("数据积累中").fetchSemanticsNodes().isNotEmpty())
            rule.waitForIdle(); captureReadingRecordEvidence("phase4a-trends-empty")
            rule.onNodeWithText("增长", substring = true).assertDoesNotExist()
            rule.onNodeWithText("评分", substring = true).assertDoesNotExist()
        } finally { close(vm) }
    }

    @Test fun onlyOpenIntentHasNoResolvedConversionSample() {
        val empty = emptyPeriod()
        val vm = vm { range -> snapshot(range, empty.copy(start = empty.start.copy(openCount = 1))) }
        try {
            show(vm)
            awaitReady(vm)
            scrollTo("开始转化")
            rule.onNodeWithTag("trend-conversion").assertTextEquals("数据积累中")
            scrollTo("1 个学习意图仍在进行，不计入转化率")
            rule.onNodeWithText("1 个学习意图仍在进行，不计入转化率").assertExists()
        } finally { close(vm) }
    }

    @Test fun noRecoveryAttemptHasNoMedianOrOutcomeDenominator() {
        val full = trustedPeriod().copy(recover = emptyPeriod().recover)
        val vm = vm { range -> snapshot(range, full) }
        try {
            show(vm)
            awaitReady(vm)
            scrollTo("分心后恢复尝试")
            rule.onNodeWithTag("trend-recovery-attempts").assertTextEquals("0 次")
            scrollTo("已确认结果中的成功")
            rule.onNodeWithTag("trend-recovery-known-fraction").assertTextEquals("数据积累中")
            scrollTo("典型恢复耗时")
            rule.onNodeWithTag("trend-recovery-latency").assertTextEquals("数据积累中")
        } finally { close(vm) }
    }

    @Test fun comparisonUsesAbsoluteChangesAndIntegerPercentagePointsAndAllHidesComparison() {
        val comparison = TrendsComparison(2, 3, 8.1, 1, 2, -1, 120_000, 60_000, -1, 2, 1, 0)
        val vm = vm { range -> snapshot(range, trustedPeriod(), if (range == TrendsRange.ALL) null else comparison) }
        try {
            show(vm)
            awaitReady(vm)
            scrollTo("真正开始")
            assertTrue(rule.onAllNodesWithText("比前 7 天多 2 次").fetchSemanticsNodes().isNotEmpty())
            scrollTo("开始转化")
            rule.onNodeWithText("比前 7 天高 8 个百分点").assertExists()
            scrollTo("有效专注时间")
            rule.onNodeWithText("比前 7 天多 2 分钟").assertExists()
            scrollTo("全部")
            rule.onNodeWithTag("trends-range-ALL").performClick()
            rule.waitUntil(5_000) { vm.uiState.value.snapshot?.range == TrendsRange.ALL }
            rule.onAllNodesWithText("比前", substring = true).assertCountEquals(0)
        } finally { close(vm) }
    }

    @Test fun loadingThenErrorOffersRetryWithoutLeakingPrivateFailure() {
        val response = CompletableDeferred<Unit>()
        var fail = true
        val repository = object : TrendsRepository {
            override suspend fun load(range: TrendsRange, time: AnalyticsTimeContext): TrendsSnapshot {
                response.await()
                if (fail) error("private SQL path")
                return snapshot(range, trustedPeriod())
            }
        }
        val vm = TrendsViewModel(repository, provider())
        try {
            show(vm)
            await("正在整理本地趋势…")
            rule.runOnIdle { response.complete(Unit) }
            await("暂时无法读取趋势")
            rule.onNodeWithText("private SQL path").assertDoesNotExist()
            fail = false
            rule.onNodeWithText("重试读取").performClick()
            awaitReady(vm)
            scrollTo("9 / 12 · 75%")
            rule.onNodeWithText("暂时无法读取趋势").assertDoesNotExist()
        } finally { response.complete(Unit); close(vm) }
    }

    @Test fun rangesAndExitReachable320Normal() = reachable(320, 1f)
    @Test fun rangesAndExitReachable320Double() = reachable(320, 2f)
    @Test fun rangesAndExitReachable360Normal() = reachable(360, 1f)
    @Test fun rangesAndExitReachable360Double() = reachable(360, 2f)
    @Test fun rangesAndExitReachable411Normal() = reachable(411, 1f)
    @Test fun rangesAndExitReachable411Double() = reachable(411, 2f)

    private fun reachable(width: Int, fontScale: Float) {
        var exits = 0
        val period = trustedPeriod().let { it.copy(start = it.start.copy(convertedCount = 999_999_999, openCount = 999_999_999)) }
        val vm = vm { range -> snapshot(range, period) }
        val density = rule.activity.resources.displayMetrics.density
        try {
            rule.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    MirraTheme { Box(Modifier.width(width.dp).height(560.dp).testTag("trends-test-container")) {
                        TrendsScreen(vm, { exits++ })
                    } }
                }
            }
            await("趋势")
            awaitReady(vm)
            if (width == 320 && fontScale == 2f) {
                rule.waitUntil(5_000) { vm.uiState.value.snapshot != null }
                rule.waitForIdle(); captureReadingRecordEvidence("phase4a-trends-320dp-font2")
            }
            assertEquals(width.toFloat(), rule.onNodeWithTag("trends-test-container").fetchSemanticsNode().boundsInRoot.width / density, 0.5f)
            for (range in TrendsRange.entries) {
                val tag = "trends-range-${range.name}"
                rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
                val node = rule.onNodeWithTag(tag)
                node.assertIsDisplayed()
                assertTrue(node.fetchSemanticsNode().boundsInRoot.height / density >= 47.9f)
                node.performClick()
                rule.waitUntil(5_000) { vm.uiState.value.snapshot?.range == range }
                node.assertIsSelected()
            }
            scrollTo("999999999 个学习意图仍在进行，不计入转化率")
            rule.onNodeWithText("999999999 个学习意图仍在进行，不计入转化率").assertIsDisplayed()
            scrollTo("返回")
            val exit = rule.onNode(hasText("返回") and hasClickAction())
            assertTrue(exit.fetchSemanticsNode().boundsInRoot.height / density >= 47.9f)
            exit.performClick()
            assertEquals(1, exits)
        } finally { close(vm) }
    }

    private fun show(vm: TrendsViewModel) { rule.setContent { MirraTheme { TrendsScreen(vm, {}) } } }
    private fun awaitReady(vm: TrendsViewModel, expand: Boolean = true) {
        rule.waitUntil(5_000) { vm.uiState.value.snapshot != null }
        if (expand) for (section in listOf("start", "maintain", "recover")) {
            rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("trends-details-$section"))
            rule.onNodeWithTag("trends-details-$section").performClick()
        }
    }
    private fun await(text: String) = rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun scrollTo(text: String) { rule.onNode(hasScrollAction()).performScrollToNode(hasText(text)) }
    private fun close(vm: TrendsViewModel) { rule.activityRule.scenario.close(); vm.viewModelScope.cancel() }
    private fun provider() = AnalyticsTimeProvider(Clock.fixed(time.now, ZoneId.of("UTC"))) { time.zoneId }
    private fun vm(load: (TrendsRange) -> TrendsSnapshot) = TrendsViewModel(object : TrendsRepository {
        override suspend fun load(range: TrendsRange, time: AnalyticsTimeContext) = load(range)
    }, provider())

    private fun snapshot(range: TrendsRange, current: TrendsPeriod, comparison: TrendsComparison? = null) = TrendsSnapshot(
        range, time, TrendsWindows(TrendsWindow(null, time.now.toEpochMilli()), null), current,
        if (comparison == null) null else emptyPeriod(), comparison,
    )

    private fun trustedPeriod() = TrendsPeriod(
        StartTrends(9, 2, 1, 1, TrendFraction(9, 12, .75), 9, 60_000.0, 0, 3, 6, 0, 120_000.0),
        MaintainTrends(5, 4, 1, 1_200_000, 600_000, 4, 180_000, false),
        RecoverTrends(4, 2, 1, 1, 0, 0, TrendFraction(2, 3, 2.0 / 3.0), 90_000.0),
    )

    private fun emptyPeriod() = TrendsPeriod(
        StartTrends(0, 0, 0, 0, TrendFraction(0, 0, null), 0, null, 0, 0, 0, 0, null),
        MaintainTrends(0, 0, 0, null, null, 0, null, false),
        RecoverTrends(0, 0, 0, 0, 0, 0, TrendFraction(0, 0, null), null),
    )
}
