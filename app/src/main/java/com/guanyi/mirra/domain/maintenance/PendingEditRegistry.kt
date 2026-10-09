package com.guanyi.mirra.domain.maintenance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Covers UI drafts and work queued before Repository admission; storage admission is separate. */
class PendingEditRegistry {
    private enum class Phase { OPEN, FROZEN, RETIRED }
    private val boundary = Any()
    private var phase = Phase.OPEN
    private var activeFreeze: Any? = null
    private val owners = linkedSetOf<Registration>()
    private val editable = MutableStateFlow(true)
    val acceptingEdits: StateFlow<Boolean> = editable.asStateFlow()

    fun register(freezeAndFlush: suspend () -> Unit): Registration = synchronized(boundary) {
        check(phase == Phase.OPEN) { "Pending edits are frozen or retired" }
        Registration(this, freezeAndFlush).also { owners += it }
    }

    /** Freeze admission before dispatching to Main, so no later UI event can change the captured draft. */
    suspend fun freezeAndFlush(): FrozenEdits {
        val identity = Any()
        val registered = synchronized(boundary) {
            check(phase == Phase.OPEN) { "Pending edits are already frozen or retired" }
            phase = Phase.FROZEN
            activeFreeze = identity
            editable.value = false
            owners.toList()
        }
        try {
            withContext(Dispatchers.Main.immediate) {
                registered.forEach { owner ->
                    if (synchronized(boundary) { owner in owners }) owner.flush()
                }
            }
            return FrozenEdits(this, identity)
        } catch (failure: Throwable) {
            release(identity, retire = false)
            throw failure
        }
    }

    private fun edit(owner: Registration, block: () -> Unit): Boolean = synchronized(boundary) {
        if (phase != Phase.OPEN || owner !in owners) return@synchronized false
        block()
        true
    }

    private fun release(identity: Any, retire: Boolean) = synchronized(boundary) {
        if (phase != Phase.FROZEN || activeFreeze !== identity) return@synchronized
        activeFreeze = null
        phase = if (retire) Phase.RETIRED else Phase.OPEN
        editable.value = !retire
        if (retire) owners.clear()
    }

    class Registration internal constructor(
        private val registry: PendingEditRegistry,
        internal val flush: suspend () -> Unit,
    ) : AutoCloseable {
        /** The admission check and synchronous state/queue mutation share one boundary with freezing. */
        fun edit(block: () -> Unit): Boolean = registry.edit(this, block)
        override fun close() { synchronized(registry.boundary) { registry.owners.remove(this) } }
    }

    class FrozenEdits internal constructor(private val registry: PendingEditRegistry, private val identity: Any) {
        fun release() { registry.release(identity, retire = false) }
        fun retire() { registry.release(identity, retire = true) }
    }
}
