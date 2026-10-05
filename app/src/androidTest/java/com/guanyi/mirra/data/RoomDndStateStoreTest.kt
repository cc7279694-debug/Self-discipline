package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.DndLifecycle
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.data.repository.RoomDndStateStore
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomDndStateStoreTest {
    @Test fun dndLifecycleUsesExistingV4FieldsWithoutChangingCoverage() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            MirraDatabase::class.java).build()
        try {
            var now = 1_000L
            val item = DefaultLearningItemRepository(db, clock = { now }).create("书", 100, 10)
            val workflow = DefaultStudyWorkflowRepository(db, RuleBasedSummaryEngine(), IntentExpiryPolicy(), clock = { now })
            val session = workflow.startSession(workflow.createIntent(item.id).id, 10)
            val store = RoomDndStateStore(db)
            store.prepare(session.id, null, "mirra:rule-creation-pending")
            store.prepare(session.id, null, "rule-1")
            store.setLifecycle(session.id, DndLifecycle.ACTIVE)
            assertEquals("rule-1", store.get(session.id)?.ruleId)
            assertEquals(DndLifecycle.ACTIVE, store.get(session.id)?.lifecycle)
            assertEquals("NONE", db.focusDao().getContext(session.id)?.monitoringStatus?.name)

            now = 2_000L
            workflow.beginCloseout(session.id, 10, now)
            workflow.completeCloseout(session.id)
            assertEquals(listOf(session.id), store.pendingAfterRecovery().map { it.sessionId })
            store.setLifecycle(session.id, DndLifecycle.RELEASED)
            assertTrue(store.pendingAfterRecovery().isEmpty())
        } finally { db.close() }
    }

    @Test fun cannotPrepareDndForEndedSession() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            MirraDatabase::class.java).build()
        try {
            var now = 1_000L
            val item = DefaultLearningItemRepository(db, clock = { now }).create("书", 100, 10)
            val workflow = DefaultStudyWorkflowRepository(db, RuleBasedSummaryEngine(), IntentExpiryPolicy(), clock = { now })
            val session = workflow.startSession(workflow.createIntent(item.id).id, 10)
            now = 2_000L
            workflow.beginCloseout(session.id, 10, now)
            workflow.completeCloseout(session.id)
            try { RoomDndStateStore(db).prepare(session.id, 1, null); fail("Expected rejection") }
            catch (_: IllegalStateException) { }
        } finally { db.close() }
    }
}
