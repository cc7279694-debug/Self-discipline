package com.guanyi.mirra.feature.wearable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import com.guanyi.mirra.domain.wearable.WearableCoordinator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WearableSettingsScreen(coordinator: WearableCoordinator?, onBack: () -> Unit) {
    val displayLocale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    var confirmEnable by remember { mutableStateOf(false) }
    var confirmControl by remember { mutableStateOf(false) }
    var confirmCatalog by remember { mutableStateOf(false) }
    var selectCatalogBooks by remember { mutableStateOf(false) }
    var draftCatalogIds by remember { mutableStateOf(emptySet<String>()) }
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
                Text("书籍离线目录", style = MaterialTheme.typography.titleLarge)
                Text("开启后，将主线及你选择的进行中书籍的名称、页码和已填写的合法第一步传到手环。目录保存后可离线学习；腕端记录目前仅保存在手环，不上传手机历史。")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (state.catalogEnabled) "已允许同步书籍" else "尚未允许")
                    Switch(checked = state.catalogEnabled, enabled = state.enabled,
                        modifier = Modifier.testTag("wearable-catalog-toggle"),
                        onCheckedChange = { if (it) confirmCatalog = true else coordinator.setCatalogEnabledFromUser(false) })
                }
                Text("主线自动加入，另外最多选择 11 本进行中的书籍。不会修改手机书籍、学习记录或统计。")
                Text("关闭目录同步仅停止更新，不会自动删除手环已保存的完整目录。")
                OutlinedButton(onClick = {
                    draftCatalogIds = state.catalogSelectedIds.toSet()
                    selectCatalogBooks = true
                    coordinator.loadCatalogChoicesFromUser()
                }, enabled = state.catalogEnabled, modifier = Modifier.fillMaxWidth().testTag("wearable-catalog-select")) {
                    Text("选择同步书籍（${state.catalogSelectedIds.size} 本）")
                }
                val catalogDescription = when (state.catalogStatus) {
                    "SENDING" -> "正在发送目录，等待手环保存"
                    "AWAITING_ACK" -> "已发送，等待手环完整保存确认"
                    "SYNCED" -> "手环已确认完整保存，可离线使用"
                    "ERROR" -> "目录保存尚未确认；手环仅使用完整保存的目录，请查看腕端提示。"
                    else -> "尚未确认保存；请在手环 Mirra 点同步书籍"
                }
                Text(catalogDescription, Modifier.testTag("wearable-catalog-status"))
                if (state.catalogEnabled && !state.catalogNegotiated) Text("当前腕端尚未协商离线目录，请打开配套正式腕端。")
                state.lastCatalogAt?.let { Text("最近目录确认：${SimpleDateFormat("HH:mm:ss", displayLocale).format(Date(it))}") }
                state.catalogError?.let { Text(catalogErrorText(it), color = MaterialTheme.colorScheme.error) }
                Text("书名完整支持 120 字、动作 160 字；超限会明确拒绝整份目录，不截断动作。同步不会改变正在进行的腕端学习快照。")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("允许手环控制学习", style = MaterialTheme.typography.titleLarge)
                Text("可选择休息 5 或 10 分钟、提前结束休息，以及确认手机已准备好的第一步并开始学习。默认关闭；关闭后仍可查看阅读状态。")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (state.controlEnabled) "已允许" else "尚未允许")
                    Switch(checked = state.controlEnabled, enabled = state.enabled,
                        modifier = Modifier.testTag("wearable-control-toggle"),
                        onCheckedChange = { if (it) confirmControl = true else coordinator.setControlsFromUser(false) })
                }
                Text(if (state.controlNegotiated) "腕端支持学习控制；操作还需要新鲜状态和有效授权。" else "当前腕端尚未协商学习控制，继续只读同步。")
            }
        }
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
        Text("手机锁屏时的可达性取决于设备与正常学习服务。开始学习若受系统后台限制，请回手机操作。手机被强行停止时，请先打开手机 Mirra。")
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
    if (confirmControl) AlertDialog(onDismissRequest = { confirmControl = false },
        title = { Text("允许手环控制学习") },
        text = { Text("只允许休息 5 或 10 分钟、提前结束休息，以及在看完实际第一步后明确确认开始。不会在手环选书、修改页码、结束学习或读取笔记。监测无法安全启动时，会保留准备并提示回手机操作。这项许可仅保存在当前手机，不随备份迁移。") },
        confirmButton = { TextButton(onClick = { confirmControl = false; coordinator?.setControlsFromUser(true) },
            modifier = Modifier.testTag("wearable-control-confirm")) { Text("允许这些操作") } },
        dismissButton = { TextButton(onClick = { confirmControl = false }) { Text("保持只读") } })
    if (confirmCatalog) AlertDialog(onDismissRequest = { confirmCatalog = false },
        title = { Text("允许同步离线书籍目录") },
        text = { Text("新增同步范围：主线和所选进行中书籍的稳定编号、完整书名、总页数、当前页码、已填写的合法第一步及主线标记。手环可离线独立学习；本阶段不上传腕端学习记录，不修改手机历史或统计。每次开始仍需在手环实际完成第一步并确认。无需新增系统权限。") },
        confirmButton = { TextButton(onClick = { confirmCatalog = false; coordinator?.setCatalogEnabledFromUser(true) },
            modifier = Modifier.testTag("wearable-catalog-confirm")) { Text("允许目录同步") } },
        dismissButton = { TextButton(onClick = { confirmCatalog = false }) { Text("暂不开启") } })
    if (selectCatalogBooks && coordinator != null) {
        val state by coordinator.state.collectAsStateWithLifecycle()
        AlertDialog(onDismissRequest = { selectCatalogBooks = false },
            title = { Text("选择离线书籍") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text("只展示进行中的书籍；主线自动加入。")
                    if (state.catalogChoicesLoading) Text("正在读取书籍…")
                    if (!state.catalogChoicesLoading && state.catalogChoices.isEmpty()) Text("暂无进行中的书籍")
                    state.catalogChoices.forEach { book ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(book.title + if (book.isMainline) "（主线）" else "", Modifier.weight(1f))
                            Checkbox(checked = book.isMainline || book.id in draftCatalogIds,
                                modifier = Modifier.testTag("wearable-catalog-book-${book.id}"),
                                enabled = !book.isMainline && (book.id in draftCatalogIds || draftCatalogIds.size < 11),
                                onCheckedChange = { checked -> draftCatalogIds = if (checked) draftCatalogIds + book.id else draftCatalogIds - book.id })
                        }
                    }
                    state.catalogError?.let { Text(catalogErrorText(it), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(enabled = !state.catalogChoicesLoading && state.catalogError == null,
                onClick = {
                    val eligible = state.catalogChoices.filterNot { it.isMainline }.map { it.id }.toSet()
                    coordinator.setCatalogBooksFromUser(draftCatalogIds.filter { it in eligible })
                    selectCatalogBooks = false
                }) { Text("保存选择") } },
            dismissButton = { TextButton(onClick = { selectCatalogBooks = false }) { Text("取消") } })
    }
}

private fun catalogErrorText(code: String) = when (code) {
    "CATALOG_TOO_LARGE" -> "目录超出手环容量，请减少所选书籍后再同步。"
    "INVALID_CATALOG" -> "有书籍的名称、动作、页码或编号超出兼容范围；未替换腕端完整目录，请在手机检查书籍。"
    "CATALOG_DISABLED" -> "书籍目录同步尚未允许。"
    "TIMEOUT" -> "未收到完整保存确认，请在手环再次同步。"
    "PERSIST_FAILED", "RESULT_UNKNOWN" -> "腕端保存结果未确认，请查看手环提示；手机没有记录为同步成功。"
    "CONFLICT" -> "目录版本冲突，未确认替换，请重新同步。"
    else -> wearableErrorText(code)
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
