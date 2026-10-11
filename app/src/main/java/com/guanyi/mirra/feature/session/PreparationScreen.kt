package com.guanyi.mirra.feature.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.domain.SessionStartCoordinator
import com.guanyi.mirra.domain.StartMonitoringResult
import com.guanyi.mirra.domain.FirstActionResolver
import com.guanyi.mirra.domain.IntentExpiryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import com.guanyi.mirra.ui.components.MirraPrimaryButton
import com.guanyi.mirra.ui.components.MirraSecondaryButton
import com.guanyi.mirra.ui.components.MirraTextAction
import com.guanyi.mirra.ui.components.MirraFocusCard
import com.guanyi.mirra.ui.components.FirstActionInput
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

data class PreparationUiState(
    val intent: StudyIntentEntity? = null,
    val item: LearningItemEntity? = null,
    val waitingMillis: Long = 0,
    val isExpired: Boolean = false,
) {
    val firstAction: String get() = item?.let(FirstActionResolver::resolve).orEmpty()
    val canConfirm: Boolean get() = intent != null && item?.status == LearningItemStatus.IN_PROGRESS &&
        firstAction.isNotEmpty()
}

class PreparationViewModel(
    private val intentId: String,
    private val workflow: StudyWorkflowRepository,
    private val learningItems: LearningItemRepository,
    private val sessionStartCoordinator: SessionStartCoordinator,
    private val clock: () -> Long = System::currentTimeMillis,
    private val expiryPolicy: IntentExpiryPolicy = IntentExpiryPolicy(),
) : ViewModel() {
    var error by mutableStateOf<String?>(null)
        private set
    var abandoning by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var editingFirstAction by mutableStateOf(false)
        private set
    var savingFirstAction by mutableStateOf(false)
        private set
    private val nowMillis = MutableStateFlow(clock())

    val uiState = combine(workflow.observeActiveIntent(), learningItems.observeAll(), nowMillis) { intent, items, now ->
        val selected = intent?.takeIf { it.id == intentId }
        PreparationUiState(selected, items.firstOrNull { it.id == selected?.learningItemId },
            selected?.let { (now - it.createdAt).coerceAtLeast(0) } ?: 0,
            selected?.let { expiryPolicy.isExpired(it.createdAt, now) } ?: false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PreparationUiState())

    init {
        viewModelScope.launch { runCatching { workflow.markTransitioned(intentId) } }
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                nowMillis.value = clock()
            }
        }
    }

    fun start(onStarted: (String, Boolean) -> Unit) {
        if (starting || abandoning || editingFirstAction || savingFirstAction || uiState.value.intent?.id != intentId) return
        val item = uiState.value.item ?: return
        starting = true
        error = null
        viewModelScope.launch {
            try {
                checkNotNull(workflow.observeActiveIntent().first()?.takeIf {
                    it.id == intentId && it.learningItemId == item.id && it.outcome == null &&
                        it.convertedAt == null && it.endedAt == null
                }) { "这次启动已结束，请重新准备" }
                val persisted = checkNotNull(learningItems.get(item.id)) { "Learning Item 不存在" }
                check(persisted.status == LearningItemStatus.IN_PROGRESS) { "只有进行中的内容可以开始" }
                val chosenAction = FirstActionResolver.requireChosen(persisted.firstAction)
                check(chosenAction == FirstActionResolver.resolve(item)) { "第一步动作刚刚更新，请查看后再次确认" }
                val result = sessionStartCoordinator.start(intentId, persisted.currentPage)
                onStarted(result.session.id, result.monitoring == StartMonitoringResult.UNMONITORED ||
                    result.monitoring == StartMonitoringResult.DEGRADED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "无法开始 Session"
            } finally {
                starting = false
            }
        }
    }

    fun editFirstAction() {
        if (starting || abandoning || savingFirstAction || uiState.value.intent?.id != intentId) return
        if (uiState.value.isExpired) {
            error = "准备已超时，不能修改第一步；可以确认结束本次启动，或取消后重新准备"
            return
        }
        error = null
        editingFirstAction = true
    }

    fun dismissFirstActionEditor() {
        if (!savingFirstAction) editingFirstAction = false
    }

    fun saveFirstAction(action: String) {
        if (!editingFirstAction || savingFirstAction || starting || abandoning) return
        savingFirstAction = true
        error = null
        viewModelScope.launch {
            try {
                learningItems.updatePreparationFirstAction(intentId, action)
                editingFirstAction = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "无法保存第一步动作"
            } finally {
                savingFirstAction = false
            }
        }
    }

    fun abandon(onAbandoned: () -> Unit) {
        if (abandoning || starting || savingFirstAction) return
        abandoning = true
        viewModelScope.launch {
            try {
                workflow.abandonIntent(intentId)
                onAbandoned()
            } catch (cancelled: CancellationException) {
                abandoning = false
                throw cancelled
            } catch (failure: Exception) {
                abandoning = false
                error = failure.message ?: "无法取消本次启动"
            }
        }
    }
}

@Composable
fun PreparationScreen(
    viewModel: PreparationViewModel,
    onStarted: (String) -> Unit,
    onAbandoned: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val item = state.item
    val busy = viewModel.starting || viewModel.abandoning || viewModel.savingFirstAction
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column {
            Text("启动准备", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(20.dp))
            Text("先做完下面这一步，再确认开始阅读。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            if (state.intent != null) {
                Text("已等待 ${state.waitingMillis / 60_000} 分 ${(state.waitingMillis / 1_000) % 60} 秒",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
            }
            MirraFocusCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("第一步", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (item == null) "正在读取…" else state.firstAction.ifEmpty { "先确定一个现在就能完成的小动作。" },
                        style = MaterialTheme.typography.headlineSmall)
                    MirraTextAction(onClick = viewModel::editFirstAction,
                        enabled = state.intent != null && item != null && !busy && !state.isExpired) {
                        Text(if (state.firstAction.isEmpty()) "确定第一步" else "编辑第一步")
                    }
                }
            }
            if (state.isExpired) Text("准备已超时，确认会结束本次启动；你也可以取消后重新准备。",
                color = MaterialTheme.colorScheme.error)
            if (viewModel.starting) Text("正在准备本次学习…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Column {
            MirraPrimaryButton(
                onClick = { viewModel.start { sessionId, unmonitored ->
                    if (unmonitored) Toast.makeText(context, "本次未开启分心监测", Toast.LENGTH_SHORT).show()
                    onStarted(sessionId)
                } },
                enabled = state.canConfirm && !busy && !viewModel.editingFirstAction,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("我已完成这一步，开始阅读") }
            Spacer(Modifier.height(10.dp))
            MirraSecondaryButton(onClick = onBack, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) { Text("稍后再说") }
            Spacer(Modifier.height(10.dp))
            MirraTextAction(
                onClick = { viewModel.abandon(onAbandoned) },
                enabled = state.intent != null && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("取消本次启动") }
        }
    }
    if (viewModel.editingFirstAction && item != null) {
        var edited by rememberSaveable(item.id, state.intent?.id, viewModel.editingFirstAction) {
            mutableStateOf(state.firstAction)
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissFirstActionEditor,
            title = { Text("确定第一步") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    FirstActionInput(edited, { edited = it }, item.currentPage, enabled = !viewModel.savingFirstAction)
                    viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.saveFirstAction(edited) },
                    enabled = !viewModel.savingFirstAction && FirstActionResolver.validationError(edited) == null) {
                    Text(if (viewModel.savingFirstAction) "正在保存…" else "保存动作")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissFirstActionEditor, enabled = !viewModel.savingFirstAction) { Text("取消") }
            },
        )
    }
}
