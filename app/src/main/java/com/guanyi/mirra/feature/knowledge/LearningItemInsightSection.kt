package com.guanyi.mirra.feature.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.guanyi.mirra.domain.insights.ReadingPaceInsight
import com.guanyi.mirra.domain.insights.ReadingPaceInsightDirection
import com.guanyi.mirra.ui.theme.MirraTheme

/** Read-only description. Rounding and eligibility remain owned by the domain service. */
@Composable
fun LearningItemInsightSection(insight: ReadingPaceInsight, modifier: Modifier = Modifier) {
    val slower = insight.direction == ReadingPaceInsightDirection.SLOWER
    val change = if (insight.changeExceeds100Percent) "超过 100%" else "约 ${insight.roundedChangePercent}%"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (slower) "近期阅读节奏变慢" else "近期阅读节奏变快",
            color = MirraTheme.colors.textPrimary, style = MaterialTheme.typography.titleMedium)
        Text("最近 3 次可比较阅读，比此前这本书的有效阅读速度${if (slower) "低" else "高"}$change。",
            color = MirraTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        Text("仅比较完整监测且可计算有效阅读速度的记录。",
            color = MirraTheme.colors.textTertiary, style = MaterialTheme.typography.bodySmall)
    }
}
