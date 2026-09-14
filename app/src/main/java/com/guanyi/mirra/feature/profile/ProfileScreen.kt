package com.guanyi.mirra.feature.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.guanyi.mirra.ui.theme.MirraTheme

@Composable
fun ProfileScreen(viewModel: ProfileViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshTimeContext()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = "我的",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "你的学习数据只保存在这台设备上。",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MirraTheme.colors.textSecondary,
        )
        ProfileSummaryContent(state, Modifier.padding(top = 28.dp))
    }
}

@Composable
fun ProfileSummaryContent(state: ProfileUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("最近 7 天", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MirraTheme.colors.textPrimary)
        if (state.isLoading) {
            Text("正在整理本地记录…", color = MirraTheme.colors.textTertiary)
            return@Column
        }
        state.error?.let { Text(it, color = MirraTheme.colors.danger) }
        if (state.isEmpty && state.sessionCount == 0) {
            Text("最近 7 天还没有正常结束的阅读记录。", color = MirraTheme.colors.textSecondary)
        }
        FactRow("正常阅读", "${state.sessionCount} 次")
        FactRow("阅读时长", state.totalDurationText)
        FactRow("推进页数", "${state.pagesRead} 页")
        FactRow("新增笔记", "${state.noteCount} 条")
        state.comparisonText?.let {
            HorizontalDivider(color = MirraTheme.colors.divider)
            Text(it, color = MirraTheme.colors.textTertiary)
        }
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MirraTheme.colors.textSecondary)
        Text(value, color = MirraTheme.colors.textPrimary, fontWeight = FontWeight.Medium)
    }
}
