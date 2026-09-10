package com.guanyi.mirra.feature.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StartUiState(
    val mainline: LearningItemEntity? = null,
    val activeIntent: StudyIntentEntity? = null,
    val activeSession: StudySessionEntity? = null,
)

class StartViewModel(
    private val learningItems: LearningItemRepository,
    private val workflow: StudyWorkflowRepository,
) : ViewModel() {
    var error by mutableStateOf<String?>(null)
        private set

    val uiState = combine(
        learningItems.observeMainline(),
        workflow.observeActiveIntent(),
        workflow.observeActiveSession(),
        ::StartUiState,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartUiState())

    fun begin(onIntentReady: (String) -> Unit) {
        val item = uiState.value.mainline ?: return
        viewModelScope.launch {
            runCatching { workflow.createIntent(item.id) }
                .onSuccess { onIntentReady(it.id) }
                .onFailure { error = it.message ?: "无法开始" }
        }
    }
}

@Composable
fun StartScreen(
    viewModel: StartViewModel,
    onOpenIntent: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onCreateLearningItem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activeSession = state.activeSession
    val activeIntent = state.activeIntent
    val mainline = state.mainline

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("观已Mirra", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(28.dp))
            Text("把想学的，变成正在发生的。", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    activeSession != null -> "有一段阅读正在进行"
                    activeIntent != null -> "你已经产生了学习意图，继续完成第一个动作。"
                    mainline != null -> "主线：《${mainline.name}》\n上次读到第 ${mainline.currentPage} 页"
                    else -> "先建立一条清晰的学习主线，再从一次真实阅读开始。"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Button(
            onClick = {
                when {
                    activeSession != null -> onOpenSession(activeSession.id)
                    activeIntent != null -> onOpenIntent(activeIntent.id)
                    mainline != null -> viewModel.begin(onOpenIntent)
                    else -> onCreateLearningItem()
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(
                when {
                    activeSession != null -> "继续当前阅读"
                    activeIntent != null -> "继续启动"
                    mainline != null -> "我想开始"
                    else -> "创建第一本书"
                },
            )
        }
    }
}
