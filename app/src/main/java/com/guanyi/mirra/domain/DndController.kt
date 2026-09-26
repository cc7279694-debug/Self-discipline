package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.DndLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DndRecord(
    val sessionId: String,
    val active: Boolean,
    val lifecycle: DndLifecycle = DndLifecycle.NOT_APPLIED,
    val priorFilter: Int? = null,
    val ruleId: String? = null,
)

/** The store never calls Android APIs while holding a Room transaction. */
interface DndStateStore {
    suspend fun get(sessionId: String): DndRecord?
    suspend fun prepare(sessionId: String, priorFilter: Int?, ruleId: String?)
    suspend fun setLifecycle(sessionId: String, lifecycle: DndLifecycle)
    suspend fun pendingAfterRecovery(): List<DndRecord>
}

/** The Android boundary is deliberately independent of monitoring coverage. */
interface DndSystem {
    val apiLevel: Int
    fun hasAccess(): Boolean
    fun findOwnedRule(): String?
    fun createOwnedRule(): String
    fun activateOwnedRule(id: String)
    fun deactivateOwnedRule(id: String)
    fun currentFilter(): Int
    fun applyLegacyPriority()
    fun legacyOwnershipIntact(): Boolean
    fun restoreLegacyFilter(priorFilter: Int)
}

class DndController(private val store: DndStateStore, private val system: DndSystem) {
    private val mutex = Mutex()

    /** Only invoked for a newly committed Session and an explicit, persisted user preference. */
    suspend fun apply(sessionId: String, enabled: Boolean) = mutex.withLock {
        if (!enabled) return@withLock
        val record = store.get(sessionId) ?: return@withLock
        if (!record.active || record.lifecycle == DndLifecycle.ACTIVE ||
            record.lifecycle == DndLifecycle.RELEASED || record.lifecycle == DndLifecycle.RELEASE_PENDING) return@withLock
        var ownRule: String? = null
        var modernActivated = false
        var legacyApplied = false
        var priorForCompensation: Int? = null
        try {
            if (!system.hasAccess()) {
                store.setLifecycle(sessionId, DndLifecycle.APPLY_FAILED)
                return@withLock
            }
            if (system.apiLevel >= 29) {
                ownRule = system.findOwnedRule()
                if (ownRule == null) {
                    // Durable intent precedes the external create: a crash before persisting the ID is discoverable.
                    store.prepare(sessionId, null, RULE_CREATION_PENDING)
                    ownRule = system.createOwnedRule()
                }
                store.prepare(sessionId, null, ownRule)
                system.activateOwnedRule(ownRule)
                modernActivated = true
            } else {
                val prior = system.currentFilter()
                if (prior == LEGACY_PRIORITY) return@withLock // Mirra did not change the system.
                if (prior !in 1..4) error("Unknown prior interruption filter")
                priorForCompensation = prior
                store.prepare(sessionId, prior, null)
                legacyApplied = true
                system.applyLegacyPriority()
            }
            store.setLifecycle(sessionId, DndLifecycle.ACTIVE)
        } catch (cancelled: CancellationException) {
            compensateApply(ownRule.takeIf { modernActivated }, legacyApplied, priorForCompensation)
            throw cancelled
        } catch (_: Exception) {
            compensateApply(ownRule.takeIf { modernActivated }, legacyApplied, priorForCompensation)
            runCatching { store.setLifecycle(sessionId, DndLifecycle.APPLY_FAILED) }
        }
    }

    private fun compensateApply(ownRule: String?, legacyApplied: Boolean, prior: Int?) {
        runCatching {
            if (ownRule != null && system.hasAccess()) system.deactivateOwnedRule(ownRule)
            else if (legacyApplied && prior != null && system.legacyOwnershipIntact()) system.restoreLegacyFilter(prior)
        }
    }

    suspend fun release(sessionId: String) = mutex.withLock {
        val record = store.get(sessionId) ?: return@withLock
        if (record.lifecycle == DndLifecycle.RELEASED ||
            (record.ruleId == null && record.priorFilter == null)) return@withLock
        releaseRecord(record)
    }

    private suspend fun releaseRecord(record: DndRecord) {
        try {
            store.setLifecycle(record.sessionId, DndLifecycle.RELEASE_PENDING)
            if (system.apiLevel >= 29) {
                if (!system.hasAccess()) error("DND permission unavailable")
                val owned = system.findOwnedRule()
                if (owned != null && (owned == record.ruleId || record.ruleId == RULE_CREATION_PENDING)) {
                    system.deactivateOwnedRule(owned)
                }
                // A removed rule has no remaining Mirra effect; a different rule is never touched.
            } else if (record.priorFilter != null && system.legacyOwnershipIntact()) {
                if (!system.hasAccess()) error("DND permission unavailable")
                system.restoreLegacyFilter(record.priorFilter)
            }
            store.setLifecycle(record.sessionId, DndLifecycle.RELEASED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runCatching { store.setLifecycle(record.sessionId, DndLifecycle.RELEASE_FAILED) }
        }
    }

    /** Called only after abnormal Session recovery; never starts monitoring or a Session. */
    suspend fun reconcileAfterRecovery() = mutex.withLock {
        val pending = store.pendingAfterRecovery()
        pending.forEach { record ->
            if (system.apiLevel < 29 && record.priorFilter != null) {
                // A new process cannot prove ownership of a legacy global filter.
                runCatching { store.setLifecycle(record.sessionId, DndLifecycle.RELEASE_FAILED) }
            } else if (record.ruleId != null) releaseRecord(record)
        }
    }

    private companion object {
        const val LEGACY_PRIORITY = 2
        const val RULE_CREATION_PENDING = "mirra:rule-creation-pending"
    }
}
