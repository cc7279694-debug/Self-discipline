package com.guanyi.mirra.feature.knowledge

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.model.ReadingSessionProjection
import com.guanyi.mirra.data.local.model.EffectiveReadingSource
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.data.repository.ReadingAnalyticsRepository
import com.guanyi.mirra.data.repository.ReadingInsightRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.AnalyticsTimeProvider
import com.guanyi.mirra.domain.CompletionPrediction
import com.guanyi.mirra.domain.CompletionPredictionService
import com.guanyi.mirra.domain.FirstActionResolver
import com.guanyi.mirra.domain.EffectiveReadingService
import com.guanyi.mirra.domain.EffectiveReadingEstimate
import com.guanyi.mirra.domain.EffectiveEstimateUnavailableReason
import com.guanyi.mirra.domain.PredictionConfidence
import com.guanyi.mirra.domain.PredictionUnavailableReason
import com.guanyi.mirra.domain.ReadingAnalyticsService
import com.guanyi.mirra.domain.insights.ReadingPaceInsight
import com.guanyi.mirra.ui.components.MirraPrimaryButton
import com.guanyi.mirra.ui.components.FirstActionInput
import com.guanyi.mirra.ui.components.MirraProgress
import com.guanyi.mirra.ui.components.MirraSecondaryButton
import com.guanyi.mirra.ui.components.MirraTextAction
import com.guanyi.mirra.ui.theme.MirraTheme
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CreateLearningItemViewModel(private val repository: LearningItemRepository) : ViewModel() {
    var error by mutableStateOf<String?>(null)
        private set
    var isSubmitting by mutableStateOf(false)
        private set

    fun create(
        name: String,
        totalPages: String,
        currentPage: String,
        firstAction: String,
        setAsMainline: Boolean,
        onCreated: (String) -> Unit,
    ) {
        if (isSubmitting) return
        isSubmitting = true
        error = null
        viewModelScope.launch {
            runCatching {
                repository.create(
                    name = name,
                    totalPages = totalPages.toInt(),
                    currentPage = currentPage.toInt(),
                    firstAction = firstAction,
                    setAsMainline = setAsMainline,
                )
            }.onSuccess { onCreated(it.id) }
                .onFailure { error = it.message ?: "无法创建" }
            isSubmitting = false
        }
    }
}

