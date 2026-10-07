package com.guanyi.mirra.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.MIGRATION_1_2
import com.guanyi.mirra.data.local.MIGRATION_2_3
import com.guanyi.mirra.data.local.MIGRATION_3_4
import com.guanyi.mirra.data.preferences.AppPreferencesRepository
import com.guanyi.mirra.data.preferences.DefaultAppPreferencesRepository
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultImageRepository
import com.guanyi.mirra.data.repository.DefaultNoteRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.data.repository.ImageRepository
import com.guanyi.mirra.data.repository.NoteRepository
import com.guanyi.mirra.data.repository.DefaultReadingAnalyticsRepository
import com.guanyi.mirra.data.repository.ReadingAnalyticsRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.data.repository.DefaultSearchRepository
import com.guanyi.mirra.data.repository.DefaultTopicRepository
import com.guanyi.mirra.data.repository.SearchRepository
import com.guanyi.mirra.data.repository.TopicRepository
import com.guanyi.mirra.data.repository.FocusRepository
import com.guanyi.mirra.data.repository.DefaultFocusRepository
import com.guanyi.mirra.data.search.SearchIndexRebuilder
import com.guanyi.mirra.data.search.SearchIndexWriter
import com.guanyi.mirra.data.storage.DefaultImageStorageService
import com.guanyi.mirra.domain.DefaultSessionManager
import com.guanyi.mirra.domain.cleanupCloseoutSteps
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import com.guanyi.mirra.domain.SessionManager
import com.guanyi.mirra.domain.DefaultSearchEngine
import com.guanyi.mirra.domain.AnalyticsTimeProvider
import com.guanyi.mirra.domain.SessionStartCoordinator
import com.guanyi.mirra.domain.DndController
import com.guanyi.mirra.data.repository.RoomDndStateStore
import com.guanyi.mirra.platform.focus.AndroidDndSystem
import com.guanyi.mirra.platform.focus.AndroidDndGateway
import com.guanyi.mirra.domain.RepositoryMonitoredStartStore
import com.guanyi.mirra.platform.focus.MonitoringPlatformRuntime
import com.guanyi.mirra.domain.CompletionPredictionService
import com.guanyi.mirra.domain.ReadingAnalyticsService
import com.guanyi.mirra.feature.profile.DefaultDndUserActions
import com.guanyi.mirra.feature.profile.DndUserActions
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private val Context.mirraPreferences by preferencesDataStore(name = "mirra_preferences")

internal suspend fun runCloseoutStartup(
    recover: suspend () -> com.guanyi.mirra.domain.SessionRecoveryResult,
    channels: suspend () -> Unit, dnd: suspend () -> Unit,
    images: suspend () -> Unit, search: suspend () -> Unit,
    onIssue: (Throwable) -> Unit,
) {
    var primary: Throwable? = null
    try {
        val result = recover()
        if (result is com.guanyi.mirra.domain.SessionRecoveryResult.PendingRetry) onIssue(result.cause)
    } catch (failure: Throwable) {
        primary = failure
        if (failure !is CancellationException) onIssue(failure)
    }
    // Owned resources must be reconciled even when recovery failed or its caller was cancelled.
    withContext(NonCancellable) { withContext(Dispatchers.IO) {
        for (step in listOf(channels, dnd)) {
            try { withTimeout(5_000) { step() } }
            catch (failure: Exception) {
                onIssue(failure)
                primary?.takeUnless { it === failure }?.addSuppressed(failure)
                if (failure is CancellationException && failure !is TimeoutCancellationException && primary == null) {
                    primary = failure
                }
            }
        }
    } }
    primary?.takeIf { it is CancellationException }?.let { throw it }
    currentCoroutineContext().ensureActive()
    for (step in listOf(images, search)) {
        try { withContext(Dispatchers.IO) { step() } }
        catch (timeout: TimeoutCancellationException) { onIssue(timeout) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onIssue(failure) }
    }
    primary?.let { throw it }
}

/** Thin post-start fence; underlying Room/DND/channel owners still enforce their own races. */
internal suspend fun configureActiveSessionPresentationAndDnd(
    sessionId: String, isLearning: suspend (String) -> Boolean,
    configure: suspend (String) -> Unit, apply: suspend (String) -> Unit,
    cleanup: suspend (String) -> Unit,
) {
    if (!isLearning(sessionId)) { cleanup(sessionId); return }
    try {
        configure(sessionId)
        if (isLearning(sessionId)) apply(sessionId)
    } finally {
        if (!isLearning(sessionId)) cleanup(sessionId)
    }
}

