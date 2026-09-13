package com.guanyi.mirra.feature.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.domain.SessionManager
import com.guanyi.mirra.domain.FirstActionResolver
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.guanyi.mirra.ui.components.MirraPrimaryButton
import com.guanyi.mirra.ui.components.MirraSecondaryButton
import com.guanyi.mirra.ui.components.MirraTextAction

data class PreparationUiState(
    val intent: StudyIntentEntity? = null,
    val item: LearningItemEntity? = null,
)

class PreparationViewModel(
    private val intentId: String,
    private val workflow: StudyWorkflowRepository,
    private val learningItems: LearningItemRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {
    var error by mutableStateOf<String?>(null)
        private set
    var abandoning by mutableStateOf(false)
        private set

    val uiState = combine(workflow.observeActiveIntent(), learningItems.observeAll()) { intent, items ->
        val selected = intent?.takeIf { it.id == intentId }
        PreparationUiState(selected, items.firstOrNull { it.id == selected?.learningItemId })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PreparationUiState())

    init {
        viewModelScope.launch { runCatching { workflow.markTransitioned(intentId) } }
    }

    fun start(onStarted: (String) -> Unit) {
        val item = uiState.value.item ?: return
        viewModelScope.launch {
            runCatching { sessionManager.start(intentId, item.currentPage) }
                .onSuccess { onStarted(it.id) }
                .onFailure { error = it.message ?: "无法开始 Session" }
        }
    }

    fun abandon(onAbandoned: () -> Unit) {
        if (abandoning) return
        abandoning = true
        viewModelScope.launch {
            runCatching { workflow.abandonIntent(intentId) }
                .onSuccess { onAbandoned() }
                .onFailure {
                    abandoning = false
                    error = it.message ?: "无法取消本次启动"
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
    val item = state.item
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("启动准备", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(20.dp))
            Text("先完成一个具体动作", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text(item?.let(FirstActionResolver::resolve) ?: "正在读取…", style = MaterialTheme.typography.titleLarge)
            viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Column {
            MirraPrimaryButton(
                onClick = { viewModel.start(onStarted) },
                enabled = item != null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("我已拿起书，开始阅读") }
            Spacer(Modifier.height(10.dp))
            MirraSecondaryButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("稍后再说") }
            Spacer(Modifier.height(10.dp))
            MirraTextAction(
                onClick = { viewModel.abandon(onAbandoned) },
                enabled = item != null && !viewModel.abandoning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("取消本次启动") }
        }
    }
}
