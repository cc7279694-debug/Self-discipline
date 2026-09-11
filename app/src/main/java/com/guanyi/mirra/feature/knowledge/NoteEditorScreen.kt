package com.guanyi.mirra.feature.knowledge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.data.repository.NoteRepository
import com.guanyi.mirra.domain.RuleBasedNoteTypeSuggester
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NoteEditorViewModel(
    initialNoteId: String?,
    initialLearningItemId: String?,
    private val notes: NoteRepository,
    learningItems: LearningItemRepository,
    private val noteTypeSuggester: RuleBasedNoteTypeSuggester = RuleBasedNoteTypeSuggester(),
    newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    val learningItems = learningItems.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    var content by mutableStateOf("")
        private set
    var pageText by mutableStateOf("")
        private set
    var semanticType by mutableStateOf(NoteSemanticType.UNDERSTANDING)
        private set
    var selectedLearningItemId by mutableStateOf(initialLearningItemId)
        private set
    var savedMessage by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var isSaving by mutableStateOf(false)
        private set
    var canDelete by mutableStateOf(initialNoteId != null)
        private set
    var isLoaded by mutableStateOf(initialNoteId == null)
        private set
    val canChooseLearningItem = initialNoteId == null

    private val draftId = initialNoteId ?: newId()
    private var persistedNoteId = initialNoteId
    private var typeManuallySelected = false
    private var saveJob: Job? = null
    private var revision = 0
    private var deleted = false
    private val saveMutex = Mutex()

    init {
        if (initialNoteId != null) {
            viewModelScope.launch {
                val note = notes.observe(initialNoteId).first()
                if (note == null) {
                    error = "Note 不存在"
                } else {
                    content = note.content
                    pageText = note.pageNumber?.toString().orEmpty()
                    semanticType = note.semanticType
                    selectedLearningItemId = note.learningItemId
                    typeManuallySelected = true
                }
                isLoaded = true
            }
        }
    }

    fun changeContent(value: String) {
        content = value
        if (!typeManuallySelected) semanticType = noteTypeSuggester.suggest(value)
        changed()
    }

    fun changePage(value: String) {
        pageText = value.filter(Char::isDigit)
        changed()
    }

    fun changeType(value: NoteSemanticType) {
        semanticType = value
        typeManuallySelected = true
        changed()
    }

    fun selectLearningItem(id: String) {
        if (!canChooseLearningItem || persistedNoteId != null) return
        selectedLearningItemId = id
        changed()
    }

    fun flushDraft() {
        if (deleted) return
        saveJob?.cancel()
        viewModelScope.launch {
            runCatching { saveDraft(requireValid = false) }
                .onFailure { error = it.message ?: "笔记保存失败" }
        }
    }

    fun leave(onLeft: () -> Unit) {
        if (deleted) {
            onLeft()
            return
        }
        saveJob?.cancel()
        if (persistedNoteId == null && content.isBlank()) {
            onLeft()
            return
        }
        viewModelScope.launch {
            runCatching { saveDraft(requireValid = true) }
                .onSuccess { onLeft() }
                .onFailure { error = it.message ?: "笔记保存失败" }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = persistedNoteId ?: return
        saveJob?.cancel()
        viewModelScope.launch {
            isSaving = true
            error = null
            runCatching { notes.delete(id) }
                .onSuccess {
                    deleted = true
                    onDeleted()
                }
                .onFailure { error = it.message ?: "删除失败" }
            isSaving = false
        }
    }

    private fun changed() {
        revision += 1
        savedMessage = ""
        error = null
        scheduleAutoSave()
    }

    private fun scheduleAutoSave() {
        saveJob?.cancel()
        if (content.isBlank() || selectedLearningItemId == null) return
        saveJob = viewModelScope.launch {
            delay(500)
            runCatching { saveDraft(requireValid = false) }
                .onFailure { error = it.message ?: "笔记保存失败" }
        }
    }

    private suspend fun saveDraft(requireValid: Boolean): Boolean = saveMutex.withLock {
        if (!isLoaded) return@withLock false
        val cleanContent = content.trim()
        if (cleanContent.isEmpty()) {
            if (requireValid && persistedNoteId != null) kotlin.error("笔记内容不能为空。如需移除，请删除这条笔记。")
            return@withLock false
        }
        val itemId = selectedLearningItemId
        if (itemId == null) {
            if (requireValid) kotlin.error("请选择学习内容")
            return@withLock false
        }
        val page = pageText.toIntOrNull()
        if (pageText.isNotBlank() && page == null) kotlin.error("页码格式不正确")
        val savingRevision = revision
        isSaving = true
        error = null
        val saved = try {
            if (persistedNoteId == null) {
                notes.createStandalone(itemId, cleanContent, semanticType, page, draftId)
            } else {
                notes.update(checkNotNull(persistedNoteId), cleanContent, semanticType, page)
            }
        } finally {
            isSaving = false
        }
        persistedNoteId = saved.id
        canDelete = true
        if (savingRevision == revision) savedMessage = "已自动保存"
        true
    }
}

@Composable
fun NoteEditorScreen(
    viewModel: NoteEditorViewModel,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val learningItems by viewModel.learningItems.collectAsStateWithLifecycle()
    var itemMenuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selectedItemName = learningItems.firstOrNull { it.id == viewModel.selectedLearningItemId }?.name
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.flushDraft()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.flushDraft()
        }
    }
    BackHandler { viewModel.leave(onBack) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (viewModel.canChooseLearningItem) "新建笔记" else "笔记内容", style = MaterialTheme.typography.headlineMedium)
        if (!viewModel.isLoaded) {
            Text("正在读取笔记…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
            return@Column
        }
        if (viewModel.canChooseLearningItem && !viewModel.canDelete) {
            Box {
                OutlinedButton(onClick = { itemMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedItemName ?: "选择学习内容")
                }
                DropdownMenu(expanded = itemMenuExpanded, onDismissRequest = { itemMenuExpanded = false }) {
                    learningItems.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.name) },
                            onClick = {
                                itemMenuExpanded = false
                                viewModel.selectLearningItem(item.id)
                            },
                        )
                    }
                }
            }
        } else {
            Text("学习内容：${selectedItemName.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NoteSemanticType.entries.forEach { type ->
                FilterChip(
                    selected = viewModel.semanticType == type,
                    onClick = { viewModel.changeType(type) },
                    label = { Text(type.displayName) },
                )
            }
        }
        OutlinedTextField(
            value = viewModel.content,
            onValueChange = viewModel::changeContent,
            label = { Text("笔记内容") },
            minLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = viewModel.pageText,
            onValueChange = viewModel::changePage,
            label = { Text("页码（可选）") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (viewModel.isSaving) Text("正在保存…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (viewModel.savedMessage.isNotEmpty()) Text(viewModel.savedMessage, color = MaterialTheme.colorScheme.primary)
        viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (viewModel.canDelete) {
            TextButton(
                onClick = { confirmDelete = true },
                enabled = !viewModel.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("删除笔记") }
        }
        OutlinedButton(onClick = { viewModel.leave(onBack) }, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条笔记？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete(onDeleted)
                    },
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}
