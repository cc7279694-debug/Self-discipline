package com.guanyi.mirra.feature.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
    val uiState = combine(
        learningItems.observe(itemId),
        workflow.observeLatestSummaryForItem(itemId),
    ) { item, session -> LearningItemDetailUiState(item, session?.generatedSummary) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LearningItemDetailUiState(),
    )

    fun setMainline() = viewModelScope.launch { uiState.value.item?.let { learningItems.setMainline(it.id) } }

    fun begin(onIntentReady: (String) -> Unit) = viewModelScope.launch {
        uiState.value.item?.let { onIntentReady(workflow.createIntent(it.id).id) }
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
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val item = state.item
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        item?.let {
            Text(it.name, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text("当前第 ${it.currentPage} 页，共 ${it.totalPages} 页")
            Text("进度 ${(it.currentPage * 100 / it.totalPages)}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.lastSummary?.let { summary ->
                Spacer(Modifier.height(10.dp))
                Text("上次总结：$summary", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { viewModel.begin(onStart) }, modifier = Modifier.fillMaxWidth()) { Text("开始阅读") }
            Spacer(Modifier.height(10.dp))
            if (!it.isMainline) {
                OutlinedButton(onClick = { viewModel.setMainline() }, modifier = Modifier.fillMaxWidth()) { Text("设为主线") }
            } else {
                Text("当前主线", color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
}
