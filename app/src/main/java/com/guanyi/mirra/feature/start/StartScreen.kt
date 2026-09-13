package com.guanyi.mirra.feature.start

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guanyi.mirra.data.local.model.RecentReadingSnapshot
import com.guanyi.mirra.ui.components.MirraFocusCard
import com.guanyi.mirra.ui.components.MirraPrimaryButton
import com.guanyi.mirra.ui.components.MirraProgress
import com.guanyi.mirra.ui.components.MirraTextAction
import com.guanyi.mirra.ui.theme.MirraTheme

@Composable
fun StartScreen(
    viewModel: StartViewModel,
    onOpenIntent: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onCreateFirstLearningItem: () -> Unit,
    onOpenKnowledge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("观已Mirra", style = MaterialTheme.typography.labelLarge, color = MirraTheme.colors.textSecondary)
        Text("我现在要怎么开始学习？", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

        if (state.isLoading) CircularProgressIndicator()
        state.content?.let { content ->
            StartContent(
                content = content,
                isSubmitting = state.isSubmitting,
                onOpenIntent = onOpenIntent,
                onOpenSession = onOpenSession,
                onCreateFirstLearningItem = onCreateFirstLearningItem,
                onOpenKnowledge = onOpenKnowledge,
                onSelectItem = viewModel::selectItem,
                onSetAsMainline = viewModel::setSelectedAsMainline,
                onBegin = { viewModel.begin(onOpenIntent) },
                onAbandon = { viewModel.abandonIntent() },
            )
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun StartContent(
    content: StartContentState,
    isSubmitting: Boolean,
    onOpenIntent: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onCreateFirstLearningItem: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onSelectItem: (String) -> Unit,
    onSetAsMainline: (Boolean) -> Unit,
    onBegin: () -> Unit,
    onAbandon: () -> Unit,
) {
    when (content) {
        is StartContentState.ActiveSession -> {
            Text("正在学习", style = MaterialTheme.typography.titleLarge)
            Text(content.learningItemName, style = MaterialTheme.typography.headlineSmall)
            Text("当前第 ${content.currentPage} 页 · 已进行 ${content.elapsedMinutes} 分钟")
            MirraPrimaryButton(onClick = { onOpenSession(content.sessionId) }, modifier = Modifier.fillMaxWidth()) {
                Text("继续学习")
            }
        }
        is StartContentState.ActiveIntent -> {
            Text("准备开始", style = MaterialTheme.typography.titleLarge)
            Text(content.learningItemName, style = MaterialTheme.typography.headlineSmall)
            Text(content.firstAction, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MirraPrimaryButton(onClick = { onOpenIntent(content.intentId) }, modifier = Modifier.fillMaxWidth()) {
                Text("继续准备")
            }
            MirraTextAction(onClick = onAbandon, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth()) {
                Text("取消本次启动")
            }
        }
        is StartContentState.Mainline -> {
            Text("当前主线", style = MaterialTheme.typography.titleMedium)
            LearningItemSummary(content.item, content.recentReading)
            MirraPrimaryButton(onClick = onBegin, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth()) {
                Text("开始学习")
            }
        }
        is StartContentState.ChooseInProgress -> {
            Text("这次想学什么？", style = MaterialTheme.typography.titleLarge)
            content.items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelectItem(item.id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = content.selectedItemId == item.id, onClick = { onSelectItem(item.id) })
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontWeight = FontWeight.Medium)
                        Text("上次停在第 ${item.currentPage} 页", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
            }
            if (content.selectedItemId != null) {
                val selected = content.items.first { it.id == content.selectedItemId }
                Text(selected.firstAction, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RecentReading(content.recentReading)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = content.setSelectedAsMainline, onCheckedChange = onSetAsMainline)
                    Text("设为主线")
                }
            }
            MirraPrimaryButton(
                onClick = onBegin,
                enabled = content.selectedItemId != null && !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("开始学习") }
        }
        is StartContentState.NoInProgress -> {
            Text("暂无正在学习的内容", style = MaterialTheme.typography.titleLarge)
            Text("你已有 ${content.totalItemCount} 项学习内容，可到知识页恢复或查看。")
            MirraPrimaryButton(onClick = onOpenKnowledge, modifier = Modifier.fillMaxWidth()) { Text("查看学习内容") }
        }
        StartContentState.EmptyLibrary -> {
            Text("开始你的第一次学习", style = MaterialTheme.typography.titleLarge)
            Text("先添加一本书，再决定是否把它设为主线。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            MirraPrimaryButton(onClick = onCreateFirstLearningItem, modifier = Modifier.fillMaxWidth()) { Text("添加第一本书") }
        }
    }
}

@Composable
private fun LearningItemSummary(item: StartLearningItem, recentReading: RecentReadingSnapshot?) {
    MirraFocusCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.name, style = MaterialTheme.typography.headlineSmall)
            Text("上次停在第 ${item.currentPage} 页 · 共 ${item.totalPages} 页")
            MirraProgress(progress = { item.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
            Text(item.firstAction, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RecentReading(recentReading)
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun RecentReading(recent: RecentReadingSnapshot?) {
    recent?.let {
        Text(
            "上次阅读 ${it.durationMillis / 60_000L} 分钟 · ${it.noteCount} 条笔记",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
