package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.StudySessionEntity

sealed interface SessionFinishResult {
    data class Completed(val session: StudySessionEntity) : SessionFinishResult
    data class PendingRetry(val sessionId: String) : SessionFinishResult
}
