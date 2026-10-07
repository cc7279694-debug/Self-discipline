package com.guanyi.mirra.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.ui.components.MirraTextAction
import com.guanyi.mirra.ui.theme.MirraTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun GlobalReadingHistoryScreen(viewModel: GlobalReadingHistoryViewModel, onSessionSelected: (String) -> Unit,
    onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    GlobalReadingHistoryContent(state, viewModel.zoneId, viewModel::loadMore, viewModel::retry,
        onSessionSelected, onBack, modifier)
}

@Composable
fun GlobalReadingHistoryContent(state: GlobalReadingHistoryUiState, zoneId: ZoneId, onLoadMore: () -> Unit,
    onRetry: () -> Unit, onSessionSelected: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd · HH:mm").withZone(zoneId)
    LazyColumn(modifier.fillMaxSize().background(MirraTheme.colors.background), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { MirraTextAction(onBack) { Text("返回") } }
        item { Text("阅读记录", style = MaterialTheme.typography.headlineLarge) }
        when {
            state.isLoading -> item { Text("正在读取阅读记录…") }
            state.initialError -> item { Column {
                Text("暂时无法读取阅读记录")
                MirraTextAction(onRetry) { Text("重试") }
            } }
            state.records.isEmpty() -> item { Text("还没有阅读记录", color = MirraTheme.colors.textSecondary) }
        }
        items(state.records, key = { it.sessionId }) { row ->
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("history-row-${row.sessionId}")
                .clickable { onSessionSelected(row.sessionId) }.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.learningItemName, style = MaterialTheme.typography.titleMedium)
                Text(formatter.format(Instant.ofEpochMilli(row.endedAt)), color = MirraTheme.colors.textSecondary)
                val duration = try { Math.subtractExact(row.endedAt, row.startedAt).takeIf { it >= 0 } }
                    catch (_: ArithmeticException) { null }
                val pages = row.endPage?.let { "${row.startPage} → $it 页" } ?: "页码未记录"
                Text("$pages · ${duration?.let { "${it / 60_000} 分钟" } ?: "时长不可用"}")
                val end = when (row.endType) {
                    SessionEndType.NORMAL -> "正常结束"
                    SessionEndType.ABNORMAL -> "异常结束"
                    SessionEndType.EARLY -> "提前结束"
                    SessionEndType.AUTO -> "自动结束"
                    SessionEndType.START_INCOMPLETE -> "开始未完成"
                    null -> "结束状态未记录"
                }
                val coverage = when (row.monitoringStatus) {
                    MonitoringCoverage.PARTIAL -> " · 监测不完整"
                    MonitoringCoverage.NONE -> " · 未监测"
                    null -> " · 无监测记录"
                    MonitoringCoverage.FULL -> ""
                }
                Text(end + coverage, color = MirraTheme.colors.textSecondary)
            }
            HorizontalDivider(color = MirraTheme.colors.divider)
        }
        if (state.pageError) item { MirraTextAction(onRetry) { Text("重试加载更多") } }
        else if (state.hasMore) item {
            MirraTextAction(onLoadMore, enabled = !state.loadingMore) { Text(if (state.loadingMore) "正在读取…" else "加载更多") }
        }
    }
}
