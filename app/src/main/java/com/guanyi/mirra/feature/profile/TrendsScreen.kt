package com.guanyi.mirra.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guanyi.mirra.domain.trends.TrendFraction
import com.guanyi.mirra.domain.trends.TrendsRange
import com.guanyi.mirra.domain.trends.TrendsSnapshot
import com.guanyi.mirra.ui.components.MirraSecondaryButton
import com.guanyi.mirra.ui.components.MirraTextAction
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun TrendsScreen(viewModel: TrendsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val error = state.error
    val snapshot = state.snapshot
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MirraTheme.colors.background),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item("title") {
            Text("趋势", style = MaterialTheme.typography.headlineLarge,
                color = MirraTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold)
        }
        item("ranges") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TrendsRange.entries.forEach { range ->
                    MirraTextAction(
                        onClick = { viewModel.selectRange(range) },
                        modifier = Modifier.testTag("trends-range-${range.name}")
                            .semantics { selected = state.range == range },
                    ) {
                        Text(range.label, color = if (state.range == range) MirraTheme.colors.accentStrong
                            else MirraTheme.colors.textPrimary,
                            fontWeight = if (state.range == range) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        when {
            state.isLoading -> item("loading") {
                Text("正在整理本地趋势…", color = MirraTheme.colors.textPrimary)
            }
            error != null -> item("error") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error, color = MirraTheme.colors.textPrimary)
                    MirraTextAction(viewModel::retry) { Text("重试读取") }
                }
            }
            snapshot != null -> facts(snapshot)
        }
        item("refresh") { MirraTextAction(viewModel::refresh, Modifier.fillMaxWidth(), enabled = !state.isLoading) { Text("刷新") } }
        item("back") { MirraSecondaryButton(onBack, Modifier.fillMaxWidth()) { Text("返回") } }
    }
}

private fun LazyListScope.facts(snapshot: TrendsSnapshot) {
    val start = snapshot.current.start
    val maintain = snapshot.current.maintain
    val recover = snapshot.current.recover
    val comparison = snapshot.comparison.takeUnless { snapshot.range == TrendsRange.ALL }
    val previous = snapshot.range.days?.let { "前 $it 天" }
    val noResolvedIntents = start.conversion.denominator == 0L
    val noEndedSessions = maintain.endedSessionCount == 0L
    val unavailable = if (noEndedSessions) ACCUMULATING else UNAVAILABLE

    heading("开始", "start", "按学习意图创建日期统计")
    metric("学习意图", "${start.conversion.denominator + start.openCount} 次", "intents")
    metric("真正开始", "${start.convertedCount} 次", "converted", countChange(comparison?.convertedCountDelta, previous))
    metric("放弃", "${start.abandonedCount} 次", "abandoned")
    metric("超时", "${start.timeoutCount} 次", "timeouts")
    metric("开始转化", fraction(start.conversion, if (noResolvedIntents) ACCUMULATING else UNAVAILABLE), "conversion",
        if (comparison == null) null else percentagePointChange(comparison.conversionPercentagePointDelta, previous))
    if (start.openCount > 0) item("open-intents") {
        Text("${start.openCount} 个学习意图仍在进行，不计入转化率", color = MirraTheme.colors.textPrimary)
    }
    metric("典型开始耗时", latency(start.medianStartLatencyMillis, if (start.convertedCount == 0L) ACCUMULATING else UNAVAILABLE), "start-latency")
    if (start.startDataIssueCount > 0) metric("开始耗时无法确认", "${start.startDataIssueCount} 次", "start-issues")
    metric("已确认稳定开始", "${start.stableConfirmedCount} 次", "stable-confirmed",
        countChange(comparison?.stableConfirmedCountDelta, previous))
    metric("未确认稳定开始", "${start.stableUnconfirmedCount} 次", "stable-unconfirmed")
    if (start.stableDataIssueCount > 0) metric("稳定开始数据无法确认", "${start.stableDataIssueCount} 次", "stable-issues")
    metric("典型稳定耗时", latency(start.medianStableLatencyMillis, if (start.convertedCount == 0L) ACCUMULATING else UNAVAILABLE), "stable-latency")

    heading("学习过程", "maintain", "按阅读结束日期统计")
    metric("可完整分析的阅读", "${maintain.trustedSessionCount} 次", "trusted-count",
        countChange(comparison?.trustedSessionCountDelta, previous))
    metric("有效专注时间", duration(maintain.effectiveFocusMillis, unavailable), "effective-focus",
        durationChange(comparison?.effectiveFocusMillisDelta, previous))
    metric("深度阅读时间", duration(maintain.deepFocusMillis, unavailable), "deep-focus",
        durationChange(comparison?.deepFocusMillisDelta, previous))
    metric("明确分心次数", if (maintain.trustedSessionCount == 0L) unavailable else "${maintain.distractionCount} 次", "distraction-count",
        countChange(comparison?.distractionCountDelta?.takeIf {
            maintain.trustedSessionCount > 0L && (snapshot.previous?.maintain?.trustedSessionCount ?: 0L) > 0L
        }, previous))
    metric("明确分心时间", duration(maintain.distractionMillis, unavailable), "distraction-duration")
    metric("无法完整分析的记录", "${maintain.unavailableSessionCount} 次", "unavailable-count",
        countChange(comparison?.unavailableSessionCountDelta, previous))

    heading("回到学习", "recover", "按阅读结束日期统计")
    metric("分心后恢复尝试", "${recover.attemptCount} 次", "recovery-attempts",
        countChange(comparison?.recoveryAttemptCountDelta, previous))
    metric("明确成功", "${recover.successCount} 次", "recovery-success",
        countChange(comparison?.recoverySuccessCountDelta, previous))
    metric("明确中断", "${recover.interruptedCount} 次", "recovery-interrupted")
    metric("无法确认", "${recover.unknownCount} 次", "recovery-unknown",
        countChange(comparison?.recoveryUnknownCountDelta, previous))
    metric("已确认结果中的成功", fraction(recover.knownOutcomeSuccess,
        if (recover.attemptCount == 0L) ACCUMULATING else UNAVAILABLE), "recovery-known-fraction")
    metric("典型恢复耗时", latency(recover.medianSuccessfulRecoveryMillis,
        if (recover.attemptCount == 0L) ACCUMULATING else UNAVAILABLE), "recovery-latency")
}