interface AppContainer {
    val appPreferencesRepository: AppPreferencesRepository
    val learningItemRepository: LearningItemRepository
    val studyWorkflowRepository: StudyWorkflowRepository
    val noteRepository: NoteRepository
    val imageRepository: ImageRepository
    val topicRepository: TopicRepository
    val searchRepository: SearchRepository
    val readingAnalyticsRepository: ReadingAnalyticsRepository
    val readingRecordRepository: com.guanyi.mirra.data.repository.ReadingRecordRepository
    val globalReadingHistoryRepository: com.guanyi.mirra.data.repository.GlobalReadingHistoryRepository
    val trendsRepository: com.guanyi.mirra.data.repository.TrendsRepository
    val focusRepository: FocusRepository
    val focusSessionActions: com.guanyi.mirra.domain.monitoring.FocusSessionActions
    val dndUserActions: DndUserActions
    val crossAppInterventionActions: com.guanyi.mirra.feature.profile.CrossAppInterventionUserActions? get() = null
    val interventionNavigation: com.guanyi.mirra.domain.intervention.InterventionNavigationController? get() = null
    val readingAnalyticsService: ReadingAnalyticsService
    val readingRecordService: com.guanyi.mirra.domain.ReadingRecordService
    val effectiveReadingService: com.guanyi.mirra.domain.EffectiveReadingService
    val completionPredictionService: CompletionPredictionService
    val analyticsTimeProvider: AnalyticsTimeProvider
    val sessionManager: SessionManager
    val sessionStartCoordinator: SessionStartCoordinator
    val startup: Deferred<Unit>
}

