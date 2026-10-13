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
class WearableCatalogReaderTest {
    private lateinit var db: MirraDatabase
    private val tables = listOf("learning_items", "study_intents", "study_sessions", "notes", "image_assets", "topics",
        "note_topic_cross_refs", "risk_apps", "session_focus_contexts", "session_risk_app_snapshots", "session_segments", "focus_events")
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java).build() }
    @After fun close() { db.close() }
    private fun item(id: String, main: Boolean = false, status: LearningItemStatus = LearningItemStatus.IN_PROGRESS,
        action: String = "翻到当前页，读第一段。") = LearningItemEntity(id, "真实书籍$id", status, 300, 42,
            if (main) 1 else null, action, 1, 1, null)
    private fun facts() = tables.associateWith { table ->
        db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        }
    }
    @Test fun actualRoomProjectionSelectsOnlyMainlineAndChosenInProgressWithoutChangingAnyTable() = runTest {
        listOf(item("main", true), item("other"), item("unselected"), item("paused", status = LearningItemStatus.PAUSED),
            item("completed", status = LearningItemStatus.COMPLETED), item("invalid-action", action = "开始学习")).forEach { db.learningItemDao().insert(it) }
        val reader = WearableCatalogReader(db, StorageMaintenanceGate(leaseScope = backgroundScope), CompletableDeferred(Unit))
        val before = facts()
        assertEquals(setOf("main", "other", "unselected", "invalid-action"), reader.choices().map { it.id }.toSet())
        val catalog = reader.read(listOf("other", "paused", "completed", "invalid-action"))
        assertEquals(listOf("main", "invalid-action", "other"), catalog.books.map { it.id })
        assertNull(catalog.books[1].firstAction)
        WearableCatalogProtocol.encode(catalog)
        assertEquals(before, facts())
    }
    @Test fun busyOrRetiredGenerationCannotReadOldOrReplacementDatabase() = runTest {
        db.learningItemDao().insert(item("main", true))
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val reader = WearableCatalogReader(db, gate, CompletableDeferred(Unit))
        gate.coordinator.withExclusive(1_000) { permit ->
            try { reader.read(emptyList()); fail("busy admitted") } catch (_: WearableSourceBusy) { }
            gate.retire(permit, "catalog test replacement")
        }
        db.close()
        try { reader.choices(); fail("retired admitted") } catch (_: WearableSourceUnavailable) { }
        assertFalse(db.isOpen)
    }
    @Test fun invalidLongTitleRejectsWholeCatalogAndNeverMutatesOriginalBook() = runTest {
        val source = item("main", true).copy(name = "长".repeat(121))
        db.learningItemDao().insert(source)
        val before = facts()
        val reader = WearableCatalogReader(db, StorageMaintenanceGate(leaseScope = backgroundScope), CompletableDeferred(Unit))
        try { reader.read(emptyList()); fail("truncated") } catch (failure: WearableCatalogException) { assertEquals("INVALID_CATALOG", failure.code) }
        assertEquals(source, db.learningItemDao().get("main"))
        assertEquals(before, facts())
    }
    @Test fun unfinishedStartupNeverBecomesAnEmptySuccessfulCatalog() = runTest {
        val reader = WearableCatalogReader(db, StorageMaintenanceGate(leaseScope = backgroundScope), CompletableDeferred())
        try { reader.read(emptyList()); fail("startup incomplete") } catch (_: WearableSourceBusy) { }
    }
}
