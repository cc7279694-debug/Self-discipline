package com.guanyi.mirra.feature.start

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class StartSources(
    val items: List<LearningItemEntity>,
    val intent: StudyIntentEntity?,
    val session: StudySessionEntity?,
    val selectedItemId: String?,
    val setAsMainline: Boolean,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StartViewModel(
    private val learningItems: LearningItemRepository,
    private val workflow: StudyWorkflowRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val selectedItemId = MutableStateFlow<String?>(null)
    private val setAsMainline = MutableStateFlow(false)
    private val submission = MutableStateFlow(Pair(false, null as String?))
    private val nowMillis = MutableStateFlow(clock())

    private val sources = combine(
        learningItems.observeAll(),
        workflow.observeActiveIntent(),
        workflow.observeActiveSession(),
        combine(selectedItemId, setAsMainline, ::Pair),
    ) { items, intent, session, selection ->
        StartSources(items, intent, session, selection.first, selection.second)
    }

    private val recentReading = sources
        .map(StartSources::recentReadingItemId)
        .distinctUntilChanged()
        .flatMapLatest { itemId ->
            if (itemId == null) flowOf(null) else workflow.observeLatestNormalReading(itemId)
        }

    val uiState = combine(sources, recentReading, nowMillis, submission) { source, recent, now, submit ->
        when (val resolution = resolveStartContent(
            items = source.items,
            activeIntent = source.intent,
            activeSession = source.session,
            selectedItemId = source.selectedItemId,
            setSelectedAsMainline = source.setAsMainline,
            recentReading = recent,
            nowMillis = now,
        )) {
            is StartResolution.Content -> StartUiState(
                content = resolution.value,
                isLoading = false,
                isSubmitting = submit.first,
                errorMessage = submit.second,
            )
            is StartResolution.DataError -> StartUiState(
                isLoading = false,
                errorMessage = resolution.message,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartUiState())

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(30_000L)
                nowMillis.value = clock()
            }
        }
    }

    fun selectItem(itemId: String) {
        selectedItemId.value = itemId
        setAsMainline.value = false
        submission.value = false to null
    }

    fun setSelectedAsMainline(enabled: Boolean) {
        setAsMainline.value = enabled
    }

    fun begin(onIntentReady: (String) -> Unit) {
        val content = uiState.value.content
        val itemId: String
        val makeMainline: Boolean
        when (content) {
            is StartContentState.Mainline -> {
                itemId = content.item.id
                makeMainline = false
            }
            is StartContentState.ChooseInProgress -> {
                itemId = content.selectedItemId ?: return setError("请先选择本次学习内容")
                makeMainline = content.setSelectedAsMainline
            }
            else -> return
        }
        perform {
            onIntentReady(workflow.createIntent(itemId, setAsMainline = makeMainline).id)
        }
    }

    fun abandonIntent(onAbandoned: () -> Unit = {}) {
        val intent = uiState.value.content as? StartContentState.ActiveIntent ?: return
        perform {
            workflow.abandonIntent(intent.intentId)
            onAbandoned()
        }
    }

    private fun perform(action: suspend () -> Unit) {
        if (submission.value.first) return
        submission.value = true to null
        viewModelScope.launch {
            runCatching { action() }
                .onFailure { submission.value = false to (it.message ?: "操作失败") }
                .onSuccess { submission.value = false to null }
        }
    }

    private fun setError(message: String) {
        submission.value = false to message
    }
}

private fun StartSources.recentReadingItemId(): String? {
    if (session != null || intent != null) return null
    val mainline = items.firstOrNull {
        it.mainlineSlot != null && it.status == LearningItemStatus.IN_PROGRESS
    }
    if (mainline != null) return mainline.id
    return selectedItemId?.takeIf { selected ->
        items.any { it.id == selected && it.status == LearningItemStatus.IN_PROGRESS }
    }
}
