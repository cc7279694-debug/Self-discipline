package com.guanyi.mirra.domain.maintenance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class MaintenancePhase { OPEN, DRAINING, EXCLUSIVE, BLOCKED }

class MaintenanceGeneration internal constructor(val number: Long)

data class MaintenanceSnapshot(
    val phase: MaintenancePhase,
    val generation: MaintenanceGeneration,
    val activePermits: Int,
    val blockedReason: String? = null,
)

class MaintenanceUnavailableException(val phase: MaintenancePhase) :
    IllegalStateException("Maintenance admission unavailable: $phase")

class StaleMaintenanceGenerationException : IllegalStateException("Expired maintenance generation")

class InvalidOperationPermitException : IllegalStateException("Operation permit is no longer usable")

/**
 * Memory-only admission/drain protocol; NOT a wired App-wide barrier.
 *
 * Work and compensation must stay inside the scoped block. Structured children are joined
 * before its registration is released. Detached work must explicitly register through
 * [withNestedOperation] BEFORE its parent permit is sealed; capturing a permit is not admission.
 * There is deliberately no CoroutineContext element that grants inherited write authority.
 * This class neither performs nor durably recovers resource switches.
 */
class MaintenanceCoordinator {
    private val admission = Mutex()
    private val mutableState = MutableStateFlow(
        MaintenanceSnapshot(MaintenancePhase.OPEN, MaintenanceGeneration(0), 0),
    )
    val state = mutableState.asStateFlow()
    private val registrations = mutableMapOf<OperationPermit, Int>()
    private var exclusiveOwner: ExclusivePermit? = null

    class OperationPermit internal constructor(private val coordinator: MaintenanceCoordinator) {
        internal var sealed = false // Accessed only under admission mutex.

        /**
         * Idempotently revoke further nested registration. The scoped body/registered children
         * still count until they actually finish; early or duplicate release cannot fake quiescence.
         * Normal callers need not call this: the scoped API seals and releases in finally.
         */
        suspend fun release() = coordinator.seal(this)
    }

    class ExclusivePermit internal constructor(private val coordinator: MaintenanceCoordinator) {
        internal val drained = CompletableDeferred<Unit>()

        /** Latch BEFORE any future unsafe resource effect. There is no reopen-from-BLOCKED API. */
        suspend fun markUncertain(reason: String) = coordinator.block(this, reason)
    }

    private class Registration(val permit: OperationPermit) {
        var finished = false
    }

    suspend fun <T> withOperation(
        generation: MaintenanceGeneration = state.value.generation,
        block: suspend CoroutineScope.(OperationPermit) -> T,
    ): T {
        val registration = admission.withLock {
            val current = mutableState.value
            if (current.phase != MaintenancePhase.OPEN) throw MaintenanceUnavailableException(current.phase)
            // Identity, not just a number: tokens cannot cross coordinators or generations.
            if (generation !== current.generation) throw StaleMaintenanceGenerationException()
            register(OperationPermit(this))
        }
        try {
            return coroutineScope { block(registration.permit) }
        } finally {
            withContext(NonCancellable) { finish(registration, seal = true) }
        }
    }

    suspend fun <T> withNestedOperation(
        permit: OperationPermit,
        block: suspend CoroutineScope.(OperationPermit) -> T,
    ): T {
        val registration = admission.withLock {
            if (permit.sealed || !registrations.containsKey(permit)) throw InvalidOperationPermitException()
            // This belongs to an already admitted operation, so DRAINING does not reject it.
            register(permit)
        }
        try {
            return coroutineScope { block(permit) }
        } finally {
            withContext(NonCancellable) { finish(registration, seal = false) }
        }
    }

    suspend fun <T> withExclusive(
        timeoutMillis: Long,
        block: suspend CoroutineScope.(ExclusivePermit) -> T,
    ): T {
        require(timeoutMillis > 0) { "Drain timeout must be positive" }
        val owner = admission.withLock {
            val current = mutableState.value
            if (current.phase != MaintenancePhase.OPEN) throw MaintenanceUnavailableException(current.phase)
            ExclusivePermit(this).also {
                exclusiveOwner = it
                mutableState.value = current.copy(phase = MaintenancePhase.DRAINING)
                if (registrations.isEmpty()) it.drained.complete(Unit)
            }
        }
        try {
            // No IO, timeout wait, or operation body runs while holding the admission mutex.
            withTimeout(timeoutMillis) {
                owner.drained.await()
                admission.withLock {
                    check(exclusiveOwner === owner && registrations.isEmpty())
                    check(mutableState.value.phase == MaintenancePhase.DRAINING)
                    mutableState.value = mutableState.value.copy(phase = MaintenancePhase.EXCLUSIVE)
                }
            }
            return coroutineScope { block(owner) }
        } finally {
            withContext(NonCancellable) { finishExclusive(owner) }
        }
    }

    /** Must be called under admission. Each body has a distinct, exactly-once registration. */
    private fun register(permit: OperationPermit): Registration {
        registrations[permit] = (registrations[permit] ?: 0) + 1
        mutableState.value = mutableState.value.copy(activePermits = mutableState.value.activePermits + 1)
        return Registration(permit)
    }

    private suspend fun seal(permit: OperationPermit) = admission.withLock {
        // Ended or duplicate releases are harmless and cannot affect another operation.
        if (registrations.containsKey(permit)) permit.sealed = true
    }

    private suspend fun finish(registration: Registration, seal: Boolean) = admission.withLock {
        if (registration.finished) return@withLock
        registration.finished = true
        val permit = registration.permit
        if (seal) permit.sealed = true
        val remaining = checkNotNull(registrations[permit]) - 1
        if (remaining == 0) registrations.remove(permit) else registrations[permit] = remaining
        mutableState.value = mutableState.value.copy(activePermits = mutableState.value.activePermits - 1)
        if (registrations.isEmpty()) exclusiveOwner?.drained?.complete(Unit)
    }

    private suspend fun block(owner: ExclusivePermit, reason: String) = admission.withLock {
        require(reason.isNotBlank()) { "Uncertain state needs a reason" }
        if (exclusiveOwner !== owner || mutableState.value.phase != MaintenancePhase.EXCLUSIVE) {
            throw InvalidOperationPermitException()
        }
        mutableState.value = mutableState.value.copy(phase = MaintenancePhase.BLOCKED, blockedReason = reason)
    }

    private suspend fun finishExclusive(owner: ExclusivePermit) = admission.withLock {
        if (exclusiveOwner !== owner) return@withLock
        exclusiveOwner = null
        val current = mutableState.value
        when (current.phase) {
            // Timeout/cancellation before EXCLUSIVE: no resource effect was allowed. Existing
            // writers may still finish, but their resources and generation were never changed.
            MaintenancePhase.DRAINING -> mutableState.value = current.copy(phase = MaintenancePhase.OPEN)
            // Ending an unchanged exclusive scope invalidates callbacks holding the old epoch.
            MaintenancePhase.EXCLUSIVE -> mutableState.value = current.copy(
                phase = MaintenancePhase.OPEN,
                generation = MaintenanceGeneration(current.generation.number + 1),
            )
            MaintenancePhase.BLOCKED -> Unit
            MaintenancePhase.OPEN -> error("Exclusive owner lost its state")
        }
    }
}
