package com.guanyi.mirra.feature.start

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guanyi.mirra.data.local.model.RecentReadingSnapshot
import com.guanyi.mirra.domain.FirstActionResolver
import com.guanyi.mirra.ui.components.FirstActionInput
import com.guanyi.mirra.ui.components.MirraFocusCard
import com.guanyi.mirra.ui.components.MirraPrimaryButton
import com.guanyi.mirra.ui.components.MirraPersonGlyph
import com.guanyi.mirra.ui.components.MirraProgress
import com.guanyi.mirra.ui.components.MirraTextAction
import com.guanyi.mirra.ui.theme.MirraTheme
import java.time.Instant
import java.time.ZoneId

@Composable
fun StartScreen(
    viewModel: StartViewModel,
    onOpenIntent: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onCreateFirstLearningItem: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = MirraTheme.colors
    Box(
        modifier = modifier.fillMaxSize().drawBehind {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(colors.accentSoft.copy(alpha = .48f), Color.Transparent),
                    center = Offset(size.width * .95f, size.height * .11f),
                    radius = size.width * .88f,
                ),
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            BrandHeader(showProfile = state.content is StartContentState.Mainline, onOpenProfile = onOpenProfile)
            Spacer(Modifier.height(if (state.content is StartContentState.Mainline) 82.dp else 74.dp))
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
                    onEditFirstAction = viewModel::editFirstAction,
                    onAbandon = { viewModel.abandonIntent() },
                )
            }
            state.errorMessage?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = colors.danger)
            }
        }
    }
    state.firstActionEditor?.let { editor ->
        var edited by remember(editor.item.id, editor.beginAfterSaving) { mutableStateOf(editor.item.firstAction) }
        AlertDialog(
            onDismissRequest = viewModel::dismissFirstActionEditor,
            title = { Text("确定第一步") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(editor.item.name, style = MaterialTheme.typography.titleMedium)
                    FirstActionInput(edited, { edited = it }, editor.item.currentPage, enabled = !state.isSubmitting)
                    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.saveFirstAction(edited, onOpenIntent) },
                    enabled = !state.isSubmitting && FirstActionResolver.validationError(edited) == null,
                ) { Text(if (editor.beginAfterSaving) "确认动作，开始准备" else "保存动作") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissFirstActionEditor, enabled = !state.isSubmitting) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BrandHeader(showProfile: Boolean, onOpenProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("观已", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MirraTheme.colors.textPrimary)
            Text("Mirra", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Light, color = MirraTheme.colors.textSecondary)
        }
        if (showProfile) {
            Box(
                modifier = Modifier.size(48.dp)
                    .clip(MirraTheme.shapes.pill)
                    .background(MirraTheme.colors.surfaceHighlight.copy(alpha = .78f))
                    .clickable(onClick = onOpenProfile)
                    .semantics { contentDescription = "我的" },
                contentAlignment = Alignment.Center,
            ) {
                MirraPersonGlyph(MirraTheme.colors.textSecondary)
            }
        }
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
    onEditFirstAction: () -> Unit,
    onAbandon: () -> Unit,
) {
    when (content) {
        is StartContentState.ActiveSession -> {
            Text("正在学习", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(24.dp))
            Text(content.learningItemName, style = MaterialTheme.typography.headlineSmall)
            Text("当前第 ${content.currentPage} 页 · 已进行 ${content.elapsedMinutes} 分钟", color = MirraTheme.colors.textSecondary)
            Spacer(Modifier.height(24.dp))
            MirraPrimaryButton(onClick = { onOpenSession(content.sessionId) }, modifier = Modifier.fillMaxWidth()) { Text("继续学习") }
        }
        is StartContentState.ActiveIntent -> {
            Text("准备开始", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(24.dp))
            Text(content.learningItemName, style = MaterialTheme.typography.headlineSmall)
            Text(content.firstAction, color = MirraTheme.colors.textSecondary)
            Spacer(Modifier.height(24.dp))
            MirraPrimaryButton(onClick = { onOpenIntent(content.intentId) }, modifier = Modifier.fillMaxWidth()) { Text("继续准备") }
            MirraTextAction(onClick = onAbandon, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth()) { Text("取消本次启动") }
        }
        is StartContentState.Mainline -> {
            Text("今天继续", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MirraTheme.colors.textPrimary)
            Spacer(Modifier.height(20.dp))
            LearningItemSummary(content.item)
            MirraTextAction(onClick = onEditFirstAction, enabled = !isSubmitting) {
                Text(if (content.item.firstAction.isBlank()) "确定第一步" else "编辑第一步")
            }
            Spacer(Modifier.height(20.dp))
            MirraPrimaryButton(
                onClick = onBegin,
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(25.dp))
                Spacer(Modifier.width(8.dp))
                Text("开始准备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content.recentReading?.let {
                Spacer(Modifier.height(20.dp))
                RecentReadingStrip(it)
            }
        }
        is StartContentState.ChooseInProgress -> {
            Text("这次想学什么？", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))
            content.items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !isSubmitting) { onSelectItem(item.id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = content.selectedItemId == item.id, onClick = { onSelectItem(item.id) }, enabled = !isSubmitting)
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontWeight = FontWeight.Medium)
                        Text("上次停在第 ${item.currentPage} 页", color = MirraTheme.colors.textSecondary)
                    }
                }
                HorizontalDivider()
            }
            if (content.selectedItemId != null) {
                val selected = content.items.first { it.id == content.selectedItemId }
                Spacer(Modifier.height(12.dp))
                Text(selected.firstAction.ifBlank { "请先确定一个现在就能做的第一步。" }, color = MirraTheme.colors.textSecondary)
                MirraTextAction(onClick = onEditFirstAction, enabled = !isSubmitting) {
                    Text(if (selected.firstAction.isBlank()) "确定第一步" else "编辑第一步")
                }
                RecentReading(content.recentReading)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = content.setSelectedAsMainline, onCheckedChange = onSetAsMainline, enabled = !isSubmitting)
                    Text("设为主线")
                }
            }
            MirraPrimaryButton(onClick = onBegin, enabled = content.selectedItemId != null && !isSubmitting, modifier = Modifier.fillMaxWidth()) {
                Text("开始准备")
            }
        }
        is StartContentState.NoInProgress -> {
            Text("暂无正在学习的内容", style = MaterialTheme.typography.titleLarge)
            Text("你已有 ${content.totalItemCount} 项学习内容，可到知识页恢复或查看。")
            Spacer(Modifier.height(20.dp))
            MirraPrimaryButton(onClick = onOpenKnowledge, modifier = Modifier.fillMaxWidth()) { Text("查看学习内容") }
        }
        StartContentState.EmptyLibrary -> {
            Text("开始第一次学习", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(28.dp))
            MirraPrimaryButton(onClick = onCreateFirstLearningItem, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text("添加第一本书", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun LearningItemSummary(item: StartLearningItem) {
    MirraFocusCard(modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(20.dp)) {
            val coverWidth = (maxWidth * .29f).coerceIn(82.dp, 112.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                PlaceholderCover(item.name, Modifier.width(coverWidth).height(224.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MirraTheme.colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${item.currentPage} / ${item.totalPages} 页", color = MirraTheme.colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text("${item.progressPercent}%", color = MirraTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                    MirraProgress(progress = { item.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("上次停在第 ${item.currentPage} 页", color = MirraTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color = MirraTheme.colors.divider)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.size(26.dp).clip(MirraTheme.shapes.pill).background(MirraTheme.colors.accent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("1", color = MirraTheme.colors.onAccent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        Text("第一步", color = MirraTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        item.firstAction.ifBlank { "请先确定一个现在就能做的第一步。" },
                        color = MirraTheme.colors.textPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceholderCover(title: String, modifier: Modifier = Modifier) {
    val shades = listOf(Color(0xFFDADDE1), Color(0xFFE3E5E8), Color(0xFFD5D9DE), Color(0xFFE7E8EA))
    val shade = shades[(title.hashCode().toLong() and 0x7fffffffL).toInt() % shades.size]
    Box(modifier.clip(MirraTheme.shapes.medium).background(Brush.verticalGradient(listOf(MirraTheme.colors.surfaceHighlight, shade)))) {
        Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply {
                moveTo(0f, size.height * .80f)
                lineTo(size.width, size.height * .53f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path, color = shade.copy(alpha = .8f))
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Spacer(Modifier.height(40.dp))
            Text(title, color = MirraTheme.colors.textPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.width(22.dp).height(1.dp).background(MirraTheme.colors.textSecondary))
        }
    }
}

@Composable
private fun RecentReadingStrip(recent: RecentReadingSnapshot) {
    val text = formatRecentReading(recent, System.currentTimeMillis(), ZoneId.systemDefault())
    Row(
        modifier = Modifier.fillMaxWidth().clip(MirraTheme.shapes.large)
            .background(MirraTheme.colors.surfaceHighlight.copy(alpha = .8f))
            .heightIn(min = 62.dp).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClockMark()
        Text("最近阅读", color = MirraTheme.colors.textPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(text, modifier = Modifier.weight(1f), color = MirraTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ClockMark() {
    val color = MirraTheme.colors.accent
    Canvas(Modifier.size(20.dp)) {
        val width = 2.dp.toPx()
        drawCircle(color, style = Stroke(width = width))
        drawLine(color, center, Offset(center.x, size.height * .25f), strokeWidth = width)
        drawLine(color, center, Offset(size.width * .7f, size.height * .6f), strokeWidth = width)
    }
}

internal fun formatRecentReading(recent: RecentReadingSnapshot, nowMillis: Long, zoneId: ZoneId): String {
    val date = Instant.ofEpochMilli(recent.endedAt).atZone(zoneId).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val relativeDate = when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${date.monthValue}月${date.dayOfMonth}日"
    }
    val minutes = (recent.durationMillis.coerceAtLeast(0L) / 60_000L)
    val pages = (recent.endPage - recent.startPage).coerceAtLeast(0)
    return "$relativeDate · $minutes 分钟 · $pages 页"
}

@Composable
private fun RecentReading(recent: RecentReadingSnapshot?) {
    recent?.let {
        Text("上次阅读 ${it.durationMillis / 60_000L} 分钟 · ${it.noteCount} 条笔记", color = MirraTheme.colors.textSecondary)
    }
}
