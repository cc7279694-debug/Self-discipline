package com.guanyi.mirra.feature.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.heightIn
import com.guanyi.mirra.ui.components.MirraTextAction
import com.guanyi.mirra.ui.components.MirraSecondaryButton
import com.guanyi.mirra.ui.theme.MirraTheme
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
import com.guanyi.mirra.domain.monitoring.*
import com.guanyi.mirra.data.repository.LearningItemRepository
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import com.guanyi.mirra.ui.components.MirraPrimaryButton
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
    private val focusActions: FocusSessionActions,
    learningItems: LearningItemRepository? = null,
    private val clockSample: () -> ClockSample = { ClockSample(System.currentTimeMillis(), SystemClock.elapsedRealtime()) },
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
    private val evidenceMutex = Mutex()
    val focusStatus = focusActions.focusStatus
    val intervention = focusActions.intervention
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val bookName = workflow.observeSession(sessionId).flatMapLatest { current ->
        if (current == null || learningItems == null) flowOf("")
        else learningItems.observe(current.learningItemId).map { it?.name.orEmpty() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    var focusError by mutableStateOf<String?>(null)
        private set

    suspend fun refreshFocusState(sample: ClockSample = clockSample()) = focusActions.refresh(sessionId, sample)

    suspend fun observeFocusEvidence(screenNonInteractive: Boolean, pageVisibleAndFocused: Boolean) = evidenceMutex.withLock {
        val sample = clockSample()
        refreshFocusState(sample)
        focusActions.observeEvidence(sessionId, sample, screenNonInteractive, pageVisibleAndFocused)
    }

    fun reportEvidence(screenNonInteractive: Boolean, pageVisibleAndFocused: Boolean) {
        viewModelScope.launch { observeFocusEvidence(screenNonInteractive, pageVisibleAndFocused) }
    }

    private fun focusAction(refreshOnFailure: Boolean = false, block: suspend (ClockSample) -> FocusActionResult) {
        viewModelScope.launch {
            val result = block(clockSample())
            focusError = when (result) {
                FocusActionResult.SUCCESS -> null
                FocusActionResult.EXPIRED -> "当前状态已变化"
                FocusActionResult.CONFLICT -> "操作暂未生效，请稍后重试"
                FocusActionResult.SAVE_FAILED -> "保存失败，请重试"
            }
            if (result == FocusActionResult.EXPIRED || result == FocusActionResult.CONFLICT ||
                (refreshOnFailure && result == FocusActionResult.SAVE_FAILED)) refreshFocusState()
        }
    }

    fun startBreak(minutes: Int) {
        val id = focusStatus.value.segmentId ?: return
        focusAction { focusActions.startBreak(sessionId, id, minutes * 60_000L, it) }
    }
    fun finishBreak() {
        val id = focusStatus.value.segmentId ?: return
        focusAction { focusActions.finishBreak(sessionId, id, it) }
    }
    fun finishAllowance() {
        val id = focusStatus.value.segmentId ?: return
        focusAction { focusActions.finishAllowance(sessionId, id, it) }
    }
    fun extendAllowance() {
        val id = focusStatus.value.segmentId ?: return
        val token = UUID.randomUUID().toString()
        focusAction(refreshOnFailure = true) { focusActions.extendAllowance(sessionId, id, token, it) }
    }
    fun selectAllowanceReason(reason: AllowanceReason) {
        val prompt = intervention.value ?: return
        focusAction { focusActions.selectAllowanceReason(sessionId, prompt.promptToken, reason, true, it) }
    }
    fun setPromptVisible(visible: Boolean) {
        val prompt = intervention.value ?: return
        focusAction { focusActions.setPromptVisible(sessionId, prompt.promptToken, visible, it) }
    }
    fun grantAllowance() {
        val prompt = intervention.value ?: return
        val reason = prompt.reason ?: return
        focusAction { focusActions.grantAllowance(sessionId, prompt.segmentId, prompt.promptToken, prompt.packageName, reason, null, it) }
    }
    fun dismissPrompt() {
        val prompt = intervention.value ?: return
        focusAction { focusActions.dismissPrompt(sessionId, prompt.promptToken, it) }
    }
    fun returnToStudy() {
        val prompt = intervention.value ?: return
        focusAction { focusActions.returnToStudy(sessionId, prompt.promptToken, it) }
    }

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
    onOpenNote: (String) -> Unit,
    onBack: () -> Unit,
    externalRequest: com.guanyi.mirra.domain.intervention.InterventionNavigationRequest? = null,
    onExternalRequestHandled: () -> Unit = {},
    onPromptComposed: (com.guanyi.mirra.domain.monitoring.InterventionUiModel) -> Unit = {},
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val currentSession = session
    val status by viewModel.focusStatus.collectAsStateWithLifecycle()
    val intervention by viewModel.intervention.collectAsStateWithLifecycle()
    val bookName by viewModel.bookName.collectAsStateWithLifecycle()
    val prompt = intervention?.takeIf { !it.dismissed && it.sessionId == currentSession?.id }
    var breakChoice by remember { mutableStateOf(false) }
    var reasonsVisible by remember(prompt?.promptToken) { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(externalRequest?.id, currentSession?.id, prompt?.promptToken) {
        val request = externalRequest ?: return@LaunchedEffect
        if (currentSession == null) return@LaunchedEffect
        if (currentSession.activeSlot == 1 && request.sessionId == currentSession.id &&
            request.promptToken == prompt?.promptToken) {
            when (request.action) {
                com.guanyi.mirra.domain.intervention.InterventionNavigationAction.OPEN_ALLOWANCE -> reasonsVisible = true
                com.guanyi.mirra.domain.intervention.InterventionNavigationAction.OPEN_FINISH ->
                    listState.animateScrollToItem(4 + notes.size)
                else -> Unit
            }
        }
        onExternalRequestHandled()
    }
    fun hideReasons() { viewModel.setPromptVisible(false); reasonsVisible = false }
    fun finishReading() { currentSession?.let { viewModel.finish(viewModel.currentPageText.toIntOrNull() ?: it.currentPage, onFinished) } }
    SessionEvidenceReporter(viewModel)
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
    BackHandler {
        when {
            reasonsVisible -> hideReasons()
            breakChoice -> breakChoice = false
            prompt != null -> viewModel.dismissPrompt()
            else -> viewModel.leave(onBack)
        }
    }
    val elapsedMinutes = currentSession?.let { ((viewModel.now - it.startedAt).coerceAtLeast(0) / 60_000) } ?: 0

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("正在阅读", style = MaterialTheme.typography.headlineMedium)
            Text("Session 阅读时长：${elapsedMinutes} 分钟", color = MaterialTheme.colorScheme.primary)
            SessionFocusContent(status, { breakChoice = true }, viewModel::finishBreak,
                viewModel::finishAllowance, viewModel::extendAllowance)
            viewModel.focusError?.let { Text(it, color = MirraTheme.colors.danger) }
        }
        if (prompt != null) item {
            LaunchedEffect(prompt.eventId, prompt.segmentId) { onPromptComposed(prompt) }
            InterventionContent(bookName, prompt, false, { reasonsVisible = true }, viewModel::selectAllowanceReason,
                viewModel::returnToStudy, viewModel::grantAllowance, ::finishReading, viewModel::dismissPrompt)
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Column(Modifier.fillMaxWidth().clickable { onOpenNote(note.id) }) {
                Text(note.semanticType.label, style = MaterialTheme.typography.labelLarge)
                Text(note.content)
                note.pageNumber?.let { Text("第 $it 页", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            if (prompt == null) {
                MirraPrimaryButton(onClick = ::finishReading, enabled = currentSession != null,
                    modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("结束本次阅读") }
            } else {
                MirraSecondaryButton(onClick = ::finishReading, enabled = currentSession != null,
                    modifier = Modifier.fillMaxWidth()) { Text("结束本次阅读") }
            }
        }
    }
    if (breakChoice) AlertDialog(onDismissRequest = { breakChoice = false }, title = { Text("休息") },
        text = {
            Column {
                MirraTextAction({ breakChoice = false; viewModel.startBreak(5) }, Modifier.fillMaxWidth()) { Text("休息 5 分钟") }
                MirraTextAction({ breakChoice = false; viewModel.startBreak(10) }, Modifier.fillMaxWidth()) { Text("休息 10 分钟") }
            }
        }, confirmButton = { MirraTextAction({ breakChoice = false }) { Text("取消") } })
    if (reasonsVisible && prompt != null) Dialog(onDismissRequest = ::hideReasons) {
        AllowancePanelVisibility(viewModel, prompt.reason != null)
        Surface(shape = MirraTheme.shapes.large, color = MirraTheme.colors.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
                InterventionContent(bookName, prompt, true, {}, viewModel::selectAllowanceReason,
                    viewModel::returnToStudy, viewModel::grantAllowance, ::finishReading, {
                        hideReasons(); viewModel.dismissPrompt()
                    })
                viewModel.focusError?.let { Text(it, color = MirraTheme.colors.danger) }
            }
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
