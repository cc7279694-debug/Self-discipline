package com.guanyi.mirra

import android.content.Context
import androidx.room.Room
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.preferences.AppPreferencesRepository
import com.guanyi.mirra.data.preferences.MirraThemeId
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
import com.guanyi.mirra.di.AppContainer
import com.guanyi.mirra.feature.profile.DndUserActions
import com.guanyi.mirra.feature.profile.NoopDndUserActions
import com.guanyi.mirra.domain.DefaultSessionManager
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import com.guanyi.mirra.domain.SessionManager
import com.guanyi.mirra.domain.SessionStartCoordinator
import com.guanyi.mirra.domain.RepositoryMonitoredStartStore
import com.guanyi.mirra.domain.MonitoredStartPort
import com.guanyi.mirra.platform.focus.MonitoringReadyLease
import com.guanyi.mirra.domain.DefaultSearchEngine
import com.guanyi.mirra.domain.AnalyticsTimeProvider
import com.guanyi.mirra.domain.CompletionPredictionService
import com.guanyi.mirra.domain.ReadingAnalyticsService
import com.guanyi.mirra.navigation.TopLevelDestination
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class TestAppContainer(
    private val context: Context,
    private val monitorPort: MonitoredStartPort? = null,
) : AppContainer, AutoCloseable {
    val database = Room.inMemoryDatabaseBuilder(context, MirraDatabase::class.java).build()
    private val imageSandbox = TestImageStorageSandbox(context)
    private val imageStorage = DefaultImageStorageService(imageSandbox.context)
    private val searchEngine = DefaultSearchEngine()
    private val searchIndexWriter = SearchIndexWriter(database, searchEngine)
    private val searchIndexRebuilder = SearchIndexRebuilder(database, searchEngine)
    override val appPreferencesRepository: AppPreferencesRepository = object : AppPreferencesRepository {
        private val destination = MutableStateFlow(TopLevelDestination.Start)
        private val theme = MutableStateFlow(MirraThemeId.BLUE)
        private val dnd = MutableStateFlow(false)
        override val crossAppInterventionEnabled = MutableStateFlow(false)
        override val lastDestination: Flow<TopLevelDestination> = destination
        override val themeId: Flow<MirraThemeId> = theme
        override val dndEnabled: Flow<Boolean> = dnd
        override suspend fun setLastDestination(destination: TopLevelDestination) {
            this.destination.value = destination
        }
        override suspend fun setThemeId(themeId: MirraThemeId) {
            theme.value = themeId
        }
        override suspend fun setDndEnabled(enabled: Boolean) { dnd.value = enabled }
        override suspend fun setCrossAppInterventionEnabled(enabled: Boolean) { crossAppInterventionEnabled.value = enabled }
    }
    override val learningItemRepository: LearningItemRepository = DefaultLearningItemRepository(database, searchIndexWriter = searchIndexWriter)
    override val studyWorkflowRepository: StudyWorkflowRepository = DefaultStudyWorkflowRepository(
        database,
        RuleBasedSummaryEngine(),
        IntentExpiryPolicy(),
        searchIndexWriter = searchIndexWriter,
    )
    override val noteRepository: NoteRepository = DefaultNoteRepository(database, imageStorage, searchIndexWriter = searchIndexWriter)
    override val imageRepository: ImageRepository = DefaultImageRepository(database, imageStorage, searchIndexWriter = searchIndexWriter)
    override val topicRepository: TopicRepository = DefaultTopicRepository(database, searchIndexWriter)
    override val searchRepository: SearchRepository = DefaultSearchRepository(database, searchEngine, searchIndexRebuilder)
    override val readingAnalyticsRepository: ReadingAnalyticsRepository = DefaultReadingAnalyticsRepository(database)
    override val readingRecordRepository = com.guanyi.mirra.data.repository.DefaultReadingRecordRepository(database)
    override val focusRepository: FocusRepository = DefaultFocusRepository(database)
    override val focusSessionActions = FakeFocusSessionActions()
    override val dndUserActions: DndUserActions = NoopDndUserActions()
    override val readingAnalyticsService = ReadingAnalyticsService()
    override val readingRecordService = com.guanyi.mirra.domain.ReadingRecordService()
    override val effectiveReadingService = com.guanyi.mirra.domain.EffectiveReadingService()
    override val completionPredictionService = CompletionPredictionService()
    override val analyticsTimeProvider = AnalyticsTimeProvider()
    override val sessionManager: SessionManager = DefaultSessionManager(studyWorkflowRepository)
    override val sessionStartCoordinator: SessionStartCoordinator = SessionStartCoordinator(
        RepositoryMonitoredStartStore(studyWorkflowRepository, focusRepository), monitorPort ?: object : MonitoredStartPort {
            override suspend fun preflight() = false
            override fun start(): String = error("Test container has no Android monitor")
            override suspend fun awaitReady(generation: String, timeoutMillis: Long): MonitoringReadyLease? = null
            override fun verify(lease: MonitoringReadyLease) = false
            override suspend fun verifyAfterCommit(lease: MonitoringReadyLease) = false
            override fun bind(sessionId: String, generation: String) = false
            override fun stopUnbound(generation: String) = Unit
            override fun nowWall() = System.currentTimeMillis()
        },
    )
    override val startup: Deferred<Unit> = CompletableDeferred(Unit)

    fun resolveImageStorageTestPath(relativePath: String) = imageSandbox.resolve(relativePath)

    override fun close() {
        try { database.close() } finally { imageSandbox.close() }
    }
}
