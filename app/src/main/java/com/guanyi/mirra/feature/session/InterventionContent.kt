package com.guanyi.mirra.feature.session

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.guanyi.mirra.domain.monitoring.*
import com.guanyi.mirra.ui.components.*

@Composable
fun InterventionContent(bookName: String, prompt: InterventionUiModel, reasonsVisible: Boolean,
    onOpenReasons: () -> Unit, onReason: (AllowanceReason) -> Unit, onReturn: () -> Unit,
    onGrant: () -> Unit, onFinish: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (bookName.isBlank()) "还在阅读" else "还在读《$bookName》", style = MaterialTheme.typography.titleLarge)
        if (reasonsVisible) {
            prompt.allowanceOptions.forEach { option ->
                MirraSecondaryButton({ onReason(option.reason) }, Modifier.fillMaxWidth()) {
                    Text("${option.reason.label} · ${option.durationMillis / 60_000} 分钟" + if (prompt.reason == option.reason) " · 已选" else "")
                }
            }
            MirraPrimaryButton(onGrant, Modifier.fillMaxWidth(),
                enabled = prompt.reason != null && prompt.remainingWaitMillis == 0L) {
                Text(if (prompt.remainingWaitMillis > 0) "再等 ${(prompt.remainingWaitMillis + 999) / 1_000} 秒" else "开始临时使用")
            }
            MirraTextAction(onReturn, Modifier.fillMaxWidth()) { Text("回到学习") }
        } else {
            MirraPrimaryButton(onReturn, Modifier.fillMaxWidth()) { Text("回到学习") }
            MirraSecondaryButton(onOpenReasons, Modifier.fillMaxWidth()) { Text("临时使用") }
        }
        MirraTextAction(onFinish, Modifier.fillMaxWidth()) { Text("结束本次学习") }
        MirraTextAction(onDismiss, Modifier.fillMaxWidth()) { Text("关闭") }
    }
}

private val AllowanceReason.label: String get() = when (this) {
    AllowanceReason.REPLY -> "回复消息"
    AllowanceReason.RESEARCH -> "查资料"
    AllowanceReason.TEMPORARY_TASK -> "临时处理事情"
    AllowanceReason.CASUAL -> "随便看看"
}
