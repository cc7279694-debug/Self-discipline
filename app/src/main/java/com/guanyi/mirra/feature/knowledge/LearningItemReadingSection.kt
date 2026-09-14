package com.guanyi.mirra.feature.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.guanyi.mirra.domain.PredictionConfidence
import com.guanyi.mirra.ui.theme.MirraTheme
import java.time.Duration
import java.time.LocalDate

data class LearningItemAnalyticsUi(
    val recentReadingText: String? = null,
    val speedText: String? = null,
    val speedBasisText: String? = null,
    val remainingTimeText: String? = null,
    val completionDateText: String? = null,
    val confidence: PredictionConfidence? = null,
    val unavailableText: String? = null,
)

data class SessionHistoryUi(
    val id: String,
    val date: LocalDate,
    val duration: Duration,
    val startPage: Int,
    val endPage: Int,
    val pagesRead: Long,
    val noteCount: Int,
    val abnormal: Boolean,
    val excludedLabel: String? = null,
)

@Composable
fun LearningItemReadingSection(
    analytics: LearningItemAnalyticsUi?,
    history: List<SessionHistoryUi>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LearningItemAnalyticsSummary(analytics)
        if (history.isNotEmpty()) {
            Text(
                text = "阅读历史",
                color = MirraTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp),
            )
            history.forEach { item ->
                SessionHistoryRow(item)
                HorizontalDivider(color = MirraTheme.colors.divider)
            }
        }
    }
}

@Composable
fun LearningItemAnalyticsSummary(
    analytics: LearningItemAnalyticsUi?,
    modifier: Modifier = Modifier,
) {
    if (analytics == null) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("阅读节奏", color = MirraTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold)
        analytics.recentReadingText?.let { Text(it, color = MirraTheme.colors.textSecondary) }
        analytics.speedText?.let { Text(it, color = MirraTheme.colors.textPrimary, fontWeight = FontWeight.Medium) }
        analytics.speedBasisText?.let { Text(it, color = MirraTheme.colors.textTertiary) }
        analytics.remainingTimeText?.let { Text(it, color = MirraTheme.colors.textPrimary) }
        analytics.completionDateText?.let { Text(it, color = MirraTheme.colors.textPrimary) }
        analytics.confidence?.takeIf { it != PredictionConfidence.LOW }?.let {
            Text("可信度：${if (it == PredictionConfidence.HIGH) "高" else "中"}", color = MirraTheme.colors.textSecondary)
        }
        analytics.unavailableText?.let { Text(it, color = MirraTheme.colors.textTertiary) }
    }
}

@Composable
fun SessionHistoryRow(item: SessionHistoryUi, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${item.date.monthValue}月${item.date.dayOfMonth}日", color = MirraTheme.colors.textPrimary)
            Text(formatDuration(item.duration), color = MirraTheme.colors.textSecondary)
        }
        Text(
            "${item.startPage} → ${item.endPage} 页 · ${item.pagesRead} 页 · ${item.noteCount} 条笔记",
            color = MirraTheme.colors.textSecondary,
        )
        val label = when {
            item.abnormal -> "异常结束 · 不参与统计"
            item.excludedLabel != null -> "${item.excludedLabel} · 不参与统计"
            else -> null
        }
        label?.let { Text(it, color = MirraTheme.colors.textTertiary) }
    }
}

internal fun formatDuration(duration: Duration): String {
    val totalMinutes = duration.toMinutes().coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        totalMinutes == 0L -> "不足 1 分钟"
        hours == 0L -> "$minutes 分钟"
        minutes == 0L -> "$hours 小时"
        else -> "$hours 小时 $minutes 分钟"
    }
}
