package com.guanyi.mirra.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.preferences.*
import com.guanyi.mirra.data.wearable.RoomWearableRecordReceiver
import com.guanyi.mirra.data.wearable.wearableCompletedFixture
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import com.guanyi.mirra.domain.wearable.WearableLocalRecord
import com.guanyi.mirra.navigation.TopLevelDestination
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupFiveCompatibilityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "backup-five-${UUID.randomUUID()}").apply { mkdir() }
    private val prefs = PortableAppPreferencesSnapshot(
        PreferenceSnapshotValue(TopLevelDestination.Start, false), PreferenceSnapshotValue(MirraThemeId.BLUE, false),
        PreferenceSnapshotValue(false, false), PreferenceSnapshotValue(false, false))
    @After fun close() { root.deleteRecursively() }

    @Test fun fullVersionTwoRoundTripPreservesIndependentOriginalBytesAndNullablePage() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, MirraDatabase::class.java).build()
        try {
            val original = WearableLocalRecord.parseCanonical(wearableCompletedFixture())
            val receiver = RoomWearableRecordReceiver(database, StorageMaintenanceGate(leaseScope = backgroundScope), CompletableDeferred(Unit), { 123L })
            receiver.persist(original.canonical, original.digest)
            val images = File(root, "images").apply { mkdir() }
            val snapshot = BackupDatabaseSnapshot(context).capture(database, images, File(root, "snapshot"))
            assertEquals(1L, snapshot.tableCounts.getValue("wearable_completed_records"))
            val archive = File(root, "full.zip")
            BackupArchive().create(snapshot, archive, prefs, 1L)
            val validated = BackupArchive().validateAndExtract(archive, File(root, "extract"))
            val reconstructed = BackupDatabaseSnapshot(context).validateAndReconstruct(validated, File(root, "clean"))
            SQLiteDatabase.openDatabase(reconstructed.databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { clean ->
                clean.rawQuery("SELECT installationId,sessionId,canonical,digest,receivedAt FROM wearable_completed_records", null).use { row ->
                    assertTrue(row.moveToFirst()); assertEquals(original.installationId, row.getString(0)); assertEquals(original.sessionId, row.getString(1))
                    assertEquals(original.canonical, row.getString(2)); assertEquals(original.digest, row.getString(3)); assertEquals(123L, row.getLong(4))
                    assertNull(WearableLocalRecord.parseCanonical(row.getString(2)).endPage); assertFalse(row.moveToNext())
                }
                clean.rawQuery("PRAGMA user_version", null).use { row -> row.moveToFirst(); assertEquals(5, row.getInt(0)) }
                clean.rawQuery("SELECT COUNT(*) FROM study_sessions", null).use { row -> row.moveToFirst(); assertEquals(0L, row.getLong(0)) }
            }
        } finally { database.close() }
    }

    @Test fun frozenVersionOneBackupReconstructsInFiveWithNoInventedIndependentRecords() = runTest {
        val legacyRoot = File(root, "legacy-snapshot").apply { mkdir() }
        File(legacyRoot, "images").mkdir()
        val databaseFile = File(legacyRoot, "database.sqlite")
        val legacy = Room.databaseBuilder(context, LegacyBackupDatabaseV4::class.java, databaseFile.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try { legacy.openHelper.writableDatabase.execSQL("INSERT INTO learning_items VALUES ('old-book','旧备份书籍','IN_PROGRESS',100,42,1,'翻到第42页',1,2,NULL)") }
        finally { legacy.close() }
        val counts = AUTHORITATIVE_TABLES.associateWith { if (it == "learning_items") 1L else 0L }
        val archive = File(root, "initial.zip")
        BackupArchive().create(BackupSnapshot(legacyRoot, counts, 0), archive, prefs, 1L)
        val entries = ZipFile(archive).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() } }
        val metadata = Json.parseToJsonElement(entries.getValue("metadata.json").toString(Charsets.UTF_8)).jsonObject
        val oldMetadata = buildJsonObject { metadata.forEach { (key, value) -> put(key, when (key) {
            "backupFormatVersion" -> JsonPrimitive(1)
            "roomSchemaVersion" -> JsonPrimitive(4)
            "tableCounts" -> JsonObject(value.jsonObject.filterKeys { it != "wearable_completed_records" })
            else -> value
        }) } }.toString().toByteArray(Charsets.UTF_8)
        val oldArchive = File(root, "frozen-v1.zip")
        ZipOutputStream(oldArchive.outputStream()).use { zip -> (entries + ("metadata.json" to oldMetadata)).forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
        } }
        val validated = BackupArchive().validateAndExtract(oldArchive, File(root, "legacy-extracted"))
        val rebuilt = BackupDatabaseSnapshot(context).validateAndReconstruct(validated, File(root, "legacy-clean"))
        assertEquals(0L, rebuilt.tableCounts.getValue("wearable_completed_records"))
        SQLiteDatabase.openDatabase(rebuilt.databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT name,currentPage FROM learning_items", null).use { row ->
                assertTrue(row.moveToFirst()); assertEquals("旧备份书籍", row.getString(0)); assertEquals(42, row.getInt(1))
            }
            database.rawQuery("PRAGMA user_version", null).use { row -> row.moveToFirst(); assertEquals(5, row.getInt(0)) }
        }
        verifyClosedDatabase(RestoreResources(databaseFile, File(root, "unused-prefs"), File(legacyRoot, "images")))
        verifyClosedDatabase(RestoreResources(rebuilt.databaseFile, File(root, "unused-prefs"), rebuilt.imagesDirectory))
    }

    @Test fun corruptIndependentRawBlocksBackupAndDoesNotAlterCurrentFacts() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, MirraDatabase::class.java).build()
        try {
            val original = WearableLocalRecord.parseCanonical(wearableCompletedFixture())
            val receiver = RoomWearableRecordReceiver(database, StorageMaintenanceGate(leaseScope = backgroundScope), CompletableDeferred(Unit))
            receiver.persist(original.canonical, original.digest)
            database.openHelper.writableDatabase.execSQL("UPDATE wearable_completed_records SET digest=?", arrayOf("0".repeat(64)))
            try { BackupDatabaseSnapshot(context).capture(database, File(root, "images").apply { mkdir() }, File(root, "rejected")); fail("Corrupt raw receipt was backed up") }
            catch (_: BackupValidationException) { }
            assertEquals(original.canonical, database.wearableCompletedRecordDao().get(original.installationId, original.sessionId)?.canonical)
            assertEquals(1L, database.wearableCompletedRecordDao().count())
        } finally { database.close() }
    }

    @Test fun newStorageOwnerCanReplayOriginalAfterOlderFullBackupRollback() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, MirraDatabase::class.java).build()
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val oldReceiver = RoomWearableRecordReceiver(database, gate, CompletableDeferred(Unit))
        val original = WearableLocalRecord.parseCanonical(wearableCompletedFixture())
        try {
            val snapshot = BackupDatabaseSnapshot(context).capture(database, File(root, "rollback-images").apply { mkdir() }, File(root, "rollback-snapshot"))
            val archive = File(root, "rollback.zip")
            BackupArchive().create(snapshot, archive, prefs, 1L)
            oldReceiver.persist(original.canonical, original.digest)
            assertEquals(1L, oldReceiver.count())
            val selected = BackupDatabaseSnapshot(context).validateAndReconstruct(
                BackupArchive().validateAndExtract(archive, File(root, "rollback-extracted")), File(root, "rollback-selected"))
            gate.coordinator.withExclusive(1_000) { gate.retire(it, "fixture selects an older complete backup") }
            database.close()
            val replacement = Room.databaseBuilder(context, MirraDatabase::class.java, selected.databaseFile.absolutePath).build()
            try {
                val newReceiver = RoomWearableRecordReceiver(replacement, StorageMaintenanceGate(leaseScope = backgroundScope), CompletableDeferred(Unit))
                assertEquals(0L, newReceiver.count())
                assertNull(newReceiver.offer(original.installationId, original.sessionId, original.digest, original.bytes))
                assertFalse(newReceiver.persist(original.canonical, original.digest).alreadyPresent)
                assertTrue(newReceiver.persist(original.canonical, original.digest).alreadyPresent)
                assertEquals(original.canonical, replacement.wearableCompletedRecordDao().get(original.installationId, original.sessionId)?.canonical)
                assertEquals(1L, newReceiver.count())
                try { oldReceiver.persist(original.canonical, original.digest); fail("Old receiver reached the replacement dataset") }
                catch (_: com.guanyi.mirra.domain.wearable.WearableSourceUnavailable) { }
                assertFalse(database.isOpen)
            } finally { replacement.close() }
        } finally { database.close() }
    }
}