private fun LazyListScope.heading(label: String, key: String, cohort: String) {
    item("heading-$key") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            HorizontalDivider(color = MirraTheme.colors.divider)
            Text(label, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = MirraTheme.colors.textPrimary)
            Text(cohort, modifier = Modifier.testTag("trends-cohort-$key"),
                style = MaterialTheme.typography.bodyLarge, color = MirraTheme.colors.textPrimary)
        }
    }
}

private fun LazyListScope.metric(label: String, value: String, key: String, comparison: String? = null) {
    item("metric-$key") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MirraTheme.colors.textPrimary)
            Text(value, modifier = Modifier.testTag("trend-$key"),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                color = MirraTheme.colors.textPrimary)
            comparison?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MirraTheme.colors.textPrimary) }
        }
    }
}

private val TrendsRange.label: String get() = when (this) {
    TrendsRange.SEVEN_DAYS -> "7天"
    TrendsRange.THIRTY_DAYS -> "30天"
    TrendsRange.NINETY_DAYS -> "90天"
    TrendsRange.ALL -> "全部"
}

private fun fraction(fraction: TrendFraction, unavailable: String): String {
    val value = fraction.value
    if (fraction.denominator <= 0 || value == null || !value.isFinite()) return unavailable
    return "${fraction.numerator} / ${fraction.denominator} · ${(value * 100).roundToInt()}%"
}

private fun duration(millis: Long?, unavailable: String): String =
    if (millis == null || millis < 0) unavailable else com.guanyi.mirra.feature.session.recordDuration(millis)

private fun latency(millis: Double?, unavailable: String): String {
    if (millis == null || !millis.isFinite() || millis < 0) return unavailable
    if (millis > 0 && millis < 1_000) return "不足 1 秒"
    val seconds = (millis / 1_000).roundToLong()
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    val remainingSeconds = seconds % 60
    return buildList {
        if (hours > 0) add("$hours 小时")
        if (minutes > 0) add("$minutes 分钟")
        if (remainingSeconds > 0 || isEmpty()) add("$remainingSeconds 秒")
    }.joinToString(" ")
}

private fun countChange(delta: Long?, previous: String?): String? {
    if (delta == null || previous == null || delta == Long.MIN_VALUE) return null
    return when {
        delta > 0 -> "比${previous}多 $delta 次"
        delta < 0 -> "比${previous}少 ${-delta} 次"
        else -> "与${previous}相同"
    }
}

private fun durationChange(delta: Long?, previous: String?): String? {
    if (delta == null || previous == null || delta == Long.MIN_VALUE) return null
    if (delta == 0L) return "与${previous}相同"
    return "比$previous${if (delta > 0) "多" else "少"} ${duration(abs(delta), UNAVAILABLE)}"
}

private fun percentagePointChange(delta: Double?, previous: String?): String? {
    if (previous == null) return null
    if (delta == null || !delta.isFinite()) return "暂无可比较数据"
    val points = abs(delta).roundToInt()
    return when {
        delta == 0.0 -> "与${previous}相同"
        points == 0 -> "与${previous}接近"
        delta > 0 -> "比${previous}高 $points 个百分点"
        else -> "比${previous}低 $points 个百分点"
    }
}

private const val ACCUMULATING = "数据积累中"
private const val UNAVAILABLE = "暂无可确认数据"
