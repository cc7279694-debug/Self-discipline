package com.guanyi.mirra.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.guanyi.mirra.data.local.entity.DndLifecycle
import com.guanyi.mirra.ui.components.MirraTextAction
import com.guanyi.mirra.ui.components.MirraToggle
import com.guanyi.mirra.ui.theme.MirraTheme

@Composable
fun DndSettingsContent(
    state: DndSettingsUiState,
    onEnabledChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onRetryApply: () -> Unit,
    onRetryRelease: () -> Unit,
    modifier: Modifier = Modifier,
    showHeading: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeading) Text("学习保护", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("学习时自动开启勿扰", color = MirraTheme.colors.textPrimary)
                Text(
                    when (state.status) {
                        DndPreferenceStatus.OFF -> "关闭"
                        DndPreferenceStatus.READY -> "已授权"
                        DndPreferenceStatus.NEEDS_ACCESS -> "需要系统授权"
                    },
                    color = MirraTheme.colors.textSecondary,
                )
            }
            MirraToggle(state.enabled, onEnabledChange, Modifier.testTag("dnd-toggle"))
        }
        if (state.enabled) Text(state.versionExplanation, color = MirraTheme.colors.textTertiary,
            style = MaterialTheme.typography.bodySmall)
        if (state.enabled && !state.policyAccessGranted) {
            Text("需要系统勿扰权限", color = MirraTheme.colors.textSecondary)
            MirraTextAction(onOpenSettings) { Text("去授权") }
        }
        if (state.showNextSessionHint) Text("修改将在下一次学习时生效", color = MirraTheme.colors.textTertiary,
            style = MaterialTheme.typography.bodySmall)
        when (state.activeLifecycle) {
            DndLifecycle.NOT_APPLIED -> Text("本次未启用勿扰", color = MirraTheme.colors.textSecondary)
            DndLifecycle.ACTIVE -> Text("本次勿扰已开启", color = MirraTheme.colors.textSecondary)
            DndLifecycle.APPLY_FAILED -> {
                Text("本次勿扰未能开启", color = MirraTheme.colors.danger)
                MirraTextAction(onRetryApply) { Text("重试开启勿扰") }
            }
            DndLifecycle.RELEASE_PENDING, DndLifecycle.RELEASE_FAILED -> {
                Text("Mirra 勿扰状态需要处理", color = MirraTheme.colors.danger)
                MirraTextAction(onRetryRelease) { Text("重试") }
            }
            DndLifecycle.RELEASED, null -> Unit
        }
        if (state.pendingRelease) {
            Text("Mirra 勿扰状态需要处理", color = MirraTheme.colors.danger)
            MirraTextAction(onRetryRelease) { Text("重试") }
            if (!state.policyAccessGranted) MirraTextAction(onOpenSettings) { Text("去授权") }
        }
    }
}