@Composable
fun CreateLearningItemScreen(
    viewModel: CreateLearningItemViewModel,
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
    showMainlineOption: Boolean = false,
    defaultSetAsMainline: Boolean = false,
) {
    var name by remember { mutableStateOf("") }
    var totalPages by remember { mutableStateOf("") }
    var currentPage by remember { mutableStateOf("1") }
    var firstAction by remember { mutableStateOf("") }
    var setAsMainline by remember { mutableStateOf(defaultSetAsMainline) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("创建学习内容", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(name, { name = it }, label = { Text("书名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(totalPages, { totalPages = it.filter(Char::isDigit) }, label = { Text("总页数") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(currentPage, { currentPage = it.filter(Char::isDigit) }, label = { Text("当前页") }, modifier = Modifier.fillMaxWidth())
        FirstActionInput(firstAction, { firstAction = it }, currentPage.toIntOrNull() ?: 0, enabled = !viewModel.isSubmitting)
        if (showMainlineOption) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = setAsMainline, onCheckedChange = { setAsMainline = it }, enabled = !viewModel.isSubmitting)
                Text("设为主线")
            }
        }
        viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { viewModel.create(name, totalPages, currentPage, firstAction, setAsMainline, onCreated) },
            enabled = !viewModel.isSubmitting && name.isNotBlank() && totalPages.isNotBlank() && currentPage.isNotBlank() &&
                FirstActionResolver.validationError(firstAction) == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("创建") }
        OutlinedButton(onClick = onBack, enabled = !viewModel.isSubmitting, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
}

private data class ReadingTimeGeneration(val generation: Long, val time: AnalyticsTimeContext)
private data class TimedReadingSources(
    val generation: Long,
    val time: AnalyticsTimeContext,
    val recent: Result<List<ReadingSessionProjection>>,
    val history: Result<List<ReadingSessionProjection>>,
    val effective: Result<EffectiveReadingSource>,
)
private data class TimedReadingInsight(val generation: Long, val insight: ReadingPaceInsight?)

private fun <T> readingResult(read: () -> Flow<T>): Flow<Result<T>> = flow { emitAll(read()) }
    .map { Result.success(it) }.catch {
        if (it is CancellationException) throw it
        emit(Result.failure(it))
    }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LearningItemDetailViewModel(
    itemId: String,
    private val learningItems: LearningItemRepository,
    private val workflow: StudyWorkflowRepository,
    private val readingAnalytics: ReadingAnalyticsRepository,
    private val analyticsService: ReadingAnalyticsService,
    private val predictionService: CompletionPredictionService,
    private val timeProvider: AnalyticsTimeProvider,
    private val effectiveService: EffectiveReadingService,
    private val readingInsights: ReadingInsightRepository? = null,
) : ViewModel() {
    var error by mutableStateOf<String?>(null)
        private set
    var isSubmitting by mutableStateOf(false)
        private set
    private val timeContext = MutableStateFlow(ReadingTimeGeneration(0, timeProvider.snapshot()))
    private val hasActiveWorkflow = combine(workflow.observeActiveIntent(), workflow.observeActiveSession()) { intent, session ->
        intent?.learningItemId == itemId || session?.learningItemId == itemId
    }
    private val sources = timeContext.flatMapLatest { generation ->
        val time = generation.time
        val today = time.now.atZone(time.zoneId).toLocalDate()
        val from = today.minusDays(29).atStartOfDay(time.zoneId).toInstant().toEpochMilli()
        // One captured clock/zone per generation; do not mix an old query with a new window.
        combine(
            readingResult { readingAnalytics.observeRecentForItem(itemId, from, time.now.toEpochMilli()) },
            readingResult { readingAnalytics.observeHistory(itemId) },
            readingResult { readingAnalytics.observeEffectiveRecentForItem(itemId, from, time.now.toEpochMilli()) },
        ) { recent, history, effective -> TimedReadingSources(generation.generation, time, recent, history, effective) }
    }

    // An immediate empty emission keeps optional insight loading independent of the existing detail.
    // Generation identity also clears stale insight on same-clock refresh and during source handoff.
    private val insights = timeContext.flatMapLatest { generation ->
        flow {
            emit(TimedReadingInsight(generation.generation, null))
            readingInsights?.let { repository ->
                emitAll(flow { emitAll(repository.observeForItem(itemId, generation.time)) }
                    .map { TimedReadingInsight(generation.generation, it.insight) }
                    .catch {
                        if (it is CancellationException) throw it
                        emit(TimedReadingInsight(generation.generation, null))
                    })
            }
        }
    }

    val uiState = combine(
        learningItems.observe(itemId),
        workflow.observeLatestSummaryForItem(itemId),
        sources,
        insights,
        hasActiveWorkflow,
    ) { item, session, source, insight, active ->
        val time = source.time
        val allHistory = source.history.getOrDefault(emptyList())
        val window = source.recent.getOrNull()?.let { analyticsService.selectAnalyticsWindow(it, time) }
        val prediction = item?.let { predictionService.predict(it, window, time) }
        val effective = item?.let { book -> source.effective.getOrNull()?.let { effectiveService.estimate(book, it, time) } }
        val sourceError = when {
            source.effective.isFailure -> "暂时无法读取有效阅读节奏"
            source.recent.isFailure -> "暂时无法计算阅读节奏"
            source.history.isFailure -> "暂时无法读取阅读历史"
            effective?.window == null && effective?.unavailableReason == EffectiveEstimateUnavailableReason.ARITHMETIC_OUT_OF_RANGE ->
                "暂时无法计算有效阅读节奏"
            else -> null
        }
        LearningItemDetailUiState(
            item = item,
            lastSummary = session?.generatedSummary,
            analytics = if (sourceError == null) buildAnalyticsUi(allHistory, window, prediction, time, effective) else null,
            history = buildHistoryUi(allHistory, time),
            isAnalyticsLoading = false,
            analyticsError = sourceError,
            insight = insight.insight.takeIf { insight.generation == source.generation },
            hasActiveWorkflow = active,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LearningItemDetailUiState(),
    )

    fun refreshTimeContext() {
        timeContext.value = ReadingTimeGeneration(timeContext.value.generation + 1, timeProvider.snapshot())
    }

    fun setMainline() = perform { learningItems.setMainline(it.id) }

    fun pause() = perform { learningItems.pause(it.id) }

    fun resume() = perform { learningItems.resume(it.id) }

    fun complete() = perform { learningItems.complete(it.id) }

    fun updateFirstAction(firstAction: String, onSaved: () -> Unit = {}, onIntentReady: ((String) -> Unit)? = null) = perform {
        val updated = learningItems.updateFirstAction(it.id, firstAction)
        onIntentReady?.invoke(workflow.createIntent(updated.id).id)
        onSaved()
    }

    fun begin(onIntentReady: (String) -> Unit, onFirstActionRequired: () -> Unit = {}) {
        if (isSubmitting) return
        val item = uiState.value.item ?: return
        isSubmitting = true
        error = null
        viewModelScope.launch {
            try {
                if (workflow.observeActiveIntent().first() == null && FirstActionResolver.needsConfirmation(item)) {
                    onFirstActionRequired()
                } else {
                    onIntentReady(workflow.createIntent(item.id).id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IllegalArgumentException) {
                if (FirstActionResolver.needsConfirmation(item)) onFirstActionRequired()
                else error = failure.message ?: "无法开始"
            } catch (failure: Exception) {
                error = failure.message ?: "无法开始"
            } finally { isSubmitting = false }
        }
    }

    private fun perform(action: suspend (LearningItemEntity) -> Unit) {
        if (isSubmitting) return
        val item = uiState.value.item ?: return
        isSubmitting = true
        error = null
        viewModelScope.launch {
            try { action(item) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "操作失败" }
            finally { isSubmitting = false }
        }
    }

    private fun buildAnalyticsUi(
        history: List<ReadingSessionProjection>,
        window: com.guanyi.mirra.domain.SelectedAnalyticsWindow?,
        prediction: CompletionPrediction?,
        time: AnalyticsTimeContext,
        effective: EffectiveReadingEstimate?,
    ): LearningItemAnalyticsUi {
        val latest = analyticsService.qualify(history, time).firstOrNull()
        val speed = prediction?.overallPagesPerHour
        val effectiveWindow = effective?.window
        return LearningItemAnalyticsUi(
            recentReadingText = latest?.let {
                "${relativeDate(it.endedDate, time)} · ${formatDuration(it.duration)} · ${it.pagesRead} 页"
            },
            speedText = if (effectiveWindow != null) "有效阅读速度约 ${effectiveWindow.effectivePagesPerHour.roundToInt()} 页/小时"
                else speed?.let { "最近约 ${it.roundToInt()} 页/小时" },
            speedBasisText = if (effectiveWindow != null) "根据最近 ${effectiveWindow.days} 天 ${effectiveWindow.sessions.size} 次完整阅读"
                else speed?.let { "根据最近 ${window?.days} 天 ${window?.sessions?.size} 次正常阅读" },
            remainingTimeText = if (effectiveWindow != null) effective.remainingEffectiveReadingTime?.let {
                "预计还需约 ${formatDuration(it)} 有效阅读"
            } else prediction?.estimatedRemainingReadingTime?.let { "预计剩余阅读时间约 ${formatDuration(it)}" },
            completionDateText = prediction?.naturalCompletionRange?.let {
                "预计 ${dateText(it.earliest)}–${dateText(it.latest)}自然读完"
            },
            confidence = prediction?.confidence,
            unavailableText = unavailableText(prediction),
        )
    }

    private fun unavailableText(prediction: CompletionPrediction?): String? = when {
        prediction == null -> "再完成几次阅读后，这里会出现近期节奏"
        PredictionUnavailableReason.INSUFFICIENT_WINDOW in prediction.unavailableReasons ->
            "再完成几次阅读后，这里会出现近期节奏"
        PredictionUnavailableReason.ZERO_READING_SPEED in prediction.unavailableReasons -> "最近暂无页码推进"
        PredictionUnavailableReason.EXCESSIVE_VARIABILITY in prediction.unavailableReasons ||
            PredictionUnavailableReason.INSUFFICIENT_SESSIONS in prediction.unavailableReasons ||
            PredictionUnavailableReason.INSUFFICIENT_READING_DAYS in prediction.unavailableReasons ||
            PredictionUnavailableReason.INSUFFICIENT_SPAN in prediction.unavailableReasons ||
            PredictionUnavailableReason.NO_RECENT_READING in prediction.unavailableReasons ||
            PredictionUnavailableReason.LOW_CONFIDENCE in prediction.unavailableReasons ->
            "近期节奏仍在积累，暂不估算完成日期"
        else -> null
    }

    private fun buildHistoryUi(
        history: List<ReadingSessionProjection>,
        time: AnalyticsTimeContext,
    ): List<SessionHistoryUi> = history.mapNotNull { session ->
        val endedAt = session.endedAt ?: return@mapNotNull null
        val endPage = session.endPage ?: session.startPage
        SessionHistoryUi(
            id = session.sessionId,
            date = Instant.ofEpochMilli(endedAt).atZone(time.zoneId).toLocalDate(),
            duration = Duration.ofMillis((endedAt - session.startedAt).coerceAtLeast(0L)),
            startPage = session.startPage,
            endPage = endPage,
            pagesRead = (endPage.toLong() - session.startPage.toLong()).coerceAtLeast(0L),
            noteCount = session.noteCount,
            abnormal = session.endType == SessionEndType.ABNORMAL,
            excludedLabel = when (session.endType) {
                SessionEndType.EARLY -> "提前结束"
                SessionEndType.AUTO -> "自动结束"
                SessionEndType.START_INCOMPLETE -> "启动未完成"
                null -> "状态未知"
                else -> null
            },
        )
    }

    private fun relativeDate(date: LocalDate, time: AnalyticsTimeContext): String {
        val today = time.now.atZone(time.zoneId).toLocalDate()
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> dateText(date)
        }
    }

    private fun dateText(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"
}

data class LearningItemDetailUiState(
    val item: LearningItemEntity? = null,
    val lastSummary: String? = null,
    val analytics: LearningItemAnalyticsUi? = null,
    val history: List<SessionHistoryUi> = emptyList(),
    val isAnalyticsLoading: Boolean = true,
    val analyticsError: String? = null,
    val insight: ReadingPaceInsight? = null,
    val hasActiveWorkflow: Boolean = false,
)

@Composable
fun LearningItemDetailScreen(
    viewModel: LearningItemDetailViewModel,
    onStart: (String) -> Unit,
    onOpenNotes: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val item = state.item
    var confirmComplete by remember { mutableStateOf(false) }
    var firstActionEditor by remember { mutableStateOf<String?>(null) }
    var beginAfterSaving by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshTimeContext()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(14.dp))
            item?.let {
                Text(it.name, style = MaterialTheme.typography.headlineMedium, color = MirraTheme.colors.textPrimary)
                Spacer(Modifier.height(12.dp))
                Text("状态：${it.status.displayName}", color = MirraTheme.colors.textSecondary)
                Text("当前第 ${it.currentPage} 页，共 ${it.totalPages} 页", color = MirraTheme.colors.textSecondary)
                MirraProgress(
                    progress = { it.currentPage.toFloat() / it.totalPages.coerceAtLeast(1).toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                val action = if (state.hasActiveWorkflow) FirstActionResolver.forActiveIntent(it) else FirstActionResolver.resolve(it)
                Text("第一步：${action.ifBlank { "尚未确定，请选择或填写一个现在就能做的动作。" }}", color = MirraTheme.colors.textSecondary, modifier = Modifier.padding(top = 8.dp))
                MirraTextAction(onClick = {
                    beginAfterSaving = false
                    firstActionEditor = FirstActionResolver.resolve(it)
                }, enabled = !state.hasActiveWorkflow && !viewModel.isSubmitting) { Text("编辑第一步") }
                state.lastSummary?.let { summary ->
                    Text("上次总结：$summary", color = MirraTheme.colors.textSecondary)
                }
                Spacer(Modifier.height(12.dp))
                if (it.status == LearningItemStatus.IN_PROGRESS) {
                    MirraPrimaryButton(
                        onClick = { viewModel.begin(onStart) {
                            beginAfterSaving = true
                            firstActionEditor = ""
                        } },
                        enabled = !viewModel.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("开始准备") }
                    if (!it.isMainline) {
                        MirraSecondaryButton(
                            onClick = { viewModel.setMainline() },
                            enabled = !viewModel.isSubmitting,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("设为主线") }
                    }
                    MirraSecondaryButton(
                        onClick = { viewModel.pause() },
                        enabled = !viewModel.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("暂停") }
                    MirraTextAction(
                        onClick = { confirmComplete = true },
                        enabled = !viewModel.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("标记为已完成") }
                } else if (it.status == LearningItemStatus.PAUSED) {
                    MirraPrimaryButton(
                        onClick = { viewModel.resume() },
                        enabled = !viewModel.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("恢复为进行中") }
                }
                MirraSecondaryButton(onClick = { onOpenNotes(it.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("查看这本书的笔记")
                }
                viewModel.error?.let { message -> Text(message, color = MirraTheme.colors.danger) }
                state.analyticsError?.let { message -> Text(message, color = MirraTheme.colors.danger) }
            }
        }
        item {
            LearningItemAnalyticsSummary(state.analytics, Modifier.padding(top = 12.dp))
        }
        state.insight?.let { insight ->
            item { LearningItemInsightSection(insight, Modifier.padding(top = 12.dp)) }
        }
        if (state.history.isNotEmpty()) {
            item {
                Text(
                    "阅读历史",
                    color = MirraTheme.colors.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(state.history, key = { it.id }) { session ->
                SessionHistoryRow(session, onClick = { onOpenSession(session.id) })
                HorizontalDivider(color = MirraTheme.colors.divider)
            }
        }
        item {
            MirraSecondaryButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp)) { Text("返回") }
        }
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
    firstActionEditor?.let { initial ->
        var edited by remember(initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { if (!viewModel.isSubmitting) firstActionEditor = null },
            title = { Text("确定第一步") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FirstActionInput(edited, { edited = it }, item?.currentPage ?: 0, enabled = !viewModel.isSubmitting)
                    viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateFirstAction(edited, onSaved = { firstActionEditor = null },
                        onIntentReady = onStart.takeIf { beginAfterSaving })
                }, enabled = !viewModel.isSubmitting && FirstActionResolver.validationError(edited) == null) {
                    Text(if (beginAfterSaving) "确认动作，开始准备" else "保存动作")
                }
            },
            dismissButton = { TextButton(onClick = { firstActionEditor = null }, enabled = !viewModel.isSubmitting) { Text("取消") } },
        )
    }
}
