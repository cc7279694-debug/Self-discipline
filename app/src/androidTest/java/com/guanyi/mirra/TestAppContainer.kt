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
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.data.repository.DefaultSearchRepository
import com.guanyi.mirra.data.repository.DefaultTopicRepository
import com.guanyi.mirra.data.repository.SearchRepository
import com.guanyi.mirra.data.repository.TopicRepository
import com.guanyi.mirra.data.search.SearchIndexRebuilder
import com.guanyi.mirra.data.search.SearchIndexWriter
import com.guanyi.mirra.data.storage.DefaultImageStorageService
import com.guanyi.mirra.di.AppContainer
import com.guanyi.mirra.domain.DefaultSessionManager
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import com.guanyi.mirra.domain.SessionManager
import com.guanyi.mirra.domain.DefaultSearchEngine
import com.guanyi.mirra.navigation.TopLevelDestination
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class TestAppContainer(private val context: Context) : AppContainer, AutoCloseable {
    private val database = Room.inMemoryDatabaseBuilder(context, MirraDatabase::class.java).build()
    private val imageStorage = DefaultImageStorageService(context)
    private val searchEngine = DefaultSearchEngine()
    private val searchIndexWriter = SearchIndexWriter(database, searchEngine)
    private val searchIndexRebuilder = SearchIndexRebuilder(database, searchEngine)
    override val appPreferencesRepository: AppPreferencesRepository = object : AppPreferencesRepository {
        private val destination = MutableStateFlow(TopLevelDestination.Start)
        private val theme = MutableStateFlow(MirraThemeId.BLUE)
        override val lastDestination: Flow<TopLevelDestination> = destination
        override val themeId: Flow<MirraThemeId> = theme
        override suspend fun setLastDestination(destination: TopLevelDestination) {
            this.destination.value = destination
        }
        override suspend fun setThemeId(themeId: MirraThemeId) {
            theme.value = themeId
        }
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
    override val sessionManager: SessionManager = DefaultSessionManager(studyWorkflowRepository)
    override val startup: Deferred<Unit> = CompletableDeferred(Unit)

    override fun close() {
        database.close()
        context.filesDir.resolve("images").deleteRecursively()
        context.filesDir.resolve("image-work").deleteRecursively()
    }
}