class DefaultAppContainer(context: Context, private val monitoringRuntime: MonitoringPlatformRuntime) : AppContainer {
    override val effectiveReadingService = com.guanyi.mirra.domain.EffectiveReadingService()
    override val readingRecordService = com.guanyi.mirra.domain.ReadingRecordService()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = Room.databaseBuilder(
        context,
        MirraDatabase::class.java,
        "mirra.db",
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    private val imageStorage = DefaultImageStorageService(context)
    private val searchEngine = DefaultSearchEngine()
    private val searchIndexWriter = SearchIndexWriter(database, searchEngine)
    private val searchIndexRebuilder = SearchIndexRebuilder(database, searchEngine)

    override val appPreferencesRepository: AppPreferencesRepository =
        DefaultAppPreferencesRepository(context.mirraPreferences)
    override val learningItemRepository: LearningItemRepository =
        DefaultLearningItemRepository(database, searchIndexWriter = searchIndexWriter)
    override val studyWorkflowRepository: StudyWorkflowRepository =
        DefaultStudyWorkflowRepository(database, RuleBasedSummaryEngine(), IntentExpiryPolicy(), searchIndexWriter = searchIndexWriter)
    override val noteRepository: NoteRepository = DefaultNoteRepository(database, imageStorage, searchIndexWriter = searchIndexWriter)
    override val imageRepository: ImageRepository = DefaultImageRepository(database, imageStorage, searchIndexWriter = searchIndexWriter)
    override val topicRepository: TopicRepository = DefaultTopicRepository(database, searchIndexWriter)
    override val searchRepository: SearchRepository = DefaultSearchRepository(database, searchEngine, searchIndexRebuilder)
    override val readingAnalyticsRepository: ReadingAnalyticsRepository = DefaultReadingAnalyticsRepository(database)
    override val readingRecordRepository = com.guanyi.mirra.data.repository.DefaultReadingRecordRepository(database)
    override val globalReadingHistoryRepository = com.guanyi.mirra.data.repository.DefaultGlobalReadingHistoryRepository(database)
    override val trendsRepository = com.guanyi.mirra.data.repository.DefaultTrendsRepository(database)
    override val focusRepository: FocusRepository = DefaultFocusRepository(database)
    private val dndStateStore = RoomDndStateStore(database)
    private val androidDndGateway = AndroidDndGateway(context)
    private val dndController = DndController(dndStateStore, AndroidDndSystem(context))
    override val dndUserActions: DndUserActions = DefaultDndUserActions(
        preferences = appPreferencesRepository,
        activeRecordProvider = {
            studyWorkflowRepository.observeActiveSession().first()?.let { dndStateStore.get(it.id) }
        },
        pendingReleaseProvider = { dndStateStore.pendingAfterRecovery().isNotEmpty() },
        applyDnd = { sessionId -> dndController.apply(sessionId, enabled = true) },
        reconcileDnd = { dndController.reconcileAfterRecovery() },
        policyAccessProvider = androidDndGateway::policyAccessGranted,
        apiLevel = Build.VERSION.SDK_INT,
        settingsIntentFactory = androidDndGateway::settingsIntent,
    )
    private suspend fun applyDndIfEnabled(sessionId: String) {
        withContext(Dispatchers.IO) {
            runCatching { dndController.apply(sessionId, appPreferencesRepository.dndEnabled.first()) }
        }
    }
    private suspend fun configureSessionPresentationAndDnd(sessionId: String) {
        configureActiveSessionPresentationAndDnd(sessionId,
            isLearning = { focusRepository.runtimeFacts(it) != null },
            configure = {
                val enabled = runCatching { appPreferencesRepository.crossAppInterventionEnabled.first() }.getOrDefault(false)
                monitoringRuntime.interventionChannels?.configure(it, enabled)
            }, apply = ::applyDndIfEnabled,
            cleanup = { runCatching { monitoringRuntime.interventionChannels?.release(it) }; releaseDnd(it) })
    }
    private suspend fun releaseDnd(sessionId: String) {
        withContext(Dispatchers.IO) { runCatching { dndController.release(sessionId) } }
    }
    init {
        monitoringRuntime.attachFacts(focusRepository)
        monitoringRuntime.attachInterventionChannels(
            com.guanyi.mirra.data.repository.InterventionReceiptRepository(database, {
                monitoringRuntime.interventionChannels?.isCurrent(it) == true
            }),
            { sessionId -> database.sessionDao().get(sessionId)?.let { database.learningItemDao().get(it.learningItemId)?.name }
                ?: "本次学习" },
        )
    }
    override val crossAppInterventionActions = com.guanyi.mirra.feature.profile.DefaultCrossAppInterventionUserActions(
        appPreferencesRepository,
        { checkNotNull(monitoringRuntime.interventionChannels).capabilities() },
        { studyWorkflowRepository.observeActiveSession().first() != null },
        { monitoringRuntime.interventionChannels?.snapshotEnabled() == true },
        { monitoringRuntime.interventionChannels?.appVisible?.value == true },
    )
    override val interventionNavigation get() = monitoringRuntime.interventionChannels?.navigation
    override val focusSessionActions: com.guanyi.mirra.domain.monitoring.FocusSessionActions
        get() = monitoringRuntime.focusSessionActions
    override val readingAnalyticsService = ReadingAnalyticsService()
    override val completionPredictionService = CompletionPredictionService()
    override val analyticsTimeProvider = AnalyticsTimeProvider()
    override val sessionManager: SessionManager = DefaultSessionManager(studyWorkflowRepository,
        onSessionCreated = { configureSessionPresentationAndDnd(it.id) },
        closeoutWithMonitoringFacts = { id, sample, block -> monitoringRuntime.closeoutWithMonitoringFacts(id, sample, block) },
        cleanupClosedSession = { id -> cleanupCloseoutSteps(
            { monitoringRuntime.interventionChannels?.release(id) },
            { monitoringRuntime.releaseSession(id) },
            { releaseDnd(id) }) })
    override val sessionStartCoordinator: SessionStartCoordinator = SessionStartCoordinator(
        RepositoryMonitoredStartStore(studyWorkflowRepository, focusRepository), monitoringRuntime,
        onSessionCreated = { configureSessionPresentationAndDnd(it.id) },
    )
    override val startup: Deferred<Unit> = applicationScope.async {
        runCloseoutStartup(sessionManager::recoverInterruptedSession,
            { monitoringRuntime.interventionChannels?.startupCleanup() },
            { dndController.reconcileAfterRecovery() },
            { imageRepository.reconcileStorage() },
            { searchIndexRebuilder.ensureConsistent() },
            { android.util.Log.w("MirraStartup", "Bootstrap step requires retry: ${it.javaClass.simpleName}") })
    }
}
