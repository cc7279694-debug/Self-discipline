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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.testTag
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
import com.guanyi.mirra.domain.SessionFinishResult
import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.model.CloseoutSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import com.guanyi.mirra.data.repository.ReadingRecordRepository
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.feature.profile.LearningProtectionMonitoringUiState
import com.guanyi.mirra.feature.profile.LearningProtectionServiceStatus
import com.guanyi.mirra.feature.profile.projectLearningProtection
import com.guanyi.mirra.platform.focus.MonitoringPlatformRuntime
import com.guanyi.mirra.platform.focus.MonitoringCapabilities
import com.guanyi.mirra.domain.monitoring.*
import com.guanyi.mirra.data.repository.LearningItemRepository
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import com.guanyi.mirra.ui.components.MirraPrimaryButton
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionViewModel(
    private val sessionId: String,
    private val workflow: StudyWorkflowRepository,
    private val notesRepository: NoteRepository,
    private val sessionManager: SessionManager,
    private val noteTypeSuggester: RuleBasedNoteTypeSuggester = RuleBasedNoteTypeSuggester(),
    private val focusActions: FocusSessionActions,
    private val learningItems: LearningItemRepository? = null,
    private val clockSample: () -> ClockSample = { ClockSample(System.currentTimeMillis(), SystemClock.elapsedRealtime()) },
    private val pendingEdits: com.guanyi.mirra.domain.maintenance.PendingEditRegistry? = null,
) : ViewModel() {
    private val mutableFinishUi = MutableStateFlow<SessionFinishUiState>(SessionFinishUiState.PreparingConfirmation)
    val finishUi: StateFlow<SessionFinishUiState> = mutableFinishUi
    var closeoutSnapshot by mutableStateOf<CloseoutSnapshot?>(null)
        private set
    private var durableCloseout by mutableStateOf<FocusCloseoutState?>(null)
    private var finishOperationRunning = false
    private var completionDelivered = false
    private var completionCallback: ((String) -> Unit)? = null
    private var rotatingDraft by mutableStateOf(false)
    val isReadingQualified: Boolean get() = durableCloseout == FocusCloseoutState.ACTIVE
    val canEditLearning: Boolean get() = durableCloseout == FocusCloseoutState.ACTIVE &&
        mutableFinishUi.value == SessionFinishUiState.Idle && !rotatingDraft &&
        acceptingEdits.value &&
        session.value?.let { it.activeSlot == 1 && it.endedAt == null } == true
    private val canObserveLearning: Boolean get() = durableCloseout == FocusCloseoutState.ACTIVE &&
        mutableFinishUi.value !is SessionFinishUiState.Saving && mutableFinishUi.value !is SessionFinishUiState.SaveFailed &&
        mutableFinishUi.value !is SessionFinishUiState.Completed
    private val canSaveDraft: Boolean get() = canObserveLearning && !rotatingDraft &&
        session.value?.let { it.activeSlot == 1 && it.endedAt == null } == true

    fun requestFinishConfirmation() {
        if (!canEditLearning && !rotatingDraft) return
        if (mutableFinishUi.value != SessionFinishUiState.Idle) return
        mutableFinishUi.value = SessionFinishUiState.PreparingConfirmation
        error = null
        viewModelScope.launch {
            try {
                check(workflow.getCloseoutState(sessionId) == FocusCloseoutState.ACTIVE) { "当前阅读状态已变化" }
                val latest = checkNotNull(workflow.observeSession(sessionId).first()) { "Session 不存在" }
                mutableFinishUi.value = SessionFinishUiState.Confirming(latest.currentPage.toString())
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                error = "保存被中断，请重试"
                if (durableCloseout == FocusCloseoutState.ACTIVE) mutableFinishUi.value = SessionFinishUiState.Idle
            }
            catch (failure: Exception) {
                error = failure.message ?: "笔记保存失败，请重试"
                if (durableCloseout == FocusCloseoutState.ACTIVE) mutableFinishUi.value = SessionFinishUiState.Idle
            }
        }
    }
    fun changeEndPage(text: String) {
        if (mutableFinishUi.value !is SessionFinishUiState.Confirming) return
        mutableFinishUi.value = SessionFinishUiState.Confirming(text.filter(Char::isDigit))
        error = null
    }
    fun cancelFinishConfirmation() {
        if (mutableFinishUi.value is SessionFinishUiState.Confirming && durableCloseout == FocusCloseoutState.ACTIVE) {
            mutableFinishUi.value = SessionFinishUiState.Idle
            error = null
        }
    }
    fun confirmFinish(onCompleted: (String) -> Unit) {
        val confirming = mutableFinishUi.value as? SessionFinishUiState.Confirming ?: return
        if (finishOperationRunning || durableCloseout != FocusCloseoutState.ACTIVE) return
        val page = confirming.endPageText.toIntOrNull()
        if (page == null || page < 1) { error = "请输入有效结束页码"; return }
        val current = session.value ?: return
        if (page < current.currentPage) { error = "不能低于已经记录的阅读位置"; return }
        finishOperationRunning = true
        completionCallback = onCompleted
        mutableFinishUi.value = SessionFinishUiState.Saving
        error = null
        viewModelScope.launch {
            try {
                // The dialog's page stays temporary. No end boundary exists until the Note is durable.
                flushPendingEdits(noteFirst = true)
                check(workflow.getCloseoutState(sessionId) == FocusCloseoutState.ACTIVE) { "当前阅读状态已变化" }
                val latest = checkNotNull(workflow.observeSession(sessionId).first()) { "Session 不存在" }
                require(page >= latest.currentPage) { "不能低于已经记录的阅读位置" }
                learningItems?.get(latest.learningItemId)?.let { item ->
                    require(page <= item.totalPages) { "结束页必须在书籍范围内" }
                }
                val sample = clockSample() // Exactly once, after successful flush and latest-page validation.
                acceptFinishResult(sessionManager.finish(sessionId, page, sample))
            }
            catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                restoreAfterFinishFailure(cancelled, confirming)
            }
            catch (failure: Exception) { restoreAfterFinishFailure(failure, confirming) }
            finally { finishOperationRunning = false }
        }
    }
    fun retryFinish(onCompleted: (String) -> Unit) {
        if (finishOperationRunning || mutableFinishUi.value !is SessionFinishUiState.SaveFailed) return
        finishOperationRunning = true
        completionCallback = onCompleted
        mutableFinishUi.value = SessionFinishUiState.Saving
        viewModelScope.launch {
            try {
                if (workflow.getCloseoutState(sessionId) == FocusCloseoutState.ACTIVE) {
                    restoreCloseout(FocusCloseoutState.ACTIVE)
                    mutableFinishUi.value = SessionFinishUiState.Idle
                } else acceptFinishResult(sessionManager.retryPendingFinish(sessionId))
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                restoreAfterFinishFailure(cancelled, null)
            }
            catch (failure: Exception) { restoreAfterFinishFailure(failure, null) }
            finally { finishOperationRunning = false }
        }
    }
    fun dispatchCompletion(onCompleted: (String) -> Unit) {
        completionCallback = onCompleted
        dispatchCurrentCompletion()
    }
    fun releaseCompletionCallback(onCompleted: (String) -> Unit) {
        if (completionCallback === onCompleted) completionCallback = null
    }
    private fun dispatchCurrentCompletion() {
        val completed = mutableFinishUi.value as? SessionFinishUiState.Completed ?: return
        val handler = completionCallback ?: return
        if (!completionDelivered) { completionDelivered = true; handler(completed.sessionId) }
    }
    private suspend fun acceptFinishResult(result: SessionFinishResult) {
        when (result) {
            is SessionFinishResult.Completed -> {
                durableCloseout = FocusCloseoutState.COMPLETED
                mutableFinishUi.value = SessionFinishUiState.Completed(result.session.id)
                dispatchCurrentCompletion()
            }
            is SessionFinishResult.PendingRetry -> {
                restoreCloseout(FocusCloseoutState.PENDING)
                mutableFinishUi.value = SessionFinishUiState.SaveFailed(true, "阅读已结束，保存失败，请重试")
            }
        }
    }
    private suspend fun restoreAfterFinishFailure(failure: Exception, confirming: SessionFinishUiState.Confirming?) {
        error = failure.message ?: "保存失败，请重试"
        try {
            when (val state = workflow.getCloseoutState(sessionId)) {
                FocusCloseoutState.ACTIVE -> {
                    durableCloseout = state
                    mutableFinishUi.value = confirming ?: SessionFinishUiState.SaveFailed(false, error!!)
                }
                FocusCloseoutState.PENDING -> {
                    restoreCloseout(state)
                    mutableFinishUi.value = SessionFinishUiState.SaveFailed(true, error!!)
                }
                FocusCloseoutState.COMPLETED -> {
                    restoreCloseout(state)
                    mutableFinishUi.value = SessionFinishUiState.Completed(sessionId)
                    dispatchCurrentCompletion()
                }
                else -> preserveClosedStateOnReadFailure("无法确认保存状态，请重试")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            preserveClosedStateOnReadFailure("无法确认保存状态，请重试")
        }
    }
    private fun preserveClosedStateOnReadFailure(message: String) {
        val closed = durableCloseout in setOf(FocusCloseoutState.PENDING, FocusCloseoutState.COMPLETED) || closeoutSnapshot != null
        if (!closed) durableCloseout = null
        mutableFinishUi.value = SessionFinishUiState.SaveFailed(closed, message)
    }
    private suspend fun restoreCloseout(state: FocusCloseoutState?) {
        if (state == null || state == FocusCloseoutState.ABORTED) {
            preserveClosedStateOnReadFailure("无法确认阅读状态，请重试")
            return
        }
        if (state == FocusCloseoutState.PENDING && durableCloseout == FocusCloseoutState.COMPLETED) return
        val previous = durableCloseout
        durableCloseout = state
        when (state) {
            // A heartbeat can emit ACTIVE again while a save is pending. It must not unlock editing.
            FocusCloseoutState.ACTIVE -> if (previous != FocusCloseoutState.ACTIVE && !finishOperationRunning &&
                mutableFinishUi.value == SessionFinishUiState.PreparingConfirmation) mutableFinishUi.value = SessionFinishUiState.Idle
            FocusCloseoutState.PENDING -> {
                val snapshot = try { checkNotNull(workflow.getCloseoutSnapshot(sessionId)) { "结束记录不完整" } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (durableCloseout == FocusCloseoutState.COMPLETED) return
                    closeoutSnapshot = null
                    if (!finishOperationRunning) mutableFinishUi.value = SessionFinishUiState.SaveFailed(true, "结束记录读取失败，请重试")
                    return
                }
                // Room's suspended return may arrive after the owned finish has already completed B.
                if (durableCloseout == FocusCloseoutState.COMPLETED) return
                closeoutSnapshot = snapshot
                if (!finishOperationRunning) mutableFinishUi.value = SessionFinishUiState.SaveFailed(true, "阅读已结束，保存失败，请重试")
            }
            FocusCloseoutState.COMPLETED -> if (!finishOperationRunning) mutableFinishUi.value = SessionFinishUiState.Completed(sessionId)
            FocusCloseoutState.ABORTED -> Unit // Rejected by the safe read-state gate above.
        }
    }
    val session: StateFlow<StudySessionEntity?> = workflow.observeSession(sessionId).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
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
    private var debounceJob: Job? = null
    private val pendingSaves = mutableListOf<Deferred<Result<Unit>>>()
    private val pendingPageWrites = mutableListOf<Deferred<Result<Unit>>>()
    private var pageWriteError: Throwable? = null
    private var draftTypeManuallySelected = false
    private var draftPageManuallyEdited = false
    private val saveMutex = Mutex()
    private val evidenceMutex = Mutex()
    private val pendingRegistration = pendingEdits?.register(::freezeAndFlushForMaintenance)
    val acceptingEdits = pendingEdits?.acceptingEdits ?: MutableStateFlow(true)
    val focusStatus = focusActions.focusStatus
    val intervention = focusActions.intervention
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val bookName = workflow.observeSession(sessionId).flatMapLatest { current ->
        if (current == null || learningItems == null) flowOf("")
        else learningItems.observe(current.learningItemId).map { it?.name.orEmpty() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    var focusError by mutableStateOf<String?>(null)
        private set

    suspend fun refreshFocusState(sample: ClockSample? = null) {
        if (canObserveLearning) focusActions.refresh(sessionId, sample ?: clockSample())
    }

    suspend fun observeFocusEvidence(screenNonInteractive: Boolean, pageVisibleAndFocused: Boolean) = evidenceMutex.withLock {
        if (!canObserveLearning) return@withLock
        val sample = clockSample()
        refreshFocusState(sample)
        if (canObserveLearning) focusActions.observeEvidence(sessionId, sample, screenNonInteractive, pageVisibleAndFocused)
    }

    fun reportEvidence(screenNonInteractive: Boolean, pageVisibleAndFocused: Boolean) {
        if (!canObserveLearning) return
        viewModelScope.launch { observeFocusEvidence(screenNonInteractive, pageVisibleAndFocused) }
    }

    private fun focusAction(refreshOnFailure: Boolean = false, block: suspend (ClockSample) -> FocusActionResult) {
        if (!canEditLearning) return
        viewModelScope.launch {
            if (!canEditLearning) return@launch
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
            try { workflow.observeCloseoutState(sessionId).collect { restoreCloseout(it) } }
            catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                preserveClosedStateOnReadFailure("无法读取保存状态，请重试")
            }
            catch (_: Exception) {
                preserveClosedStateOnReadFailure("无法读取保存状态，请重试")
            }
        }
        viewModelScope.launch {
            while (isActive) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }

    fun changeContent(value: String) = bufferedEdit {
        if (!canEditLearning) return@bufferedEdit
        draftContent = value
        if (!draftTypeManuallySelected) {
            draftType = noteTypeSuggester.suggest(value)
        }
        savedMessage = ""
        error = null
        scheduleAutoSave()
    }

    fun changePage(value: String) = bufferedEdit {
        if (!canEditLearning) return@bufferedEdit
        draftPage = value.filter(Char::isDigit)
        draftPageManuallyEdited = true
        scheduleAutoSave()
    }

    fun changeType(value: NoteSemanticType) = bufferedEdit {
        if (!canEditLearning) return@bufferedEdit
        draftType = value
        draftTypeManuallySelected = true
        scheduleAutoSave()
    }

    fun syncCurrentPage(page: Int) = bufferedEdit {
        if (!canEditLearning) return@bufferedEdit
        currentPageText = page.toString()
        if (draftContent.isBlank() && !draftPageManuallyEdited) {
            draftPage = page.toString()
        }
    }

    fun saveAndContinue() = bufferedEdit {
        if (!canEditLearning) return@bufferedEdit
        debounceJob?.cancel()
        rotatingDraft = true
        startNoteSave {
            try { saveDraft(); resetDraft() } finally { rotatingDraft = false }
        }
    }

    fun updatePage(value: String) = bufferedEdit {
        if (!canEditLearning) return@bufferedEdit
        val filtered = value.filter(Char::isDigit)
        currentPageText = filtered
        val page = filtered.toIntOrNull() ?: return@bufferedEdit
        val persistedPage = session.value?.currentPage ?: return@bufferedEdit
        if (page < persistedPage) {
            // Keep partial input editable (40 -> 4 -> 42), without persisting a rollback.
            return@bufferedEdit
        }
        if (draftContent.isBlank() && !draftPageManuallyEdited) {
            draftPage = page.toString()
        }
        pendingPageWrites += viewModelScope.async {
            editResult { sessionManager.updatePage(sessionId, page) }.also {
                pageWriteError = it.exceptionOrNull()
                it.onFailure { failure -> error = failure.message ?: "页码保存失败" }
            }
        }
    }

    fun flushDraft() = bufferedEdit {
        if (!canSaveDraft) return@bufferedEdit
        debounceJob?.cancel()
        startNoteSave { if (canSaveDraft) saveDraft() }
    }

    fun leave(onLeft: () -> Unit) {
        if (!canEditLearning) return
        mutableFinishUi.value = SessionFinishUiState.PreparingConfirmation
        viewModelScope.launch {
            try {
                flushPendingEdits()
                check(workflow.getCloseoutState(sessionId) == FocusCloseoutState.ACTIVE) { "阅读已结束" }
                draftContent = ""; mutableFinishUi.value = SessionFinishUiState.Idle; onLeft()
            }
            catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                error = "保存被中断，请重试"
                if (durableCloseout == FocusCloseoutState.ACTIVE) mutableFinishUi.value = SessionFinishUiState.Idle
            }
            catch (failure: Exception) {
                error = failure.message ?: "笔记保存失败"
                if (durableCloseout == FocusCloseoutState.ACTIVE) mutableFinishUi.value = SessionFinishUiState.Idle
            }
        }
    }

    private fun scheduleAutoSave() {
        debounceJob?.cancel()
        if (draftContent.isBlank()) return
        debounceJob = viewModelScope.launch {
            delay(500)
            // A cancellable confirmation does not suspend ordinary Note persistence.
            bufferedEdit { if (canSaveDraft) startNoteSave { saveDraft() } }
        }
    }

    private suspend fun editResult(block: suspend () -> Unit): Result<Unit> = try {
        block(); Result.success(Unit)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { Result.failure(failure) }

    private fun startNoteSave(block: suspend () -> Unit) {
        pendingSaves += viewModelScope.async {
            editResult(block).also { it.onFailure { failure -> error = failure.message ?: "笔记保存失败" } }
        }
    }

    internal suspend fun flushPendingEdits(noteFirst: Boolean = false) {
        debounceJob?.cancelAndJoin()
        val saves = pendingSaves.toList()
        val pages = pendingPageWrites.toList()
        // Await actual outcomes, not just Job completion: saves/page writes may have failed.
        val outcomes = (saves + pages).map { deferred ->
            try { deferred.await() } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                Result.failure<Unit>(cancelled)
            }
        }
        pendingSaves.removeAll(saves.toSet()); pendingPageWrites.removeAll(pages.toSet())
        outcomes.forEach { it.getOrThrow() }
        // Closeout must not repair an unsaved page if saving the final Note fails.
        if (noteFirst) saveDraft()
        val latest = checkNotNull(workflow.observeSession(sessionId).first()) { "Session 不存在" }
        val requestedPage = currentPageText.toIntOrNull()
        if (requestedPage != null && requestedPage > 0) {
            if (requestedPage > latest.currentPage) {
                // A previous failed Deferred is gone; retry the unchanged input before confirming.
                val outcome = editResult { sessionManager.updatePage(sessionId, requestedPage) }
                pageWriteError = outcome.exceptionOrNull()
                outcome.getOrThrow()
            } else {
                // Matching or partial smaller input already has a durable, non-regressing position.
                pageWriteError = null
            }
        }
        pageWriteError?.let { throw it }
        if (!noteFirst) saveDraft() // Leave/regular flush keeps its established ordering.
    }

    private suspend fun saveDraft() = saveMutex.withLock {
        check(workflow.getCloseoutState(sessionId) == FocusCloseoutState.ACTIVE) { "阅读已结束" }
        val currentSession = checkNotNull(workflow.observeSession(sessionId).first()) { "Session 不存在" }
        check(currentSession.activeSlot == 1 && currentSession.endedAt == null) { "阅读已结束" }
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

    private fun bufferedEdit(block: () -> Unit) {
        if (pendingRegistration == null) block() else pendingRegistration.edit(block)
    }

    private suspend fun freezeAndFlushForMaintenance() {
        debounceJob?.cancelAndJoin()
        val current = workflow.observeSession(sessionId).first()
        if (workflow.getCloseoutState(sessionId) == FocusCloseoutState.ACTIVE &&
            current?.let { it.activeSlot == 1 && it.endedAt == null } == true) {
            flushPendingEdits()
        } else {
            val pending = (pendingSaves + pendingPageWrites).toList()
            pending.forEach { it.await().getOrThrow() }
            pendingSaves.clear(); pendingPageWrites.clear()
        }
    }

    override fun onCleared() {
        pendingRegistration?.close()
        super.onCleared()
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
    onOpenLearningProtection: () -> Unit = {},
    monitoringRuntime: MonitoringPlatformRuntime? = null,
    readingRecordRepository: ReadingRecordRepository? = null,
    onFocusSurfaceChanged: (Boolean) -> Unit = {},
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val currentSession = session
    val status by viewModel.focusStatus.collectAsStateWithLifecycle()
    val finishUi by viewModel.finishUi.collectAsStateWithLifecycle()
    val intervention by viewModel.intervention.collectAsStateWithLifecycle()
    val bookName by viewModel.bookName.collectAsStateWithLifecycle()
    val prompt = intervention?.takeIf { !it.dismissed && it.sessionId == currentSession?.id }
    var breakChoice by remember { mutableStateOf(false) }
    var reasonsVisible by remember(prompt?.promptToken) { mutableStateOf(false) }
    var handledExternalRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var notesVisible by rememberSaveable(currentSession?.id) { mutableStateOf(false) }
    var pageVisible by rememberSaveable(currentSession?.id) { mutableStateOf(false) }
    var detailsVisible by rememberSaveable(currentSession?.id) { mutableStateOf(false) }
    var focusDisplay by rememberSaveable(currentSession?.id) { mutableStateOf(false) }
    var focusControlsVisible by rememberSaveable(currentSession?.id) { mutableStateOf(false) }
    val acceptingEdits by viewModel.acceptingEdits.collectAsStateWithLifecycle()
    val canEdit = viewModel.canEditLearning && acceptingEdits
    val currentFocusStatus = status.takeIf {
        currentSession != null && currentSession.activeSlot == 1 && currentSession.endedAt == null &&
            viewModel.isReadingQualified && it.sessionId == currentSession.id
    }
    val capabilitiesFlow = remember(monitoringRuntime) {
        monitoringRuntime?.capabilities?.state ?: MutableStateFlow<MonitoringCapabilities?>(null)
    }
    val capabilities by capabilitiesFlow.collectAsStateWithLifecycle()
    val recordFlow = remember(currentSession?.id, readingRecordRepository) {
        currentSession?.id?.let { readingRecordRepository?.observe(it) }?.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(null) // Optional details unavailable; never replace missing facts with zero or healthy evidence.
        } ?: flowOf(null)
    }
    val record by recordFlow.collectAsStateWithLifecycle(initialValue = null)
    val currentRecord = record?.takeIf { it.session.id == currentSession?.id }
    val qualifiedMonitoring = projectLearningProtection(capabilities, monitoringRuntime?.binding, currentSession,
        currentRecord?.context, SystemClock.elapsedRealtime())
    val monitoring = if (qualifiedMonitoring.activeSessionId == null && currentFocusStatus != null) {
        // A controller may already publish durable coverage while the optional Room detail stream loads.
        // Without its matching Context, never elevate service readiness or claim monitoring is healthy.
        qualifiedMonitoring.copy(activeSessionId = currentFocusStatus.sessionId, coverage = currentFocusStatus.coverage,
            serviceStatus = LearningProtectionServiceStatus.NOT_CONFIRMED)
    } else qualifiedMonitoring
    DisposableEffect(viewModel, onFinished) {
        viewModel.dispatchCompletion(onFinished)
        onDispose { viewModel.releaseCompletionCallback(onFinished) }
    }
    LaunchedEffect(finishUi) {
        if (finishUi is SessionFinishUiState.Completed) viewModel.dispatchCompletion(onFinished)
    }
    LaunchedEffect(externalRequest?.id, currentSession?.id, prompt?.promptToken, canEdit) {
        val request = externalRequest ?: return@LaunchedEffect
        if (handledExternalRequestId == request.id) return@LaunchedEffect
        if (currentSession == null || !canEdit) return@LaunchedEffect
        handledExternalRequestId = request.id
        if (currentSession.activeSlot == 1 && request.sessionId == currentSession.id &&
            request.promptToken == prompt?.promptToken) {
            when (request.action) {
                com.guanyi.mirra.domain.intervention.InterventionNavigationAction.OPEN_ALLOWANCE -> reasonsVisible = true
                com.guanyi.mirra.domain.intervention.InterventionNavigationAction.OPEN_FINISH ->
                    viewModel.requestFinishConfirmation()
                else -> Unit
            }
        }
        onExternalRequestHandled()
    }
    fun hideReasons() { viewModel.setPromptVisible(false); reasonsVisible = false }
    fun finishReading() {
        hideReasons(); breakChoice = false; notesVisible = false; pageVisible = false; detailsVisible = false
        viewModel.requestFinishConfirmation()
    }
    LaunchedEffect(prompt?.promptToken) {
        if (prompt != null) { focusDisplay = false; focusControlsVisible = false }
    }
    LaunchedEffect(currentSession?.currentPage, canEdit) {
        if (currentSession != null) {
            viewModel.syncCurrentPage(currentSession.currentPage)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, monitoringRuntime) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.flushDraft()
            if (event == Lifecycle.Event.ON_RESUME) monitoringRuntime?.recheckUsageWithoutQuery()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.flushDraft()
        }
    }
    BackHandler {
        when {
            finishUi is SessionFinishUiState.Confirming -> viewModel.cancelFinishConfirmation()
            finishUi != SessionFinishUiState.Idle -> Unit
            reasonsVisible -> hideReasons()
            breakChoice -> breakChoice = false
            notesVisible -> notesVisible = false
            pageVisible -> pageVisible = false
            detailsVisible -> detailsVisible = false
            focusDisplay -> { focusDisplay = false; focusControlsVisible = false }
            prompt != null -> viewModel.dismissPrompt()
            else -> viewModel.leave(onBack)
        }
    }
    if (finishUi == SessionFinishUiState.PreparingConfirmation && !viewModel.isReadingQualified) {
        Column(Modifier.fillMaxSize().padding(20.dp)) { Text("正在读取保存状态…") }
        return
    }
    if (finishUi is SessionFinishUiState.Saving || finishUi is SessionFinishUiState.SaveFailed ||
        finishUi is SessionFinishUiState.Completed) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val failed = finishUi as? SessionFinishUiState.SaveFailed
            val heading = when {
                finishUi is SessionFinishUiState.Saving -> "正在保存本次阅读…"
                finishUi is SessionFinishUiState.Completed || failed?.logicallyClosed == true -> "阅读已结束"
                else -> "正在确认保存状态"
            }
            Text(heading,
                style = MaterialTheme.typography.headlineMedium)
            viewModel.closeoutSnapshot?.let { frozen ->
                Text("这次读到：第 ${frozen.requestedEndPage} 页")
                Text("本次阅读 ${formatReadingElapsed(readingElapsedMillis(currentSession, frozen, viewModel.now, true))}")
            }
            if (failed != null) {
                Text(failed.message, color = MirraTheme.colors.danger)
                MirraPrimaryButton(onClick = { viewModel.retryFinish(onFinished) }, modifier = Modifier.fillMaxWidth()) {
                    Text("重试保存")
                }
            } else Text("正在保存…")
        }
        return
    }
    SessionEvidenceReporter(viewModel, keepScreenOn = focusDisplay && canEdit && finishUi == SessionFinishUiState.Idle &&
        !notesVisible && !pageVisible && !detailsVisible && !reasonsVisible && !breakChoice,
        onFocusSurfaceChanged = onFocusSurfaceChanged)
    SessionReadingStage(
        bookName = bookName, currentPage = currentSession?.currentPage,
        elapsed = formatReadingElapsed(readingElapsedMillis(currentSession, viewModel.closeoutSnapshot, viewModel.now, !viewModel.isReadingQualified)),
        monitoring = monitoring, focusDisplay = focusDisplay, controlsVisible = focusControlsVisible, canEdit = canEdit,
        canStartBreak = currentFocusStatus?.type !in setOf(SessionSegmentType.BREAK, SessionSegmentType.TEMPORARY_ALLOWANCE),
        onBack = { viewModel.leave(onBack) }, onPage = { pageVisible = true }, onNotes = { notesVisible = true },
        onDetails = { detailsVisible = true }, onBreak = { breakChoice = true }, onFinish = ::finishReading,
        onRepairMonitoring = onOpenLearningProtection,
        onEnterFocus = { focusDisplay = true; focusControlsVisible = false },
        onExitFocus = { focusDisplay = false; focusControlsVisible = false },
        onToggleControls = { focusControlsVisible = !focusControlsVisible },
        error = viewModel.error.takeUnless { finishUi is SessionFinishUiState.Confirming || notesVisible || pageVisible } ?: viewModel.focusError,
        stateContent = {
            if (canEdit && currentFocusStatus != null && currentFocusStatus.type != SessionSegmentType.UNMONITORED) {
                SessionFocusContent(currentFocusStatus, { breakChoice = true }, viewModel::finishBreak,
                    viewModel::finishAllowance, viewModel::extendAllowance, showBreakAction = false, centered = true)
            } else Text("阅读中", style = MaterialTheme.typography.titleMedium)
        },
        interventionContent = {
            if (prompt != null && canEdit) {
                LaunchedEffect(prompt.eventId, prompt.segmentId) { onPromptComposed(prompt) }
                InterventionContent(bookName, prompt, false, { reasonsVisible = true }, viewModel::selectAllowanceReason,
                    viewModel::returnToStudy, viewModel::grantAllowance, ::finishReading, viewModel::dismissPrompt)
            }
        },
    )
    // Draft rotation temporarily denies edits, not ownership of the editor window.
    // Keep its state/scroll mounted and disable controls; durable closeout still removes it.
    val hasActiveNoteWindow = viewModel.isReadingQualified &&
        currentSession?.let { it.activeSlot == 1 && it.endedAt == null } == true
    if (notesVisible && hasActiveNoteWindow) SessionNoteSheet(viewModel, notes, canEdit, { notesVisible = false }, { id ->
        notesVisible = false; onOpenNote(id)
    })
    if (detailsVisible) SessionMonitoringDetails(monitoring, currentRecord, { detailsVisible = false }, onOpenLearningProtection)
    if (pageVisible && canEdit) AlertDialog(onDismissRequest = { pageVisible = false }, title = { Text("阅读进度") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(viewModel.currentPageText, viewModel::updatePage, label = { Text("当前页码") },
                modifier = Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            Text("有效页码会自动保存；阅读进度只前进。", style = MaterialTheme.typography.bodySmall)
            viewModel.error?.let { Text(it, color = MirraTheme.colors.danger) }
        }
    }, confirmButton = { MirraTextAction({ pageVisible = false }) { Text("完成") } })
    val confirming = finishUi as? SessionFinishUiState.Confirming
    if (confirming != null) AlertDialog(onDismissRequest = viewModel::cancelFinishConfirmation,
        title = { Text("结束本次阅读？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("这次读到：第 ${confirming.endPageText} 页")
                OutlinedTextField(confirming.endPageText, viewModel::changeEndPage,
                    label = { Text("结束页码") }, modifier = Modifier.fillMaxWidth())
                viewModel.error?.let { Text(it, color = MirraTheme.colors.danger) }
            }
        },
        dismissButton = { MirraTextAction(viewModel::cancelFinishConfirmation) { Text("继续阅读") } },
        confirmButton = {
            MirraPrimaryButton(onClick = { viewModel.confirmFinish(onFinished) }, modifier = Modifier.testTag("confirm-session-finish")) {
                Text("结束本次阅读")
            }
        })
    if (breakChoice && canEdit) AlertDialog(onDismissRequest = { breakChoice = false }, title = { Text("休息") },
        text = {
            Column {
                MirraTextAction({ breakChoice = false; viewModel.startBreak(5) }, Modifier.fillMaxWidth()) { Text("休息 5 分钟") }
                MirraTextAction({ breakChoice = false; viewModel.startBreak(10) }, Modifier.fillMaxWidth()) { Text("休息 10 分钟") }
            }
        }, confirmButton = { MirraTextAction({ breakChoice = false }) { Text("取消") } })
    if (reasonsVisible && prompt != null && canEdit) Dialog(onDismissRequest = ::hideReasons) {
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

@Composable
fun SessionMonitoringBanner(
    coverage: com.guanyi.mirra.data.local.entity.MonitoringCoverage?,
    onOpenLearningProtection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val explanation = when (coverage) {
        com.guanyi.mirra.data.local.entity.MonitoringCoverage.NONE -> "本次没有手机分心监测，阅读和笔记仍会正常记录。"
        com.guanyi.mirra.data.local.entity.MonitoringCoverage.PARTIAL -> "本次部分时间无法确认手机使用，不会当作完整专注。"
        com.guanyi.mirra.data.local.entity.MonitoringCoverage.FULL, null -> return
    }
    Column(modifier.fillMaxWidth().testTag("session-monitoring-banner"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(explanation, color = MirraTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        MirraTextAction(onOpenLearningProtection) { Text("检查学习保护") }
    }
}

internal val NoteSemanticType.label: String
    get() = when (this) {
        NoteSemanticType.QUOTE -> "摘录"
        NoteSemanticType.SUMMARY -> "总结"
        NoteSemanticType.UNDERSTANDING -> "我的理解"
        NoteSemanticType.QUESTION -> "问题"
    }
