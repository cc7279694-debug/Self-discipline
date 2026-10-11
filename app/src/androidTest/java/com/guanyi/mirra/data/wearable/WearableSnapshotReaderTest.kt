package com.guanyi.mirra.data.wearable

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import com.guanyi.mirra.domain.wearable.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WearableSnapshotReaderTest {
    private lateinit var db: MirraDatabase
    private val item = LearningItemEntity("book", "中文 📖", LearningItemStatus.IN_PROGRESS, 300, 30, 1,
        "翻到第 30 页，读第一段", 100, 100, null)
    private val intent = StudyIntentEntity("intent", item.id, 100, 150, 200, 200, IntentOutcome.CONVERTED, null)
    private val session = StudySessionEntity("session", item.id, intent.id, 200, null, null, 30, 45, null, null, null, 1)
    private val context = SessionFocusContextEntity(session.id, monitoringStatus = MonitoringCoverage.FULL,
        monitoringLostAt = null, priorDndInterruptionFilter = null, dndRuleId = null,
        requestedEndPage = null, closeoutStartedAt = null, lastHeartbeatAt = 900, createdAt = 200, updatedAt = 900)
    private val segment = SessionSegmentEntity("segment", session.id, SessionSegmentType.FOCUS, 200, null,
        null, null, null, relatedSegmentId = null, activeSlot = 1)
    private val tables = listOf("learning_items", "study_intents", "study_sessions", "notes", "image_assets", "topics", "note_topic_cross_refs",
        "risk_apps", "session_focus_contexts", "session_risk_app_snapshots", "session_segments", "focus_events")

    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java).build() }
    @After fun close() { db.close() }

    @Test fun readAndEveryGoldenOrMalformedRequestLeaveAllTwelveTablesExact() = runTest {
        seed()
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val reader = WearableSnapshotReader(db, gate, CompletableDeferred(Unit), { 1_000 }, { MonitorAvailability.UNAVAILABLE })
        val before = facts()
        val read = reader.read(null)
        assertEquals(45, read.currentPage)
        assertEquals("FULL", read.coverage)
        assertEquals(MonitorAvailability.UNAVAILABLE, read.monitorAvailability)
        val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        assets.list("wearable/protocol-v1")!!.forEach { name ->
            val payload = assets.open("wearable/protocol-v1/$name").use { it.readBytes() }
            try { WearableProtocol.decodeRequest(payload) } catch (_: WearableProtocolException) { }
            reader.read(null)
        }
        listOf("bad JSON", "{\"type\":\"START\"}", "中".repeat(2_000)).forEach {
            assertThrows(WearableProtocolException::class.java) { WearableProtocol.decodeRequest(it.toByteArray(Charsets.UTF_8)) }
        }
        assertEquals(before, facts())
    }
    @Test fun busyAndRetiredOwnerNeverReopenOrReadReplacementDatabase() = runTest {
        seed()
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val reader = WearableSnapshotReader(db, gate, CompletableDeferred(Unit), { 1_000 }, { MonitorAvailability.UNKNOWN })
        gate.coordinator.withExclusive(1_000) { permit ->
            try { reader.read(null); fail("busy read admitted") } catch (_: WearableSourceBusy) { }
            gate.retire(permit, "test replacement")
        }
        db.close()
        try { reader.read(null); fail("retired read admitted") } catch (_: WearableSourceUnavailable) { }
        assertFalse(db.isOpen)
    }
    @Test fun pendingTimeUsesFrozenBoundaryWithoutAdvancingAnyFact() = runTest {
        seed()
        db.focusDao().markCloseoutPending(session.id, 46, 950)
        db.openHelper.writableDatabase.execSQL("UPDATE session_segments SET endedAt=950, activeSlot=NULL WHERE id='segment'")
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val reader = WearableSnapshotReader(db, gate, CompletableDeferred(Unit), { 30_000 }, { MonitorAvailability.AVAILABLE })
        val before = facts()
        assertEquals(750L, reader.read(null).elapsedMs)
        assertEquals(WearablePhase.SAVING, reader.read(null).phase)
        assertEquals(before, facts())
    }
    @Test fun failedStartupAndMissingRelationsNeverBecomeIdle() = runTest {
        seed()
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val failure = CompletableDeferred<Unit>().apply { completeExceptionally(IllegalStateException("bootstrap")) }
        try { WearableSnapshotReader(db, gate, failure, { 1_000 }, { MonitorAvailability.UNKNOWN }).read(null); fail("startup") }
        catch (_: WearableSourceUnavailable) { }
        db.openHelper.writableDatabase.execSQL("DELETE FROM session_segments")
        try { WearableSnapshotReader(db, gate, CompletableDeferred(Unit), { 1_000 }, { MonitorAvailability.UNKNOWN }).read(null); fail("missing segment") }
        catch (_: WearableSourceUnavailable) { }
    }
    @Test fun activePreparationTakesPriorityOverPreviouslyFollowedEndedSession() = runTest {
        seed()
        db.openHelper.writableDatabase.execSQL("UPDATE study_sessions SET endedAt=950, endType='NORMAL', endPage=46, activeSlot=NULL WHERE id='session'")
        db.openHelper.writableDatabase.execSQL("UPDATE session_focus_contexts SET closeoutState='COMPLETED' WHERE sessionId='session'")
        db.openHelper.writableDatabase.execSQL("UPDATE session_segments SET endedAt=950, activeSlot=NULL WHERE id='segment'")
        val preparation = intent.copy(id = "new-intent", createdAt = 970, transitionedAt = null,
            convertedAt = null, endedAt = null, outcome = null, activeSlot = 1)
        db.intentDao().insert(preparation)
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val reader = WearableSnapshotReader(db, gate, CompletableDeferred(Unit), { 1_000 }, { MonitorAvailability.UNKNOWN })
        val before = facts()
        val read = reader.read(session.id)
        assertEquals(WearablePhase.PREPARING, read.phase)
        assertEquals(preparation.id, read.intentId)
        assertNull(read.sessionId)
        assertEquals(30L, read.elapsedMs)
        assertEquals(before, facts())
    }
    private suspend fun seed() {
        db.learningItemDao().insert(item); db.intentDao().insert(intent); db.sessionDao().insert(session)
        db.focusDao().insertContext(context); db.focusDao().insertSegment(segment)
        db.noteDao().insert(NoteEntity("note", item.id, session.id, NoteSemanticType.QUOTE, "private text must not be transmitted", 45, 900, 900))
    }
    private fun facts(): Map<String, List<List<String?>>> = tables.associateWith { table ->
        db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        }
    }
}
