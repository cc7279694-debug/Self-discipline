package com.guanyi.mirra.feature.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.repository.TopicRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TopicListViewModel(private val repository: TopicRepository) : ViewModel() {
    val topics = repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    var name by mutableStateOf(""); private set
    var message by mutableStateOf<String?>(null); private set
    fun changeName(value: String) { name = value; message = null }
    fun create() = viewModelScope.launch {
        runCatching { repository.create(name) }
            .onSuccess { result -> name = ""; message = if (result is com.guanyi.mirra.data.repository.CreateTopicResult.Created) "Topic 已创建" else "Topic 已存在" }
            .onFailure { message = it.message ?: "创建失败" }
    }
}

class CreateTopicViewModel(private val repository: TopicRepository) : ViewModel() {
    var name by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null); private set
    var saving by mutableStateOf(false); private set
    fun changeName(value: String) { name = value; error = null }
    fun create(onCreated: (String) -> Unit) = viewModelScope.launch {
        saving = true
        runCatching { repository.create(name) }
            .onSuccess { onCreated(it.topic.id) }
            .onFailure { error = it.message ?: "创建失败" }
        saving = false
    }
}

@Composable fun CreateTopicScreen(viewModel: CreateTopicViewModel, onCreated: (String) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("创建 Topic", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(viewModel.name, viewModel::changeName, label = { Text("Topic 名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { viewModel.create(onCreated) }, enabled = viewModel.name.isNotBlank() && !viewModel.saving, modifier = Modifier.fillMaxWidth()) { Text("创建") }
        OutlinedButton(onBack, Modifier.fillMaxWidth()) { Text("取消") }
    }
}

@Composable fun TopicListScreen(viewModel: TopicListViewModel, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val topics by viewModel.topics.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Topic", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(viewModel.name, viewModel::changeName, label = { Text("新 Topic") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = { viewModel.create() }) { Text("创建") }
        }
        viewModel.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (topics.isEmpty()) Text("还没有 Topic。可在这里创建，或在笔记中创建并关联。")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(topics, key = { it.topic.id }) { topic ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(topic.topic.id) }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(topic.topic.name); Text("${topic.noteCount} 条笔记", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        OutlinedButton(onBack, Modifier.fillMaxWidth()) { Text("返回") }
    }
}

class TopicDetailViewModel(topicId: String, repository: TopicRepository) : ViewModel() {
    val topic = repository.observe(topicId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val notes = repository.observeNotes(topicId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable fun TopicDetailScreen(viewModel: TopicDetailViewModel, onOpenNote: (String) -> Unit, onBack: () -> Unit) {
    val topic by viewModel.topic.collectAsStateWithLifecycle(); val notes by viewModel.notes.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(topic?.name ?: "Topic", style = MaterialTheme.typography.headlineMedium)
        if (notes.isEmpty()) Text("这个 Topic 还没有关联笔记。")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(notes, key = { it.note.id }) { note ->
                Card(Modifier.fillMaxWidth().clickable { onOpenNote(note.note.id) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(note.note.content, maxLines = 3)
                        Text("${note.learningItemName}${note.note.pageNumber?.let { " · 第 $it 页" }.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        OutlinedButton(onBack, Modifier.fillMaxWidth()) { Text("返回") }
    }
}
