package com.guanyi.mirra.data.repository

import androidx.room.withTransaction
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.DndLifecycle
import com.guanyi.mirra.domain.DndRecord
import com.guanyi.mirra.domain.DndStateStore

class RoomDndStateStore(private val database: MirraDatabase) : DndStateStore {
    private val focus = database.focusDao()
    private val sessions = database.sessionDao()

    override suspend fun get(sessionId: String): DndRecord? = database.withTransaction {
        val context = focus.getContext(sessionId) ?: return@withTransaction null
        val session = sessions.get(sessionId) ?: return@withTransaction null
        DndRecord(sessionId, isLearningFactWritable(session, context),
            context.dndLifecycle, context.priorDndInterruptionFilter, context.dndRuleId)
    }

    override suspend fun prepare(sessionId: String, priorFilter: Int?, ruleId: String?) = database.withTransaction {
        val session = sessions.get(sessionId)
        val context = focus.getContext(sessionId) ?: error("Missing focus context")
        check(isLearningFactWritable(session, context)) { "Only learning Sessions can apply DND" }
        check(context.dndLifecycle == DndLifecycle.NOT_APPLIED ||
            context.dndLifecycle == DndLifecycle.APPLY_FAILED) { "DND already applied or released" }
        check(focus.prepareDnd(sessionId, priorFilter, ruleId, System.currentTimeMillis()) == 1)
    }

    override suspend fun setLifecycle(sessionId: String, lifecycle: DndLifecycle) = database.withTransaction {
        if (lifecycle == DndLifecycle.ACTIVE) {
            check(isLearningFactWritable(sessions.get(sessionId), focus.getContext(sessionId))) {
                "Logically closed Sessions cannot activate DND"
            }
        }
        check(focus.setDndLifecycle(sessionId, lifecycle, System.currentTimeMillis()) == 1)
    }

    override suspend fun pendingAfterRecovery(): List<DndRecord> = database.withTransaction {
        focus.listDndRecoveryContexts().map { context ->
            DndRecord(context.sessionId, active = false, context.dndLifecycle,
                context.priorDndInterruptionFilter, context.dndRuleId)
        }
    }
}
