package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.repository.StudyWorkflowRepository

interface SessionManager {
    suspend fun start(intentId: String, startPage: Int): StudySessionEntity
    suspend fun updatePage(sessionId: String, page: Int)
    suspend fun finish(sessionId: String, endPage: Int): StudySessionEntity
    suspend fun recoverInterruptedSession()
}

class DefaultSessionManager(
    private val workflowRepository: StudyWorkflowRepository,
    private val onSessionFinished: (String) -> Unit = {},
    private val finishWithMonitoringFacts: suspend (suspend () -> StudySessionEntity) -> StudySessionEntity = { it() },
    private val onSessionCreated: suspend (StudySessionEntity) -> Unit = {},
    private val onSessionCommitted: suspend (String) -> Unit = {},
) : SessionManager {
    override suspend fun start(intentId: String, startPage: Int): StudySessionEntity {
        val session = workflowRepository.startSession(intentId, startPage)
        onSessionCreated(session)
        return session
    }

    override suspend fun updatePage(sessionId: String, page: Int) =
        workflowRepository.updateCurrentPage(sessionId, page)

    override suspend fun finish(sessionId: String, endPage: Int): StudySessionEntity {
        val finished = finishWithMonitoringFacts {
            val committed = workflowRepository.finishSession(sessionId, endPage)
            onSessionFinished(sessionId) // Room has committed before the owned monitor is released.
            committed
        }
        onSessionCommitted(sessionId) // External DND release is after Room commit and monitor lock.
        return finished
    }

    override suspend fun recoverInterruptedSession() =
        workflowRepository.recoverInterruptedSession()
}
