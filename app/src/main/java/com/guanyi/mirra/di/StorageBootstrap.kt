package com.guanyi.mirra.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Startup owns the same serial boundary as restore/retry until opening or fail-closed cleanup ends. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal fun <T> startStorageBootstrap(
    scope: CoroutineScope,
    serial: Mutex,
    pendingRecovery: () -> Boolean,
    recover: suspend () -> Unit,
    construct: () -> T,
    open: suspend (T) -> Unit,
    close: suspend () -> Unit,
    blocked: () -> Unit,
): Job {
    check(serial.tryLock()) { "Storage bootstrap is already running" }

    suspend fun failClosed(failure: Exception) = withContext(NonCancellable) {
        try { close() }
        catch (closeFailure: Exception) {
            if (closeFailure !== failure) failure.addSuppressed(closeFailure)
        }
        blocked()
    }

    return try {
        val prepare: suspend () -> T
        if (pendingRecovery()) {
            // The caller returns while the maintenance scope selects every durable resource.
            prepare = {
                recover()
                currentCoroutineContext().ensureActive()
                construct()
            }
        } else {
            // Preserve the existing container.startup contract for ordinary cold startup.
            val generation = construct()
            prepare = { generation }
        }
        // ATOMIC ensures even cancellation before the first dispatch drains owners and unlocks.
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                currentCoroutineContext().ensureActive()
                open(prepare())
            } catch (failure: Exception) {
                failClosed(failure)
            } finally { serial.unlock() }
        }
    } catch (failure: Exception) {
        scope.launch(start = CoroutineStart.ATOMIC) {
            try { failClosed(failure) }
            finally { serial.unlock() }
        }
    }
}
