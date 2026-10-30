package com.guanyi.mirra.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guanyi.mirra.data.local.entity.NoteEntity
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.local.model.ReadingRecordSource
import com.guanyi.mirra.feature.profile.LearningProtectionMonitoringUiState
import com.guanyi.mirra.feature.profile.LearningProtectionServiceStatus
import com.guanyi.mirra.platform.focus.CapabilityStatus
import com.guanyi.mirra.ui.components.MirraTextAction
import com.guanyi.mirra.ui.theme.MirraTheme

/** Presentation slots reuse the existing actions; this page owns no learning state machine. */
@Composable
internal fun SessionReadingStage(
    bookName: String,
    currentPage: Int?,
    elapsed: String,
    monitoring: LearningProtectionMonitoringUiState,
    focusDisplay: Boolean,
    controlsVisible: Boolean,
    canEdit: Boolean,
    canStartBreak: Boolean,
    onBack: () -> Unit,
    onPage: () -> Unit,
    onNotes: () -> Unit,
    onDetails: () -> Unit,
    onRepairMonitoring: () -> Unit,
    onBreak: () -> Unit,
    onFinish: () -> Unit,
    onEnterFocus: () -> Unit,
    onExitFocus: () -> Unit,
    onToggleControls: () -> Unit,
    error: String?,
    stateContent: @Composable () -> Unit,
    interventionContent: @Composable () -> Unit,
) {
    val colors = MirraTheme.colors
    val foreground = if (focusDisplay) Color(0xFFB8BEC8) else colors.textPrimary
    val secondary = if (focusDisplay) Color(0xFF929AA7) else colors.textSecondary
    val background = if (focusDisplay) Color(0xFF101216) else colors.background
    val showControls = !focusDisplay || controlsVisible || error != null
    BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
        val compact = maxHeight < 550.dp || LocalDensity.current.fontScale > 1.4f
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(if (focusDisplay) onExitFocus else onBack,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = secondary)) {
                    Text(if (focusDisplay) "退出专注显示" else "返回")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onPage, enabled = canEdit,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("session-page-action"),
                    colors = ButtonDefaults.textButtonColors(contentColor = secondary)) {
                    Text(currentPage?.let { "第 $it 页 · 编辑" } ?: "编辑页码")
                }
            }
            Text(bookName.ifBlank { "正在阅读" }, Modifier.fillMaxWidth().padding(top = 6.dp),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, color = secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(if (compact) 20.dp else 56.dp))
            Text("本次阅读", color = secondary, style = MaterialTheme.typography.labelLarge)
            BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                val scale = LocalDensity.current.fontScale
                val fittingSize = (maxWidth.value / (elapsed.length.coerceAtLeast(5) * .63f * scale)).coerceAtMost(88f)
                val view = LocalView.current
                Text(elapsed, fontSize = fittingSize.sp, lineHeight = (fittingSize * 1.15f).sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, color = foreground,
                    textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.testTag("session-reading-timer").clearAndSetSemantics {
                        // Stable semantics: ticks are not live-region announcements. Read the value only on request.
                        contentDescription = "本次阅读计时"
                        onClick("朗读当前用时") {
                            @Suppress("DEPRECATION")
                            view.announceForAccessibility("本次阅读，$elapsed")
                            true
                        }
                    })
            }
            Text(sessionMonitoringHeadline(monitoring), color = secondary, style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center, modifier = Modifier.testTag("session-monitoring-status"))
            if (showControls) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                    TextButton(onDetails, Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = secondary)) { Text("本场详情") }
                    if (monitoring.coverage in setOf(com.guanyi.mirra.data.local.entity.MonitoringCoverage.NONE,
                            com.guanyi.mirra.data.local.entity.MonitoringCoverage.PARTIAL) ||
                        monitoring.serviceStatus in setOf(LearningProtectionServiceStatus.STOPPED, LearningProtectionServiceStatus.UNAVAILABLE)) {
                        TextButton(onRepairMonitoring, Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = secondary)) { Text("检查学习保护") }
                    }
                }
                if (focusDisplay) {
                    // A local Material palette, not MirraTheme (which owns Activity bar styling).
                    MaterialTheme(colorScheme = darkColorScheme(primary = foreground, secondary = foreground,
                        surface = Color(0xFF1B2027), onSurface = foreground, onSurfaceVariant = secondary)) {
                        CompositionLocalProvider(LocalContentColor provides foreground) { stateContent() }
                    }
                } else CompositionLocalProvider(LocalContentColor provides foreground) { stateContent() }
            }
            if (focusDisplay) {
                Spacer(Modifier.height(28.dp))
                TextButton(onToggleControls, Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = secondary)) {
                    Text(if (controlsVisible) "收起控制" else "显示控制")
                }
            }
            if (showControls) {
                Spacer(Modifier.height(if (compact) 16.dp else 40.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onNotes, enabled = canEdit,
                        modifier = Modifier.heightIn(min = 52.dp).testTag("session-note-action"),
                        shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (focusDisplay) Color(0xFF252A32) else colors.accentSoft,
                            contentColor = foreground)) { Text("记录想法") }
                    if (canStartBreak) OutlinedButton(onBreak, enabled = canEdit, modifier = Modifier.heightIn(min = 52.dp),
                        shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground)) {
                        Text("休息")
                    }
                }
                if (!focusDisplay) TextButton(onEnterFocus, enabled = canEdit,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("session-focus-display-action")) { Text("专注显示") }
                error?.let { Text(it, color = if (focusDisplay) Color(0xFFF2A39E) else colors.danger,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp)) }
                Box(Modifier.fillMaxWidth().padding(top = 8.dp)) { interventionContent() }
                TextButton(onFinish, enabled = canEdit,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("session-end-action"),
                    colors = ButtonDefaults.textButtonColors(contentColor = secondary)) { Text("结束阅读") }
            }
            if (focusDisplay) Text("常亮显示会增加耗电；按返回即可退出。", color = secondary,
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionNoteSheet(viewModel: SessionViewModel, notes: List<NoteEntity>, canEdit: Boolean,
    onDismiss: () -> Unit, onOpenNote: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MirraTheme.colors.background) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).verticalScroll(rememberScrollState())
            .imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("记录想法", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text("收起笔记") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NoteSemanticType.entries.forEach { type ->
                    FilterChip(selected = viewModel.draftType == type, enabled = canEdit,
                        onClick = { viewModel.changeType(type) }, label = { Text(type.label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            OutlinedTextField(viewModel.draftContent, viewModel::changeContent, label = { Text("写下摘录或想法") },
                enabled = canEdit, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
            OutlinedTextField(viewModel.draftPage, viewModel::changePage, label = { Text("笔记页码（可选）") },
                enabled = canEdit, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (viewModel.savedMessage.isNotEmpty()) Text(viewModel.savedMessage, color = MirraTheme.colors.textSecondary)
            viewModel.error?.let { Text(it, color = MirraTheme.colors.danger) }
            OutlinedButton(viewModel::saveAndContinue, enabled = canEdit && viewModel.draftContent.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保存并记下一条") }
            if (notes.isNotEmpty()) {
                Text("本次笔记 · ${notes.size}", style = MaterialTheme.typography.titleSmall)
                Text("打开笔记可查看、添加图片与关联主题。", style = MaterialTheme.typography.bodySmall,
                    color = MirraTheme.colors.textSecondary)
                notes.forEach { note ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = canEdit) { onOpenNote(note.id) }
                        .padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(note.semanticType.label, style = MaterialTheme.typography.labelLarge, color = MirraTheme.colors.textSecondary)
                        Text(note.content, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        note.pageNumber?.let { Text("第 $it 页", color = MirraTheme.colors.textSecondary) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SessionMonitoringDetails(state: LearningProtectionMonitoringUiState, record: ReadingRecordSource?,
    onDismiss: () -> Unit, onOpenLearningProtection: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("本场详情") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("系统权限：" + when (state.usageStatus) {
                CapabilityStatus.AVAILABLE -> "已授权"
                CapabilityStatus.NEEDS_USER_ACTION -> "未授权"
                else -> "暂不可确认"
            })
            Text("本场服务：" + when (state.serviceStatus) {
                LearningProtectionServiceStatus.READY -> "服务已就绪，当前查询健康"
                LearningProtectionServiceStatus.STOPPED -> "已停止"
                LearningProtectionServiceStatus.PREPARING -> "正在准备"
                LearningProtectionServiceStatus.UNAVAILABLE -> "暂不可用"
                else -> "暂不可确认"
            })
            Text("本场记录资格：${state.coverage?.name ?: "暂不可确认"}")
            Text("这不是有效专注时长；休息、临时使用和未知时间仍按原记录保留。",
                style = MaterialTheme.typography.bodySmall, color = MirraTheme.colors.textSecondary)
            Text("本场风险 App", style = MaterialTheme.typography.titleSmall)
            if (record == null) Text("本场名单暂不可确认")
            else if (record.riskSnapshots.isEmpty()) Text("本场没有选择风险 App")
            else record.riskSnapshots.forEach { Text(it.labelSnapshot.ifBlank { "风险 App" }) }
            Text("使用开始时保存的名单；修改长期配置只影响下一次学习。", style = MaterialTheme.typography.bodySmall,
                color = MirraTheme.colors.textSecondary)
        }
    }, confirmButton = { MirraTextAction(onDismiss) { Text("完成") } }, dismissButton = {
        MirraTextAction({ onDismiss(); onOpenLearningProtection() }) { Text("检查学习保护") }
    })
}
