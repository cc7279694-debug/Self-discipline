package com.guanyi.mirra.di

import android.content.Context
import com.guanyi.mirra.data.backup.*
import com.guanyi.mirra.data.maintenance.*
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import com.guanyi.mirra.domain.maintenance.PendingEditRegistry
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.preferences.AppPreferencesRepository
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

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
    val fullBackupService: com.guanyi.mirra.domain.backup.FullBackupService? get() = null
    val dataExportService: com.guanyi.mirra.domain.backup.DataExportService? get() = null
    val pendingEdits: PendingEditRegistry? get() = null
    val appPreferencesRepository: AppPreferencesRepository
    val learningItemRepository: LearningItemRepository
    val studyWorkflowRepository: StudyWorkflowRepository
    val noteRepository: NoteRepository
    val imageRepository: ImageRepository
    val topicRepository: TopicRepository
    val searchRepository: SearchRepository
    val readingAnalyticsRepository: ReadingAnalyticsRepository
    val readingInsightRepository: com.guanyi.mirra.data.repository.ReadingInsightRepository
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

/** A closed Room owner must never be reopened merely to repeat its shutdown. */
internal fun checkpointAndCloseDatabase(database: MirraDatabase) {
    if (database.isOpen) {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
            check(it.moveToFirst() && it.getInt(0) == 0) { "Database reader drain incomplete" }
        }
    }
    database.close()
    check(!database.isOpen) { "Database did not close" }
}

