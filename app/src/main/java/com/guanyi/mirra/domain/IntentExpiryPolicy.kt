package com.guanyi.mirra.domain

class IntentExpiryPolicy(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    fun isExpired(createdAt: Long, now: Long): Boolean = now - createdAt >= timeoutMillis

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 30 * 60 * 1_000L
    }
}
