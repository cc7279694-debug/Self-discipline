package com.guanyi.mirra

import android.content.Context
import androidx.room.Room
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.preferences.AppPreferencesRepository
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultNoteRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.data.repository.NoteRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.di.AppContainer
import com.guanyi.mirra.domain.DefaultSessionManager
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import com.guanyi.mirra.domain.SessionManager
import com.guanyi.mirra.navigation.TopLevelDestination
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class TestAppContainer(context: Context) : AppContainer, AutoCloseable {
    private val database = Room.inMemoryDatabaseBuilder(context, MirraDatabase::class.java).build()
    override val appPreferencesRepository: AppPreferencesRepository = object : AppPreferencesRepository {
        private val destination = MutableStateFlow(TopLevelDestination.Start)
        override val lastDestination: Flow<TopLevelDestination> = destination
        override suspend fun setLastDestination(destination: TopLevelDestination) {
            this.destination.value = destination
        }
    }
    override val learningItemRepository: LearningItemRepository = DefaultLearningItemRepository(database)
    override val studyWorkflowRepository: StudyWorkflowRepository = DefaultStudyWorkflowRepository(
        database,
        RuleBasedSummaryEngine(),
        IntentExpiryPolicy(),
    )
    override val noteRepository: NoteRepository = DefaultNoteRepository(database)
    override val sessionManager: SessionManager = DefaultSessionManager(studyWorkflowRepository)
    override val startup: Deferred<Unit> = CompletableDeferred(Unit)

    override fun close() = database.close()
}
