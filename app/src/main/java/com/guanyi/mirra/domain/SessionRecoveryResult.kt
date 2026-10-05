package com.guanyi.mirra.domain

sealed interface SessionRecoveryResult {
    data object Ready : SessionRecoveryResult
    data class PendingRetry(val sessionId: String, val cause: Throwable) : SessionRecoveryResult
}
