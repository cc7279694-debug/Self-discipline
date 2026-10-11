package com.guanyi.mirra.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guanyi.mirra.data.local.entity.RiskAppEntity
import com.guanyi.mirra.data.repository.FocusRepository
import com.guanyi.mirra.platform.focus.LaunchableRiskApp
import com.guanyi.mirra.platform.focus.RiskAppCatalog
import com.guanyi.mirra.ui.components.MirraTextAction
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SavedRiskAppsState(val apps: List<RiskAppEntity> = emptyList(),
    val loaded: Boolean = false, val error: String? = null)

@Composable
fun RiskAppsSettings(repository: FocusRepository, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var readGeneration by remember { mutableIntStateOf(0) }
    val savedFlow = remember(repository, readGeneration) {
        repository.observeRiskApps().map { SavedRiskAppsState(it, loaded = true) }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(SavedRiskAppsState(error = "风险 App 暂时无法读取"))
        }
    }
    val saved by savedFlow.collectAsStateWithLifecycle(initialValue = SavedRiskAppsState())
    var showEditor by remember { mutableStateOf(false) }
    var catalogGeneration by remember { mutableIntStateOf(0) }
    var catalog by remember { mutableStateOf<List<LaunchableRiskApp>>(emptyList()) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var savingPackage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showEditor, catalogGeneration) {
        if (showEditor) {
            try {
                catalog = withContext(Dispatchers.IO) { RiskAppCatalog(context).list() }
                catalogError = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { catalogError = "暂时无法读取可选 App，请重试" }
        }
    }
    val selected = saved.apps.map { it.packageName }.toSet()
    fun toggle(app: LaunchableRiskApp) {
        if (savingPackage != null || !saved.loaded || saved.error != null) return
        savingPackage = app.packageName
        saveError = null
        scope.launch {
            try {
                if (app.packageName in selected) repository.removeRiskApp(app.packageName)
                else repository.replaceRiskApp(app.packageName, app.label)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { saveError = "风险 App 保存失败，请重试" }
            finally { savingPackage = null }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("风险 App", style = MaterialTheme.typography.titleLarge)
        Text("修改影响下一次学习，本次仍使用开始时的选择。",
            style = MaterialTheme.typography.bodySmall, color = MirraTheme.colors.textSecondary)
        if (saved.loaded && saved.apps.isEmpty()) Text("还没有选择风险 App", color = MirraTheme.colors.textSecondary)
        saved.apps.forEach { app -> Text(app.labelSnapshot.ifBlank { app.packageName },
            color = MirraTheme.colors.textPrimary, modifier = Modifier.testTag("risk-app-${app.packageName}")) }
        saved.error?.let {
            Text(it, color = MirraTheme.colors.danger)
            MirraTextAction({ readGeneration++ }) { Text("重试读取风险 App") }
        }
        if (!showEditor) saveError?.let { Text(it, color = MirraTheme.colors.danger) }
        MirraTextAction({ showEditor = true }, enabled = saved.loaded && saved.error == null) { Text("编辑风险 App") }
    }
    if (showEditor) AlertDialog(
        onDismissRequest = { showEditor = false }, title = { Text("选择风险 App") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("仅影响下一次学习；当前学习使用开始时的选择。", color = MirraTheme.colors.textSecondary)
                val options = (saved.apps.map { LaunchableRiskApp(it.packageName, it.labelSnapshot) } + catalog)
                    .distinctBy { it.packageName }.sortedBy { it.label }
                catalogError?.let {
                    Text(it, color = MirraTheme.colors.danger)
                    MirraTextAction({ catalogGeneration++ }) { Text("重试读取可选 App") }
                }
                if (options.isEmpty() && catalogError == null) Text("没有可选的 App")
                options.forEach { app ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("risk-editor-${app.packageName}").clickable(
                        enabled = savingPackage == null && saved.loaded && saved.error == null,
                        onClick = { toggle(app) },
                    ).padding(vertical = 6.dp)) {
                        Checkbox(checked = app.packageName in selected, onCheckedChange = null)
                        Text(app.label.ifBlank { app.packageName }, modifier = Modifier.weight(1f).padding(top = 12.dp),
                            color = MirraTheme.colors.textPrimary)
                    }
                }
                if (savingPackage != null) Text("正在保存选择…", color = MirraTheme.colors.textSecondary)
                saveError?.let { Text(it, color = MirraTheme.colors.danger) }
            }
        },
        confirmButton = { MirraTextAction({ showEditor = false }) { Text("完成") } },
    )
}
