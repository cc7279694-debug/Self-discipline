package com.guanyi.mirra.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.MIGRATION_1_2
import com.guanyi.mirra.data.local.MIGRATION_2_3
import com.guanyi.mirra.data.preferences.AppPreferencesRepository
import com.guanyi.mirra.data.preferences.DefaultAppPreferencesRepository
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
import com.guanyi.mirra.domain.DefaultSessionManager
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import com.guanyi.mirra.domain.SessionManager
import com.guanyi.mirra.domain.DefaultSearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

private val Context.mirraPreferences by preferencesDataStore(name = "mirra_preferences")

interface AppContainer {
    val appPreferencesRepository: AppPreferencesRepository
    val learningItemRepository: LearningItemRepository
    val studyWorkflowRepository: StudyWorkflowRepository
    val noteRepository: NoteRepository
    val imageRepository: ImageRepository
    val topicRepository: TopicRepository
    val searchRepository: SearchRepository
    val sessionManager: SessionManager
    val startup: Deferred<Unit>
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = Room.databaseBuilder(
        context,
        MirraDatabase::class.java,
        "mirra.db",
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
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
    override val sessionManager: SessionManager = DefaultSessionManager(studyWorkflowRepository)
    override val startup: Deferred<Unit> = applicationScope.async {
        sessionManager.recoverInterruptedSession()
        runCatching { imageRepository.reconcileStorage() }
        runCatching { searchIndexRebuilder.ensureConsistent() }
    }
}
