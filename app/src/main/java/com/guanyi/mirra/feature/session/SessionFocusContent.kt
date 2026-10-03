package com.guanyi.mirra.feature.session

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.domain.monitoring.FocusStatusUiModel
import com.guanyi.mirra.ui.components.MirraTextAction

@Composable
fun SessionFocusContent(status: FocusStatusUiModel, onBreak: () -> Unit, onFinishBreak: () -> Unit,
    onFinishAllowance: () -> Unit, onExtendAllowance: () -> Unit) {
    var extendConfirmation by remember(status.segmentId) { mutableStateOf(false) }
    val title = when (status.type) {
        SessionSegmentType.FOCUS, SessionSegmentType.DEEP_FOCUS -> "阅读中"
        SessionSegmentType.BREAK -> "休息中 · ${remainingTime(status.remainingMillis)}"
        SessionSegmentType.TEMPORARY_ALLOWANCE -> "临时使用 · ${remainingTime(status.remainingMillis)}"
        SessionSegmentType.DISTRACTION -> "暂时离开学习"
        SessionSegmentType.RECOVERY -> "正在回到学习"
        SessionSegmentType.UNMONITORED -> if (status.coverage == MonitoringCoverage.PARTIAL) "监测已中断" else "本次未开启分心监测"
        null -> "阅读中"
    }
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        when (status.type) {
            SessionSegmentType.BREAK -> MirraTextAction(onFinishBreak) { Text("提前结束休息") }
            SessionSegmentType.TEMPORARY_ALLOWANCE -> {
                MirraTextAction(onFinishAllowance) { Text("提前结束") }
                if (status.canExtend) MirraTextAction({ extendConfirmation = true }) { Text("延长 2 分钟") }
            }
            else -> MirraTextAction(onBreak, enabled = status.segmentId != null) { Text("休息") }
        }
    }
    if (extendConfirmation && status.canExtend && status.type == SessionSegmentType.TEMPORARY_ALLOWANCE) {
        AlertDialog(onDismissRequest = { extendConfirmation = false }, title = { Text("延长临时使用") },
            text = { Text("本次只可延长一次，增加 2 分钟") },
            confirmButton = { MirraTextAction({ extendConfirmation = false; onExtendAllowance() }) { Text("确认延长") } },
            dismissButton = { MirraTextAction({ extendConfirmation = false }) { Text("取消") } })
    }
}

private fun remainingTime(millis: Long?): String {
    val seconds = ((millis ?: 0).coerceAtLeast(0) + 999) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
