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
) : SessionManager {
    override suspend fun start(intentId: String, startPage: Int) =
        workflowRepository.startSession(intentId, startPage)

    override suspend fun updatePage(sessionId: String, page: Int) =
        workflowRepository.updateCurrentPage(sessionId, page)

    override suspend fun finish(sessionId: String, endPage: Int): StudySessionEntity {
        val finished = workflowRepository.finishSession(sessionId, endPage)
        onSessionFinished(sessionId) // Room has committed before the owned monitor is released.
        return finished
    }

    override suspend fun recoverInterruptedSession() =
        workflowRepository.recoverInterruptedSession()
}
