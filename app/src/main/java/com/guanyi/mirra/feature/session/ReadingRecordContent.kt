package com.guanyi.mirra.feature.session

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guanyi.mirra.data.local.entity.DndLifecycle
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.domain.HumanReadingSegment
import com.guanyi.mirra.domain.ReadingRecordProjection
import com.guanyi.mirra.domain.TimelineTrust
import com.guanyi.mirra.feature.profile.DndUserActions
import com.guanyi.mirra.ui.components.MirraPrimaryButton
import com.guanyi.mirra.ui.components.MirraSecondaryButton
import com.guanyi.mirra.ui.components.MirraTextAction
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ReadingRecordContent(record: ReadingRecordProjection, expanded: Boolean, onToggleTimeline: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MirraTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(record.endPage?.let { "${record.startPage} → $it 页 · ${record.pagesRead} 页" } ?: "结束页码未记录",
            style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
        Text("阅读时长：${record.totalDurationMillis?.let(::recordDuration) ?: "未记录"}", color = colors.textPrimary)
        Text("${record.noteCount} 条笔记", color = colors.textSecondary)
        when {
            record.trust == TimelineTrust.COMPLETE_TRUSTED -> {
                record.effectiveFocusMillis?.let { Text("有效专注时间：${recordDuration(it)}", color = colors.textPrimary) }
                record.wholeSessionCounts?.let { Text("休息 ${it.breaks} 次 · 临时使用 ${it.allowances} 次 · 分心 ${it.distractions} 次", color = colors.textSecondary) }
            }
            record.endType == SessionEndType.ABNORMAL -> Text("异常结束 · 不参与有效统计", color = colors.textSecondary)
            record.trust == TimelineTrust.SESSION_INELIGIBLE -> Text("本次阅读未正常结束，未生成有效专注时间", color = colors.textSecondary)
            record.monitoringStatus == MonitoringCoverage.NONE -> Text("未开启手机监测", color = colors.textSecondary)
            record.trust == TimelineTrust.STRUCTURE_INVALID -> Text("本次记录不完整，未生成有效专注时间", color = colors.textSecondary)
            else -> Text("本次手机监测不完整，未生成有效专注时间", color = colors.textSecondary)
        }
        MirraTextAction(onClick = onToggleTimeline) { Text(if (expanded) "收起本次记录" else "查看本次记录") }
        if (expanded) {
            if (record.timeline.isEmpty()) Text("没有可查看的时间片段", color = colors.textSecondary)
            val formatter = DateTimeFormatter.ofPattern("M月d日 HH:mm:ss").withZone(ZoneId.systemDefault())
            record.timeline.forEach { interval ->
                HorizontalDivider(color = colors.divider)
                val label = when (interval.type) {
                    HumanReadingSegment.READING -> "阅读"
                    HumanReadingSegment.BREAK -> "休息"
                    HumanReadingSegment.TEMPORARY_USE -> "临时使用"
                    HumanReadingSegment.DISTRACTION -> "分心"
                    HumanReadingSegment.RECOVERING -> "正在回到学习"
                    HumanReadingSegment.UNMONITORED -> "监测中断"
                }
                Text(interval.appLabel?.let { "$label · $it" } ?: label, color = colors.textPrimary)
                Text("${formatter.format(Instant.ofEpochMilli(interval.startedAt))}–${formatter.format(Instant.ofEpochMilli(interval.endedAt))}", color = colors.textSecondary)
            }
        }
    }
}

/** Expanding is local UI state, never a navigation operation. */
@Composable
internal fun ReadingRecordPage(viewModel: ReadingRecordViewModel, justSaved: Boolean, onExit: () -> Unit, dndActions: DndUserActions? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var expanded by rememberSaveable(viewModel.sessionId) { mutableStateOf(false) }
    val record = (state as? ReadingRecordUiState.Ready)?.record
    val title = if (justSaved && record?.endType == SessionEndType.NORMAL) "本次阅读已保存" else "阅读记录"
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(vertical = 24.dp)) {
        item { Text(title, style = MaterialTheme.typography.headlineMedium, color = MirraTheme.colors.textPrimary) }
        item {
            when (val current = state) {
                ReadingRecordUiState.Loading -> Text("正在读取记录…", color = MirraTheme.colors.textSecondary)
                ReadingRecordUiState.Missing -> Text("记录不存在", color = MirraTheme.colors.textSecondary)
                ReadingRecordUiState.Error -> {
                    Text("暂时无法读取记录", color = MirraTheme.colors.textSecondary)
                    MirraTextAction(viewModel::retry) { Text("重试读取") }
                }
                is ReadingRecordUiState.Ready -> ReadingRecordContent(current.record, expanded, { expanded = !expanded })
            }
        }
        if (justSaved && dndActions != null && record?.dndLifecycle in setOf(DndLifecycle.RELEASE_PENDING, DndLifecycle.RELEASE_FAILED)) {
            item { ReadingRecordDndWarning(dndActions) }
        }
        item {
            if (justSaved) MirraPrimaryButton(onExit, Modifier.fillMaxWidth()) { Text("完成") }
            else MirraSecondaryButton(onExit, Modifier.fillMaxWidth()) { Text("返回") }
        }
    }
}

@Composable
private fun ReadingRecordDndWarning(actions: DndUserActions) {
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var retrying by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun refresh() { scope.launch { try { actions.refresh() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true } } }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    DisposableEffect(owner, actions) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Mirra 勿扰状态需要处理", color = MirraTheme.colors.textSecondary)
        if (failed) Text("暂时无法处理勿扰，请重试", color = MirraTheme.colors.textSecondary)
        MirraTextAction(onClick = { scope.launch {
            retrying = true; failed = false
            try { actions.retryRelease() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { retrying = false }
        } }, enabled = !retrying) { Text(if (retrying) "正在处理…" else "重试释放勿扰") }
        MirraTextAction(onClick = {
            try { settings.launch(actions.settingsIntent()) }
            catch (_: Exception) { failed = true }
        }) { Text("系统勿扰设置") }
    }
}

internal fun recordDuration(millis: Long): String {
    if (millis == 0L) return "0 分钟"
    val minutes = millis / 60_000
    return when {
        minutes == 0L -> "不足 1 分钟"
        minutes < 60 -> "$minutes 分钟"
        minutes % 60 == 0L -> "${minutes / 60} 小时"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
    }
}
