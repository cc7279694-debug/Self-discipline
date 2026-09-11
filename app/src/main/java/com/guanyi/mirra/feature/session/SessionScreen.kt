package com.guanyi.mirra.feature.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.NoteEntity
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.repository.NoteRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.domain.RuleBasedNoteTypeSuggester
import com.guanyi.mirra.domain.SessionManager
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionViewModel(
    private val sessionId: String,
    workflow: StudyWorkflowRepository,
    private val notesRepository: NoteRepository,
    private val sessionManager: SessionManager,
    private val noteTypeSuggester: RuleBasedNoteTypeSuggester = RuleBasedNoteTypeSuggester(),
) : ViewModel() {
    val session: StateFlow<StudySessionEntity?> = workflow.observeSession(sessionId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )
    val notes: StateFlow<List<NoteEntity>> = notesRepository.observeForSession(sessionId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    var now by mutableStateOf(System.currentTimeMillis())
        private set
    var draftContent by mutableStateOf("")
        private set
    var draftPage by mutableStateOf("")
        private set
    var draftType by mutableStateOf(NoteSemanticType.UNDERSTANDING)
        private set
    var currentPageText by mutableStateOf("")
        private set
    var savedMessage by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var draftId = UUID.randomUUID().toString()
    private var saveJob: Job? = null
    private var draftTypeManuallySelected = false
    private var draftPageManuallyEdited = false
    private val saveMutex = Mutex()

    init {
        viewModelScope.launch {
            while (isActive) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }

    fun changeContent(value: String) {
        draftContent = value
        if (!draftTypeManuallySelected) {
            draftType = noteTypeSuggester.suggest(value)
        }
        savedMessage = ""
        error = null
        scheduleAutoSave()
    }

    fun changePage(value: String) {
        draftPage = value.filter(Char::isDigit)
        draftPageManuallyEdited = true
        scheduleAutoSave()
    }

    fun changeType(value: NoteSemanticType) {
        draftType = value
        draftTypeManuallySelected = true
        scheduleAutoSave()
    }

    fun syncCurrentPage(page: Int) {
        currentPageText = page.toString()
        if (draftContent.isBlank() && !draftPageManuallyEdited) {
            draftPage = page.toString()
        }
    }

    fun saveAndContinue() {
        saveJob?.cancel()
        viewModelScope.launch {
            runCatching { saveDraft() }
                .onSuccess {
                    resetDraft()
                }
                .onFailure { error = it.message ?: "笔记保存失败" }
        }
    }

    fun updatePage(value: String) {
        val filtered = value.filter(Char::isDigit)
        currentPageText = filtered
        val page = filtered.toIntOrNull() ?: return
        val persistedPage = session.value?.currentPage ?: return
        if (page < persistedPage) {
            currentPageText = persistedPage.toString()
            return
        }
        if (draftContent.isBlank() && !draftPageManuallyEdited) {
            draftPage = page.toString()
        }
        viewModelScope.launch {
            runCatching { sessionManager.updatePage(sessionId, page) }
                .onFailure { error = it.message ?: "页码保存失败" }
        }
    }

    fun finish(endPage: Int, onFinished: (String) -> Unit) {
        viewModelScope.launch {
            saveJob?.cancel()
            runCatching {
                saveDraft()
                sessionManager.finish(sessionId, endPage)
            }.onSuccess {
                draftContent = ""
                onFinished(it.id)
            }
                .onFailure { error = it.message ?: "Session 结束失败" }
        }
    }

    fun flushDraft() {
        saveJob?.cancel()
        viewModelScope.launch {
            runCatching { saveDraft() }
                .onFailure { error = it.message ?: "笔记保存失败" }
        }
    }

    fun leave(onLeft: () -> Unit) {
        saveJob?.cancel()
        viewModelScope.launch {
            runCatching { saveDraft() }
                .onSuccess {
                    draftContent = ""
                    onLeft()
                }
                .onFailure { error = it.message ?: "笔记保存失败" }
        }
    }

    private fun scheduleAutoSave() {
        saveJob?.cancel()
        if (draftContent.isBlank()) return
        saveJob = viewModelScope.launch {
            delay(500)
            runCatching { saveDraft() }
                .onFailure { error = it.message ?: "笔记保存失败" }
        }
    }

    private suspend fun saveDraft() = saveMutex.withLock {
        val currentSession = session.value ?: return@withLock
        val content = draftContent
        if (content.isBlank()) return@withLock
        notesRepository.save(
            id = draftId,
            learningItemId = currentSession.learningItemId,
            sessionId = currentSession.id,
            content = content,
            pageNumber = draftPage.toIntOrNull(),
            semanticType = draftType,
        )
        savedMessage = "已自动保存"
    }

    private fun resetDraft() {
        draftId = UUID.randomUUID().toString()
        draftContent = ""
        draftPage = currentPageText.ifBlank { session.value?.currentPage?.toString().orEmpty() }
        draftType = NoteSemanticType.UNDERSTANDING
        draftTypeManuallySelected = false
        draftPageManuallyEdited = false
    }
}

@Composable
fun SessionScreen(
    viewModel: SessionViewModel,
    onFinished: (String) -> Unit,
    onBack: () -> Unit,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val currentSession = session
    LaunchedEffect(currentSession?.currentPage) {
        if (currentSession != null) {
            viewModel.syncCurrentPage(currentSession.currentPage)
        }
    }
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
    val elapsedMinutes = currentSession?.let { ((viewModel.now - it.startedAt).coerceAtLeast(0) / 60_000) } ?: 0

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("正在阅读", style = MaterialTheme.typography.headlineMedium)
            Text("Session 阅读时长：${elapsedMinutes} 分钟", color = MaterialTheme.colorScheme.primary)
        }
        item {
            OutlinedTextField(
                viewModel.currentPageText,
                viewModel::updatePage,
                label = { Text("当前页码") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text("快速笔记", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NoteSemanticType.entries.forEach { type ->
                    FilterChip(
                        selected = viewModel.draftType == type,
                        onClick = { viewModel.changeType(type) },
                        label = { Text(type.label) },
                    )
                }
            }
            OutlinedTextField(
                viewModel.draftContent,
                viewModel::changeContent,
                label = { Text("写下摘录或想法") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            OutlinedTextField(
                viewModel.draftPage,
                viewModel::changePage,
                label = { Text("笔记页码（可选）") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (viewModel.savedMessage.isNotEmpty()) Text(viewModel.savedMessage, color = MaterialTheme.colorScheme.primary)
            viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(
                onClick = viewModel::saveAndContinue,
                enabled = viewModel.draftContent.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存并记下一条") }
        }
        items(notes, key = NoteEntity::id) { note ->
            Column {
                Text(note.semanticType.label, style = MaterialTheme.typography.labelLarge)
                Text(note.content)
                note.pageNumber?.let { Text("第 $it 页", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { currentSession?.let { viewModel.finish(viewModel.currentPageText.toIntOrNull() ?: it.currentPage, onFinished) } },
                enabled = currentSession != null,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) { Text("结束本次阅读") }
        }
    }
}

private val NoteSemanticType.label: String
    get() = when (this) {
        NoteSemanticType.QUOTE -> "摘录"
        NoteSemanticType.SUMMARY -> "总结"
        NoteSemanticType.UNDERSTANDING -> "我的理解"
        NoteSemanticType.QUESTION -> "问题"
    }
