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
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.data.repository.ReadingAnalyticsRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.AnalyticsTimeProvider
import com.guanyi.mirra.domain.CompletionPrediction
import com.guanyi.mirra.domain.CompletionPredictionService
import com.guanyi.mirra.domain.FirstActionResolver
import com.guanyi.mirra.domain.PredictionConfidence
import com.guanyi.mirra.domain.PredictionUnavailableReason
import com.guanyi.mirra.domain.ReadingAnalyticsService
import com.guanyi.mirra.ui.components.MirraPrimaryButton
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CreateLearningItemViewModel(private val repository: LearningItemRepository) : ViewModel() {
    var error by mutableStateOf<String?>(null)
        private set

    fun create(
        name: String,
        totalPages: String,
        currentPage: String,
        firstAction: String,
        setAsMainline: Boolean,
        onCreated: (String) -> Unit,
    ) {
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
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("创建学习内容", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(name, { name = it }, label = { Text("书名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(totalPages, { totalPages = it.filter(Char::isDigit) }, label = { Text("总页数") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(currentPage, { currentPage = it.filter(Char::isDigit) }, label = { Text("当前页") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            firstAction,
            { firstAction = it },
            label = { Text("起步动作（选填）") },
            supportingText = { Text("未填写时使用“拿起书，翻到当前页”") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (showMainlineOption) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = setAsMainline, onCheckedChange = { setAsMainline = it })
                Text("设为主线")
            }
        }
        viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { viewModel.create(name, totalPages, currentPage, firstAction, setAsMainline, onCreated) },
            enabled = name.isNotBlank() && totalPages.isNotBlank() && currentPage.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("创建") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
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
) : ViewModel() {
    var error by mutableStateOf<String?>(null)
        private set
    var isSubmitting by mutableStateOf(false)
        private set
    private val timeContext = MutableStateFlow(timeProvider.snapshot())
    private val analyticsError = MutableStateFlow<String?>(null)
    private val recentSessions = timeContext.flatMapLatest { time ->
        val today = time.now.atZone(time.zoneId).toLocalDate()
        val from = today.minusDays(29).atStartOfDay(time.zoneId).toInstant().toEpochMilli()
        readingAnalytics.observeRecentForItem(itemId, from, time.now.toEpochMilli())
    }.catch {
        analyticsError.value = "暂时无法计算阅读节奏"
        emit(emptyList())
    }
    private val history = readingAnalytics.observeHistory(itemId).catch {
        analyticsError.value = "暂时无法读取阅读历史"
        emit(emptyList())
    }

    val uiState = combine(
        learningItems.observe(itemId),
        workflow.observeLatestSummaryForItem(itemId),
        recentSessions,
        history,
        timeContext,
    ) { item, session, recent, allHistory, time ->
        val window = analyticsService.selectAnalyticsWindow(recent, time)
        val prediction = item?.let { predictionService.predict(it, window, time) }
        LearningItemDetailUiState(
            item = item,
            lastSummary = session?.generatedSummary,
            analytics = buildAnalyticsUi(allHistory, window, prediction, time),
            history = buildHistoryUi(allHistory, time),
            isAnalyticsLoading = false,
            analyticsError = analyticsError.value,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LearningItemDetailUiState(),
    )

    fun refreshTimeContext() {
        analyticsError.value = null
        timeContext.value = timeProvider.snapshot()
    }

    fun setMainline() = perform { learningItems.setMainline(it.id) }

    fun pause() = perform { learningItems.pause(it.id) }

    fun resume() = perform { learningItems.resume(it.id) }

    fun complete() = perform { learningItems.complete(it.id) }

    fun updateFirstAction(firstAction: String) = perform {
        learningItems.updateFirstAction(it.id, firstAction)
    }

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

    private fun buildAnalyticsUi(
        history: List<ReadingSessionProjection>,
        window: com.guanyi.mirra.domain.SelectedAnalyticsWindow?,
        prediction: CompletionPrediction?,
        time: AnalyticsTimeContext,
    ): LearningItemAnalyticsUi {
        val latest = analyticsService.qualify(history, time).firstOrNull()
        val speed = prediction?.overallPagesPerHour
        return LearningItemAnalyticsUi(
            recentReadingText = latest?.let {
                "${relativeDate(it.endedDate, time)} · ${formatDuration(it.duration)} · ${it.pagesRead} 页"
            },
            speedText = speed?.let { "最近约 ${it.roundToInt()} 页/小时" },
            speedBasisText = speed?.let { "根据最近 ${window?.days} 天 ${window?.sessions?.size} 次正常阅读" },
            remainingTimeText = prediction?.estimatedRemainingReadingTime?.let {
                "预计剩余阅读时间约 ${formatDuration(it)}"
            },
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
    var firstActionEditor by remember { mutableStateOf<String?>(null) }
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
                Text("起步动作：${FirstActionResolver.resolve(it)}", color = MirraTheme.colors.textSecondary, modifier = Modifier.padding(top = 8.dp))
                MirraTextAction(onClick = { firstActionEditor = it.firstAction }) { Text("编辑起步动作") }
                state.lastSummary?.let { summary ->
                    Text("上次总结：$summary", color = MirraTheme.colors.textSecondary)
                }
                Spacer(Modifier.height(12.dp))
                if (it.status == LearningItemStatus.IN_PROGRESS) {
                    MirraPrimaryButton(
                        onClick = { viewModel.begin(onStart) },
                        enabled = !viewModel.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("开始阅读") }
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
                SessionHistoryRow(session)
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
            onDismissRequest = { firstActionEditor = null },
            title = { Text("编辑起步动作") },
            text = {
                OutlinedTextField(
                    value = edited,
                    onValueChange = { edited = it },
                    label = { Text("起步动作") },
                    supportingText = { Text("留空会使用当前阅读页生成默认动作") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateFirstAction(edited)
                    firstActionEditor = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { firstActionEditor = null }) { Text("取消") } },
        )
    }
}