class DefaultAppContainer(
    private val context: Context,
    private val monitoringRuntime: MonitoringPlatformRuntime,
    private val backupHost: BackupStorageHost? = null,
    override val storagePaths: RestoreResources,
    override val backupPreferences: ManagedPreferences,
    private val database: MirraDatabase,
    private val applicationScope: CoroutineScope,
) : AppContainer, BackupStorageOwner {
    override val effectiveReadingService = com.guanyi.mirra.domain.EffectiveReadingService()
    override val readingRecordService = com.guanyi.mirra.domain.ReadingRecordService()
    private val storageClose = Mutex()
    private var storageOwnersClosed = false
    override val storageGate = StorageMaintenanceGate(leaseScope = applicationScope)
    override val pendingEdits = PendingEditRegistry()
    override val backupDatabase get() = database
    private val imageStorage = DefaultImageStorageService(context)
    private val searchEngine = DefaultSearchEngine()
    private val searchIndexWriter = SearchIndexWriter(database, searchEngine)
    private val searchIndexRebuilder = SearchIndexRebuilder(database, searchEngine)

    override val appPreferencesRepository: AppPreferencesRepository =
        GateAppPreferencesRepository(backupPreferences.repository, storageGate)
    override val learningItemRepository: LearningItemRepository =
        GateLearningItemRepository(DefaultLearningItemRepository(database, searchIndexWriter = searchIndexWriter), storageGate)
    override val studyWorkflowRepository: StudyWorkflowRepository =
        GateStudyWorkflowRepository(DefaultStudyWorkflowRepository(database, RuleBasedSummaryEngine(), IntentExpiryPolicy(), searchIndexWriter = searchIndexWriter), storageGate)
    override val noteRepository: NoteRepository = GateNoteRepository(DefaultNoteRepository(database, imageStorage, searchIndexWriter = searchIndexWriter), storageGate)
    override val imageRepository: ImageRepository = GateImageRepository(DefaultImageRepository(database, imageStorage, searchIndexWriter = searchIndexWriter), storageGate)
    override val topicRepository: TopicRepository = GateTopicRepository(DefaultTopicRepository(database, searchIndexWriter), storageGate)
    override val searchRepository: SearchRepository = GateSearchRepository(DefaultSearchRepository(database, searchEngine, searchIndexRebuilder), storageGate)
    override val readingAnalyticsRepository: ReadingAnalyticsRepository = DefaultReadingAnalyticsRepository(database)
    override val readingInsightRepository = com.guanyi.mirra.data.repository.DefaultReadingInsightRepository(readingAnalyticsRepository)
    override val readingRecordRepository = com.guanyi.mirra.data.repository.DefaultReadingRecordRepository(database)
    override val globalReadingHistoryRepository = com.guanyi.mirra.data.repository.DefaultGlobalReadingHistoryRepository(database)
    override val trendsRepository = com.guanyi.mirra.data.repository.DefaultTrendsRepository(database)
    override val focusRepository: FocusRepository = GateFocusRepository(DefaultFocusRepository(database), storageGate)
    private val dndStateStore = GateDndStateStore(RoomDndStateStore(database), storageGate)
    private val androidDndGateway = AndroidDndGateway(context)
    private val dndSystem = AndroidDndSystem(context)
    private val dndController = DndController(dndStateStore, dndSystem)
    override val dndUserActions: DndUserActions = DefaultDndUserActions(
        preferences = appPreferencesRepository,
        activeRecordProvider = {
            studyWorkflowRepository.observeActiveSession().first()?.let { dndStateStore.get(it.id) }
        },
        pendingReleaseProvider = { dndStateStore.pendingAfterRecovery().isNotEmpty() },
        applyDnd = { sessionId -> storageGate.writerOperation { markDndDeviceUse(); dndController.apply(sessionId, enabled = true) } },
        reconcileDnd = { storageGate.writerOperation { dndController.reconcileAfterRecovery() } },
        policyAccessProvider = androidDndGateway::policyAccessGranted,
        apiLevel = Build.VERSION.SDK_INT,
        settingsIntentFactory = androidDndGateway::settingsIntent,
    )
    private suspend fun applyDndIfEnabled(sessionId: String) {
        withContext(Dispatchers.IO) {
            storageGate.writerOperation {
                runCatching {
                    val enabled = appPreferencesRepository.dndEnabled.first()
                    if (enabled && dndSystem.hasAccess()) markDndDeviceUse()
                    dndController.apply(sessionId, enabled)
                }
            }
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
        withContext(Dispatchers.IO) { storageGate.writerOperation { runCatching { dndController.release(sessionId) } } }
    }
    init {
        monitoringRuntime.attachMaintenance(storageGate)
        monitoringRuntime.attachFacts(focusRepository)
        monitoringRuntime.attachInterventionChannels(
            GateInterventionReceiptRepository(com.guanyi.mirra.data.repository.InterventionReceiptRepository(database, {
                monitoringRuntime.interventionChannels?.isCurrent(it) == true
            }), storageGate),
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
    override val sessionManager: SessionManager = GateSessionManager(DefaultSessionManager(studyWorkflowRepository,
        onSessionCreated = { configureSessionPresentationAndDnd(it.id) },
        closeoutWithMonitoringFacts = { id, sample, block -> monitoringRuntime.closeoutWithMonitoringFacts(id, sample, block) },
        cleanupClosedSession = { id -> cleanupCloseoutSteps(
            { monitoringRuntime.interventionChannels?.release(id) },
            { monitoringRuntime.releaseSession(id) },
            { releaseDnd(id) }) }), storageGate)
    override val sessionStartCoordinator: SessionStartCoordinator = SessionStartCoordinator(
        RepositoryMonitoredStartStore(studyWorkflowRepository, focusRepository), monitoringRuntime,
        onSessionCreated = { configureSessionPresentationAndDnd(it.id) },
        operationAdmission = { action -> storageGate.writerOperation { action() } },
    )
    override val startup: Deferred<Unit> = applicationScope.async {
        storageGate.writerOperation { runCloseoutStartup(sessionManager::recoverInterruptedSession,
            { monitoringRuntime.interventionChannels?.startupCleanup() },
            { dndController.reconcileAfterRecovery() },
            { imageRepository.reconcileStorage() },
            { searchIndexRebuilder.ensureConsistent() },
            { android.util.Log.w("MirraStartup", "Bootstrap step requires retry: ${it.javaClass.simpleName}") }) }
    }
    override val fullBackupService = backupHost?.let { DefaultFullBackupService(context, this, it) }?.also { service ->
        // App bootstrap has already resolved the separate restore journal before constructing us.
        applicationScope.launch(Dispatchers.IO) {
            try { service.reclaimPreviousProcesses() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                android.util.Log.w("MirraBackup", "Private backup cleanup requires retry: ${failure.javaClass.simpleName}")
            }
        }
    }
    override val dataExportService = fullBackupService?.let { service ->
        com.guanyi.mirra.data.export.DefaultDataExportService(context, service,
            requireCurrent = { checkNotNull(backupHost).requireCurrent(this) }).also { export ->
            // Only the new export workspace is touched. Process identity protects other
            // current containers; preparation retries cleanup if startup cannot complete it.
            applicationScope.launch(Dispatchers.IO) {
                try { export.reclaimPreviousProcesses() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    android.util.Log.w("MirraExport", "Private export cleanup requires retry: ${failure.javaClass.simpleName}")
                }
            }
        }
    }

    private fun markDndDeviceUse() {
        // Device-local clue survives a portable restore; never exported or replayed as ownership.
        androidDurableFiles().write(File(context.noBackupFilesDir, "dnd-device-used"), byteArrayOf(1))
    }

    override suspend fun assertRuntimeQuiescent() {
        monitoringRuntime.assertMaintenanceQuiescent()
        check(dndStateStore.pendingAfterRecovery().isEmpty()) { "Mirra 勿扰清理尚未完成" }
        val knownOwner = File(context.noBackupFilesDir, "dnd-device-used").exists() ||
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM session_focus_contexts WHERE dndRuleId IS NOT NULL OR priorDndInterruptionFilter IS NOT NULL").use { it.moveToFirst(); it.getLong(0)>0 }
        if (Build.VERSION.SDK_INT >= 29) {
            if (!dndSystem.hasAccess()) check(!knownOwner) { "无法确认 Mirra 勿扰已释放，请先检查系统勿扰权限" }
            else dndSystem.findOwnedRule()?.let { id ->
                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                val rule = manager.getAutomaticZenRule(id)
                check(rule == null || !rule.isEnabled || (Build.VERSION.SDK_INT >= 35 &&
                    manager.getAutomaticZenRuleState(id) == android.service.notification.Condition.STATE_FALSE)) {
                    "无法确认 Mirra 勿扰规则已停止，请在系统设置停用该规则后重试"
                }
            }
        } else check(!dndSystem.legacyOwnershipIntact()) { "Mirra 勿扰清理尚未完成" }
    }

    override suspend fun closeStorageOwners() = withContext(NonCancellable) {
        storageClose.withLock {
            if (storageOwnersClosed) return@withLock
            monitoringRuntime.closeMaintenanceOwners()
            applicationScope.coroutineContext[Job]?.cancelAndJoin()
            backupPreferences.close()
            checkpointAndCloseDatabase(database)
            // A failed step remains retryable; only a fully drained generation is closed.
            storageOwnersClosed = true
        }
    }
}
