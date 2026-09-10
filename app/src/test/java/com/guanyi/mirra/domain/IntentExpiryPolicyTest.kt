package com.guanyi.mirra.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentExpiryPolicyTest {
    private val policy = IntentExpiryPolicy(timeoutMillis = 30 * 60 * 1_000L)

    @Test
    fun `intent remains active immediately before thirty minute boundary`() {
        assertFalse(policy.isExpired(createdAt = 1_000L, now = 1_800_999L))
    }

    @Test
    fun `intent expires at thirty minute boundary`() {
        assertTrue(policy.isExpired(createdAt = 1_000L, now = 1_801_000L))
    }
}
