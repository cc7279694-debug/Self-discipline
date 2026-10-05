package com.guanyi.mirra.di

import com.guanyi.mirra.domain.SessionRecoveryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CloseoutStartupRecoveryTest {
    @Test fun pendingFailureRemainsRetryableButRunsEveryBootstrapOwner() = runTest {
        val calls = mutableListOf<String>(); val errors = mutableListOf<Throwable>()
        val failure = IllegalStateException("B failed")
        runCloseoutStartup({ calls += "recover"; SessionRecoveryResult.PendingRetry("s", failure) },
            { calls += "channels" }, { calls += "dnd" }, { calls += "images" }, { calls += "search" }, errors::add)
        assertEquals(listOf("recover", "channels", "dnd", "images", "search"), calls)
        assertEquals(listOf(failure), errors)
    }
    @Test fun startupChannelCleanupFailureStillRunsDndAndStorageBootstrap() = runTest {
        val calls = mutableListOf<String>(); val errors = mutableListOf<Throwable>()
        runCloseoutStartup({ SessionRecoveryResult.Ready }, { calls += "channels"; error("cleanup failed") },
            { calls += "dnd" }, { calls += "images" }, { calls += "search" }, errors::add)
        assertEquals(listOf("channels", "dnd", "images", "search"), calls)
        assertEquals(1, errors.size)
    }
    @Test fun ordinaryRecoveryFailureStillCleansAllOwnersAndRemainsVisible() = runTest {
        val calls = mutableListOf<String>(); val errors = mutableListOf<Throwable>()
        val failure = IllegalStateException("recovery failed")
        try {
            runCloseoutStartup({ throw failure }, { calls += "channels" }, { calls += "dnd" },
                { calls += "images" }, { calls += "search" }, errors::add)
            fail("Must propagate recovery failure")
        } catch (caught: IllegalStateException) { assertSame(failure, caught) }
        assertEquals(listOf("channels", "dnd", "images", "search"), calls)
        assertTrue(errors.contains(failure))
    }
    @Test fun multipleBootstrapFailuresAreReportedWithoutSkippingLaterSteps() = runTest {
        val calls = mutableListOf<String>(); val errors = mutableListOf<Throwable>()
        runCloseoutStartup({ SessionRecoveryResult.Ready }, { error("channel") }, { error("dnd") },
            { error("storage") }, { calls += "search" }, errors::add)
        assertEquals(listOf("search"), calls)
        assertEquals(3, errors.size)
    }
    @Test fun realJobCancellationCleansChannelsAndDndThenPropagates() = runTest {
        val entered = CompletableDeferred<Unit>(); val calls = mutableListOf<String>()
        val job = async {
            runCloseoutStartup({ entered.complete(Unit); awaitCancellation() },
                { calls += "channels" }, { calls += "dnd" }, { calls += "images" }, { calls += "search" }, {})
        }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(listOf("channels", "dnd"), calls)
    }
    @Test fun channelCancellationStillAttemptsDndThenPropagates() = runTest {
        val calls = mutableListOf<String>()
        val cancelled = CancellationException("channel cancelled")
        try {
            runCloseoutStartup({ SessionRecoveryResult.Ready }, { throw cancelled },
                { calls += "dnd" }, { calls += "images" }, { calls += "search" }, {})
            fail("Cancellation must remain visible")
        } catch (caught: CancellationException) {
            // Coroutines stack-trace recovery may copy the exception across a dispatcher boundary.
            assertEquals(cancelled.message, caught.message)
            assertTrue(caught === cancelled || caught.cause === cancelled)
        }
        assertEquals(listOf("dnd"), calls)
    }
    @Test fun timedOutImageCleanupDoesNotSkipSearch() = runTest {
        val errors = mutableListOf<Throwable>(); var searched = false
        runCloseoutStartup({ SessionRecoveryResult.Ready }, {}, {},
            { withTimeout(1) { awaitCancellation() } }, { searched = true }, errors::add)
        assertTrue(searched)
        assertEquals(1, errors.size)
    }
}
