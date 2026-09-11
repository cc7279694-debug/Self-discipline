package com.guanyi.mirra.feature.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CreateLearningItemViewModel(private val repository: LearningItemRepository) : ViewModel() {
    var error by mutableStateOf<String?>(null)
        private set

    fun create(name: String, totalPages: String, currentPage: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                repository.create(name, totalPages.toInt(), currentPage.toInt())
            }.onSuccess { onCreated(it.id) }
                .onFailure { error = it.message ?: "无法创建" }
        }
    }
}

@Composable
fun CreateLearningItemScreen(
    viewModel: CreateLearningItemViewModel,
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var totalPages by remember { mutableStateOf("") }
    var currentPage by remember { mutableStateOf("1") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("创建学习内容", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(name, { name = it }, label = { Text("书名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(totalPages, { totalPages = it.filter(Char::isDigit) }, label = { Text("总页数") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(currentPage, { currentPage = it.filter(Char::isDigit) }, label = { Text("当前页") }, modifier = Modifier.fillMaxWidth())
        viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { viewModel.create(name, totalPages, currentPage, onCreated) },
            enabled = name.isNotBlank() && totalPages.isNotBlank() && currentPage.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("创建") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
}

class LearningItemDetailViewModel(
    itemId: String,
    private val learningItems: LearningItemRepository,
    private val workflow: StudyWorkflowRepository,
) : ViewModel() {
    var error by mutableStateOf<String?>(null)
        private set
    var isSubmitting by mutableStateOf(false)
        private set
    val uiState = combine(
        learningItems.observe(itemId),
        workflow.observeLatestSummaryForItem(itemId),
    ) { item, session -> LearningItemDetailUiState(item, session?.generatedSummary) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LearningItemDetailUiState(),
    )

    fun setMainline() = perform { learningItems.setMainline(it.id) }

    fun pause() = perform { learningItems.pause(it.id) }

    fun resume() = perform { learningItems.resume(it.id) }

    fun complete() = perform { learningItems.complete(it.id) }

    fun begin(onIntentReady: (String) -> Unit) = viewModelScope.launch {
        val item = uiState.value.item ?: return@launch
        isSubmitting = true
        error = null
        runCatching { workflow.createIntent(item.id) }
            .onSuccess { onIntentReady(it.id) }
            .onFailure { error = it.message ?: "无法开始" }
        isSubmitting = false
    }

    private fun perform(action: suspend (LearningItemEntity) -> Unit) = viewModelScope.launch {
        val item = uiState.value.item ?: return@launch
        isSubmitting = true
        error = null
        runCatching { action(item) }
            .onFailure { error = it.message ?: "操作失败" }
        isSubmitting = false
    }
}

data class LearningItemDetailUiState(
    val item: LearningItemEntity? = null,
    val lastSummary: String? = null,
)

@Composable
fun LearningItemDetailScreen(
    viewModel: LearningItemDetailViewModel,
    onStart: (String) -> Unit,
    onOpenNotes: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val item = state.item
    var confirmComplete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        item?.let {
            Text(it.name, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text("状态：${it.status.displayName}")
            Text("当前第 ${it.currentPage} 页，共 ${it.totalPages} 页")
            Text("进度 ${(it.currentPage * 100 / it.totalPages)}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.lastSummary?.let { summary ->
                Spacer(Modifier.height(10.dp))
                Text("上次总结：$summary", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            if (it.status == LearningItemStatus.IN_PROGRESS) {
                Button(
                    onClick = { viewModel.begin(onStart) },
                    enabled = !viewModel.isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("开始阅读") }
                Spacer(Modifier.height(10.dp))
                if (!it.isMainline) {
                    OutlinedButton(
                        onClick = { viewModel.setMainline() },
                        enabled = !viewModel.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("设为主线") }
                } else {
                    Text("当前主线", color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { viewModel.pause() },
                    enabled = !viewModel.isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("暂停") }
                Spacer(Modifier.height(10.dp))
                TextButton(
                    onClick = { confirmComplete = true },
                    enabled = !viewModel.isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("标记为已完成") }
            } else if (it.status == LearningItemStatus.PAUSED) {
                Button(
                    onClick = { viewModel.resume() },
                    enabled = !viewModel.isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("恢复为进行中") }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { onOpenNotes(it.id) }, modifier = Modifier.fillMaxWidth()) { Text("查看这本书的笔记") }
            viewModel.error?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(message, color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
    if (confirmComplete) {
        AlertDialog(
            onDismissRequest = { confirmComplete = false },
            title = { Text("标记为已完成？") },
            text = { Text("完成后会保留所有阅读记录和笔记，但本阶段不能恢复为进行中。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmComplete = false
                        viewModel.complete()
                    },
                ) { Text("确认完成") }
            },
            dismissButton = {
                TextButton(onClick = { confirmComplete = false }) { Text("取消") }
            },
        )
    }
}
