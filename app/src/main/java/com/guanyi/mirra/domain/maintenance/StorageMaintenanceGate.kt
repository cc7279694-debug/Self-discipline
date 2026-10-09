package com.guanyi.mirra.domain.maintenance

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RetiredStorageEpochException : IllegalStateException("Storage resource epoch has been retired")

/**
 * One gate belongs to exactly one container and its captured Room/preferences/files instances.
 * Coordinator tokens may refresh after an unchanged backup; the resource owner never changes.
 * A restore retires this gate before effects and constructs a new gate for the selected resources.
 */
class StorageMaintenanceGate(
    val coordinator: MaintenanceCoordinator = MaintenanceCoordinator(),
    private val leaseScope: CoroutineScope,
) {
    @Volatile private var retired = false

    private class RegisteredOperation(
        val gate: StorageMaintenanceGate,
        val permit: MaintenanceCoordinator.OperationPermit,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RegisteredOperation>
    }

    suspend fun <T> operation(
        block: suspend CoroutineScope.(MaintenanceCoordinator.OperationPermit) -> T,
    ): T {
        requireLiveEpoch()
        return coordinator.withOperation(coordinator.state.value.generation) { permit ->
            withContext(RegisteredOperation(this@StorageMaintenanceGate, permit)) { block(permit) }
        }
    }

    /** Explicit reuse still performs coordinator registration/validation; never retries admission. */
    suspend fun <T> operation(
        permit: MaintenanceCoordinator.OperationPermit,
        block: suspend CoroutineScope.(MaintenanceCoordinator.OperationPermit) -> T,
    ): T {
        requireLiveEpoch()
        return coordinator.withNestedOperation(permit) {
            withContext(RegisteredOperation(this@StorageMaintenanceGate, permit)) { block(permit) }
        }
    }

    /**
     * Only a context installed by this gate can reuse its already registered permit. A detached
     * child carrying a sealed context fails; it is never silently given the latest token.
     */
    suspend fun <T> writerOperation(block: suspend () -> T): T {
        requireLiveEpoch()
        val registered = currentCoroutineContext()[RegisteredOperation]
        return if (registered?.gate === this) operation(registered.permit) { block() }
        else operation { block() }
    }

    /** Call within this coordinator's EXCLUSIVE scope, before closing or replacing any resource. */
    suspend fun retire(owner: MaintenanceCoordinator.ExclusivePermit, reason: String) {
        owner.markUncertain(reason)
        check(coordinator.state.value.phase == MaintenancePhase.BLOCKED) { "Foreign exclusive owner" }
        retired = true
    }

    /**
     * Registers an external camera request until its callback and full import/discard chain finish.
     * A request without a callback blocks exclusive maintenance (or makes its drain time out).
     */
    suspend fun openLease(): OperationLease {
        requireLiveEpoch()
        val admitted = CompletableDeferred<MaintenanceCoordinator.OperationPermit>()
        val complete = CompletableDeferred<Unit>()
        val registration = leaseScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                operation { permit ->
                    admitted.complete(permit)
                    complete.await()
                }
            } catch (failure: Throwable) {
                admitted.completeExceptionally(failure)
            }
        }
        return try {
            OperationLease(this, admitted.await(), complete, registration)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                complete.complete(Unit)
                registration.join()
            }
            throw failure
        }
    }

    class OperationLease internal constructor(
        private val gate: StorageMaintenanceGate,
        private val permit: MaintenanceCoordinator.OperationPermit,
        private val complete: CompletableDeferred<Unit>,
        private val registration: Job,
    ) {
        suspend fun <T> operation(block: suspend CoroutineScope.() -> T): T =
            gate.operation(permit) { block() }

        suspend fun releaseAndJoin() = withContext(NonCancellable) {
            complete.complete(Unit)
            registration.join()
        }
    }

    private fun requireLiveEpoch() {
        if (retired) throw RetiredStorageEpochException()
    }
}
