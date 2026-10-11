package com.guanyi.mirra.feature.wearable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import com.guanyi.mirra.domain.wearable.WearableCoordinator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WearableSettingsScreen(coordinator: WearableCoordinator?, onBack: () -> Unit) {
    var confirmEnable by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("手环伴侣", style = MaterialTheme.typography.headlineLarge)
        Text("在手腕上看一眼，再回到阅读。", style = MaterialTheme.typography.bodyLarge)
        Text("只同步当前书籍、第一步动作、页码、学习状态与时间。不读取笔记正文，不上传 Mirra 服务器。")
        if (coordinator == null) { Text("手机数据正在准备，请稍后重新打开。"); return@Column }
        LifecycleStartEffect(coordinator) {
            onStopOrDispose { coordinator.cancelWearAppCheck() }
        }
        val state by coordinator.state.collectAsStateWithLifecycle()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.enabled) "手环伴侣已启用" else "尚未启用手环伴侣", style = MaterialTheme.typography.titleLarge)
                Text("设备：${state.deviceName ?: "尚未选择"}", Modifier.testTag("wearable-device"))
                Text("小米手机服务：${if (state.serviceAvailable) "可用" else "尚不可用"}")
                Text("设备授权：${if (state.authorized) "已授权" else "未确认"}")
                Text("只读握手：${if (state.handshaken) "已完成只读握手" else "等待打开 Mirra Band"}", Modifier.testTag("wearable-handshake"))
                val sourceDescription = when (state.errorCategory) {
                    "SOURCE_BUSY" -> "手机正在处理数据，暂时停止同步"
                    "SOURCE_UNAVAILABLE" -> "暂不可读取"
                    else -> if (state.sourceReady) "可读取" else "手机正在处理数据，暂时停止同步"
                }
                Text("学习数据：$sourceDescription")
                Text("最近完整同步：${state.lastSnapshotAt?.let { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it)) } ?: "尚无"}")
                state.errorCategory?.let { Text(wearableErrorText(it), color = MaterialTheme.colorScheme.error) }
                val appCheckDescription = when {
                    state.appCheckInProgress -> "正在检查"
                    state.appCheckError != null -> "未能完成检查"
                    state.appInstalled == true -> "小米服务报告已安装"
                    state.appInstalled == false -> "小米服务报告未安装"
                    else -> "尚未检查"
                }
                Text("腕端安装检查：$appCheckDescription", Modifier.testTag("wearable-app-check-result"))
                state.appCheckError?.let { category ->
                    Text("${wearableErrorText(category)}（$category）", Modifier.testTag("wearable-app-check-error"),
                        color = MaterialTheme.colorScheme.error)
                }
                Text("安装检查不代表签名指纹核对、消息握手或完整同步。")
                if (!state.enabled || !state.serviceAvailable || !state.authorized) {
                    Text("需先启用伴侣、重新连接设备并确认授权，才能检查腕端应用。")
                }
                OutlinedButton(onClick = coordinator::checkWearAppFromUser,
                    enabled = state.enabled && state.serviceAvailable && state.authorized && !state.appCheckInProgress,
                    modifier = Modifier.fillMaxWidth().testTag("wearable-app-check")) { Text("检查腕端应用") }
            }
        }
        Text("需要手机上的小米配套服务与已经配对的设备。启用后会显示小米提供的设备消息授权页。手环退出后暂停查询，再次打开时重新获取数据。")
        Text("手机锁屏时的可达性取决于当前设备与正常学习服务；尚未通过当前设备验证。手机被强行停止时，请先打开手机 Mirra。")
        if (!state.enabled) {
            Button(onClick = { confirmEnable = true }, modifier = Modifier.fillMaxWidth().testTag("wearable-enable")) { Text("启用手环伴侣") }
        } else {
            Button(onClick = coordinator::reconnectFromUser, modifier = Modifier.fillMaxWidth().testTag("wearable-reconnect")) { Text("重新连接设备") }
            OutlinedButton(onClick = coordinator::disableFromUser, modifier = Modifier.fillMaxWidth().testTag("wearable-disable")) { Text("关闭手环伴侣") }
        }
    }
    if (confirmEnable) AlertDialog(onDismissRequest = { confirmEnable = false },
        title = { Text("启用只读手环伴侣") },
        text = { Text("仅发送当前阅读信息。小米的首次授权可能同时包含通知能力；Mirra 仅请求设备消息，不主动发通知，也不读取健康、定位或麦克风。关闭伴侣不会停止学习或改变分心监测设置。") },
        confirmButton = { TextButton(onClick = { confirmEnable = false; coordinator?.enableFromUser() }) { Text("继续授权") } },
        dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("暂不开启") } })
}

private fun wearableErrorText(category: String): String = when (category) {
    "NO_DEVICE" -> "未发现设备，请先在小米配套应用中完成配对。"
    "MULTIPLE_DEVICES" -> "发现多个设备，请在小米配套应用中明确当前目标设备。"
    "NOT_AUTHORIZED", "AUTHORIZATION_DENIED" -> "设备消息尚未授权，可重新连接并完成授权。"
    "TIMEOUT", "SERVICE_UNAVAILABLE" -> "小米服务暂不可达，请打开配套应用后重试。"
    "CLEANUP_PENDING" -> "同步连接尚未安全释放，请在本次学习结束后重启 Mirra 再试。"
    "SIGNING_IDENTITY_MISMATCH" -> "手机与腕端签名身份不匹配，请核对配套安装包。"
    "COUNTERPART_APP_MISSING" -> "设备上未发现配套应用，请检查腕端 Mirra 安装。"
    "SETTINGS_UNAVAILABLE" -> "伴侣设置保存失败，请稍后重试。"
    "SOURCE_BUSY" -> "手机正在处理数据，稍后重新打开腕端应用。"
    "SOURCE_UNAVAILABLE" -> "手机学习数据暂不可用，请稍后重试。"
    else -> "同步暂不可用，请检查配对与授权后重试。"
}
