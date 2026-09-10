package com.guanyi.mirra.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.preferences.AppPreferencesRepository
import com.guanyi.mirra.data.preferences.DefaultAppPreferencesRepository
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultNoteRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.data.repository.NoteRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.domain.DefaultSessionManager
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import com.guanyi.mirra.domain.SessionManager
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
    val sessionManager: SessionManager
    val startup: Deferred<Unit>
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = Room.databaseBuilder(
        context,
        MirraDatabase::class.java,
        "mirra.db",
    ).build()

    override val appPreferencesRepository: AppPreferencesRepository =
        DefaultAppPreferencesRepository(context.mirraPreferences)
    override val learningItemRepository: LearningItemRepository =
        DefaultLearningItemRepository(database)
    override val studyWorkflowRepository: StudyWorkflowRepository =
        DefaultStudyWorkflowRepository(database, RuleBasedSummaryEngine(), IntentExpiryPolicy())
    override val noteRepository: NoteRepository = DefaultNoteRepository(database)
    override val sessionManager: SessionManager = DefaultSessionManager(studyWorkflowRepository)
    override val startup: Deferred<Unit> = applicationScope.async {
        sessionManager.recoverInterruptedSession()
    }
}
