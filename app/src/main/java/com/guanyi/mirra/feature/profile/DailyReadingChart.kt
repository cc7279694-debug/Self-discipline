package com.guanyi.mirra.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.guanyi.mirra.domain.trends.DailyTrendsPoint
import com.guanyi.mirra.feature.session.recordDuration
import com.guanyi.mirra.ui.theme.MirraTheme

/** Exact ended-date cohorts, not a division of the period total or a focus-quality claim. */
@Composable
fun DailyReadingChart(points: List<DailyTrendsPoint>, tag: String, sparseDates: Boolean = false) {
    if (points.isEmpty()) {
        Text("暂无逐日记录", color = MirraTheme.colors.textSecondary, modifier = Modifier.testTag(tag))
        return
    }
    // The ALL total remains complete; only its explicitly labelled visual slice is bounded.
    val visible = points.takeLast(90)
    val confirmedMaximum = visible.mapNotNull { it.normalReading.totalDurationMillis }.maxOrNull()
    val maximum = confirmedMaximum?.coerceAtLeast(1L) ?: 1L
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("每日阅读时长 · 按结束日期", style = MaterialTheme.typography.bodySmall, color = MirraTheme.colors.textSecondary)
        if (sparseDates) Text("按有记录的日期展示${if (points.size > 90) "，图中为最近 90 个日期" else ""}",
            style = MaterialTheme.typography.bodySmall, color = MirraTheme.colors.textSecondary)
        Text(confirmedMaximum?.let { "最高 ${recordDuration(it)}" } ?: "暂无可确认最高时长", style = MaterialTheme.typography.labelMedium,
            color = MirraTheme.colors.textSecondary)
        val scroll = rememberScrollState()
        Row(Modifier.fillMaxWidth().then(if (visible.size > 7) Modifier.horizontalScroll(scroll) else Modifier),
            verticalAlignment = Alignment.Top) {
            visible.forEach { point ->
                val millis = point.normalReading.totalDurationMillis
                val description = "${point.date}，${millis?.let(::recordDuration) ?: "暂无可确认数据"}" +
                    if (point.normalReading.dataIssueCount > 0) "，另有无法确认的记录" else ""
                Column((if (visible.size <= 7) Modifier.weight(1f) else Modifier.width(44.dp))
                    .semantics(mergeDescendants = true) { contentDescription = description },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(100.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        if (millis == null) {
                            Text("—", color = MirraTheme.colors.textSecondary, modifier = Modifier.testTag("$tag-${point.date}-unknown"))
                        } else {
                            val height = (millis.toDouble() / maximum.toDouble() * 96).toFloat().coerceIn(2f, 96f)
                            Box(Modifier.width(18.dp).height(height.dp)
                                .testTag("$tag-${point.date}-bar")
                                .background(if (millis == 0L) MirraTheme.colors.divider else MirraTheme.colors.accentStrong,
                                    RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                        }
                    }
                    Text("${point.date.monthValue}/${point.date.dayOfMonth}", style = MaterialTheme.typography.labelSmall,
                        color = MirraTheme.colors.textSecondary, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (visible.any { it.normalReading.dataIssueCount > 0 }) {
            Text("图表只累计可确认的阅读时长；— 表示无法确认。", style = MaterialTheme.typography.bodySmall,
                color = MirraTheme.colors.textSecondary)
        }
    }
}
