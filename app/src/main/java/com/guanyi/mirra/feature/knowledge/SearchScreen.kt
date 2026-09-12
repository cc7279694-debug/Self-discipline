package com.guanyi.mirra.feature.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.repository.SearchRepository
import com.guanyi.mirra.data.repository.SearchResult
import com.guanyi.mirra.data.search.SearchDocumentType
import kotlinx.coroutines.launch
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(FlowPreview::class)
class SearchViewModel(private val repository: SearchRepository) : ViewModel() {
    var query by mutableStateOf(""); private set
    var results by mutableStateOf<List<SearchResult>>(emptyList()); private set
    var hint by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var searching by mutableStateOf(false); private set
    private val queryFlow = MutableStateFlow("")
    init {
        viewModelScope.launch {
            queryFlow.debounce(300).distinctUntilChanged().collectLatest { value ->
                if (value.isBlank()) return@collectLatest
                searching = true
                try {
                    val response = repository.search(value)
                    results = response.items
                    hint = response.hint
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    error = "搜索索引需要修复，请重试"
                } finally {
                    searching = false
                }
            }
        }
    }
    fun changeQuery(value: String) {
        query = value; error = null
        queryFlow.value = value
        if (value.isBlank()) { results = emptyList(); hint = null; searching = false; return }
    }
    fun repair() = viewModelScope.launch {
        searching = true
        runCatching { repository.rebuildIndex(); repository.search(query) }
            .onSuccess { results = it.items; hint = it.hint; error = null }
            .onFailure { error = "索引修复失败" }
        searching = false
    }
}

@Composable fun SearchScreen(viewModel: SearchViewModel, onOpen: (SearchResult) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("搜索", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(viewModel.query, viewModel::changeQuery, label = { Text("搜索笔记、内容、Topic 或阅读总结") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (viewModel.query.isBlank()) Text("可搜索笔记正文、图片说明、书名、Topic 和阅读总结。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (viewModel.searching) Text("正在搜索…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        viewModel.hint?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        viewModel.error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { viewModel.repair() }) { Text("修复索引") }
        }
        if (!viewModel.searching && viewModel.query.isNotBlank() && viewModel.results.isEmpty() && viewModel.hint == null && viewModel.error == null) Text("没有找到结果")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            SearchDocumentType.entries.forEach { type ->
                val group = viewModel.results.filter { it.type == type }
                if (group.isNotEmpty()) {
                    item(key = "heading-$type") { Text(type.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall) }
                    items(group, key = { "${it.type}-${it.id}" }) { result ->
                        Card(Modifier.fillMaxWidth().clickable { onOpen(result) }) {
                            Column(Modifier.padding(16.dp)) {
                                Text(result.title, style = MaterialTheme.typography.titleMedium)
                                if (result.snippet != result.title) Text(result.snippet, maxLines = 3)
                                result.pageNumber?.let { Text("第 $it 页", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(onBack, Modifier.fillMaxWidth()) { Text("返回") }
    }
}

private val SearchDocumentType.label: String get() = when (this) {
    SearchDocumentType.NOTE -> "笔记"
    SearchDocumentType.LEARNING_ITEM -> "学习内容"
    SearchDocumentType.TOPIC -> "Topic"
    SearchDocumentType.SESSION -> "阅读总结"
}

class SessionSearchDetailViewModel(sessionId: String, workflow: StudyWorkflowRepository) : ViewModel() {
    val session = workflow.observeSession(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable fun SessionSearchDetailScreen(viewModel: SessionSearchDetailViewModel, onBack: () -> Unit) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("阅读记录", style = MaterialTheme.typography.headlineMedium)
        Text(session?.generatedSummary ?: "总结不存在", style = MaterialTheme.typography.titleLarge)
        session?.let { value ->
            Text("第 ${value.startPage}–${value.endPage ?: value.currentPage} 页")
            val minutes = ((value.endedAt ?: value.startedAt) - value.startedAt).coerceAtLeast(0) / 60_000
            Text("阅读时长：$minutes 分钟")
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        OutlinedButton(onBack, Modifier.fillMaxWidth()) { Text("返回") }
    }
}
