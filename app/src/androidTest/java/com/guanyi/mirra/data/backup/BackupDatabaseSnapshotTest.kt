package com.guanyi.mirra.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.preferences.*
import com.guanyi.mirra.navigation.TopLevelDestination
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupDatabaseSnapshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var source: MirraDatabase
    private lateinit var root: File
    private lateinit var snapshots: BackupDatabaseSnapshot
    private val prefs = PortableAppPreferencesSnapshot(
        PreferenceSnapshotValue(TopLevelDestination.Start, false), PreferenceSnapshotValue(MirraThemeId.BLUE, false),
        PreferenceSnapshotValue(false, false), PreferenceSnapshotValue(false, false),
    )
    @Before fun setup() {
        source = Room.inMemoryDatabaseBuilder(context, MirraDatabase::class.java).build()
        root = File(context.cacheDir, "backup-test-${UUID.randomUUID()}").apply { mkdir() }
        File(root, "images").mkdir()
        snapshots = BackupDatabaseSnapshot(context)
    }
    @After fun cleanup() { source.close(); root.deleteRecursively() }

    @Test fun roundTripCopiesTwelveTablesAndOriginalJpegAndRebuildsChineseSearch() = runTest {
        seedAllTables()
        val image = File(root, "images/11223344-5566-7788-99aa-bbccddeeff00.jpg")
        val before = image.readBytes()
        val snapshot = snapshots.capture(source, File(root, "images"), File(root, "snapshot"))
        assertEquals(12, snapshot.tableCounts.size)
        assertTrue(snapshot.tableCounts.values.all { it == 1L })
        assertArrayEquals(before, File(snapshot.root, "images/${image.name}").readBytes())
        val archive = File(root, "backup.zip")
        BackupArchive().create(snapshot, archive, prefs, 42)
        val validated = BackupArchive().validateAndExtract(archive, File(root, "unpacked"))
        val clean = snapshots.validateAndReconstruct(validated, File(root, "clean"))
        SQLiteDatabase.openDatabase(clean.databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT content FROM notes", null).use { row -> assertTrue(row.moveToFirst()); assertEquals("阅读中文笔记", row.getString(0)) }
            db.rawQuery("SELECT COUNT(*) FROM search_fts WHERE search_fts MATCH '阅读'", null).use { row -> row.moveToFirst(); assertTrue(row.getLong(0) > 0) }
            db.rawQuery("SELECT dndRuleId, priorDndInterruptionFilter, dndLifecycle, dndAccessAtStart FROM session_focus_contexts", null).use { row ->
                row.moveToFirst(); assertTrue(row.isNull(0)); assertTrue(row.isNull(1)); assertEquals("NOT_APPLIED", row.getString(2)); assertEquals(1, row.getInt(3))
            }
        }
        assertFalse(File(clean.databaseFile.path + "-wal").exists())
        assertEquals(DndLifecycle.RELEASED, source.focusDao().getContext("session")?.dndLifecycle)
    }

    @Test fun activeIntentIsRejectedWithoutChangingItsState() = runTest {
        seedItem()
        source.intentDao().insert(StudyIntentEntity("intent", "item", 1, null, null, null, null, 1))
        rejected { snapshots.capture(source, File(root, "images"), File(root, "snapshot")) }
        assertEquals(1, source.intentDao().get("intent")?.activeSlot)
    }

    @Test fun missingReferencedImageRejectsSnapshot() = runTest {
        seedAllTables()
        File(root, "images/11223344-5566-7788-99aa-bbccddeeff00.jpg").delete()
        rejected { snapshots.capture(source, File(root, "images"), File(root, "snapshot")) }
        assertEquals(1, source.imageAssetDao().listAll().size)
    }

    @Test fun unresolvedDndOwnershipIsRejectedRatherThanSilentlyErased() = runTest {
        seedAllTables()
        source.openHelper.writableDatabase.execSQL("UPDATE session_focus_contexts SET dndLifecycle='RELEASE_FAILED'")
        rejected { snapshots.capture(source, File(root, "images"), File(root, "snapshot")) }
        assertEquals(DndLifecycle.RELEASE_FAILED, source.focusDao().getContext("session")?.dndLifecycle)
    }

    @Test fun foreignTriggerCannotBePublishedEvenWithMatchingManifestHashes() = runTest {
        seedAllTables()
        val snapshot = snapshots.capture(source, File(root, "images"), File(root, "snapshot"))
        SQLiteDatabase.openDatabase(snapshot.databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER malicious AFTER INSERT ON notes BEGIN DELETE FROM learning_items; END")
        }
        val archive = File(root, "attack.zip")
        BackupArchive().create(snapshot, archive, prefs, 42)
        val parsed = BackupArchive().validateAndExtract(archive, File(root, "parsed"))
        rejected { snapshots.validateAndReconstruct(parsed, File(root, "clean")) }
        assertEquals("安全学习", source.learningItemDao().get("item")?.name)
    }

    @Test fun foreignEnumAndCrossSessionSoftReferenceAreRejected() = runTest {
        seedAllTables()
        source.openHelper.writableDatabase.execSQL("UPDATE focus_events SET segmentId='nonexistent'")
        rejected { snapshots.capture(source, File(root, "images"), File(root, "snapshot")) }
    }

    @Test fun legacyAbnormalSessionWithoutContextAndZeroProgressArePreserved() = runTest {
        seedItem()
        source.intentDao().insert(StudyIntentEntity("intent", "item", 1, 2, 3, 3, IntentOutcome.CONVERTED, null))
        source.sessionDao().insert(StudySessionEntity("session", "item", "intent", 3, null, 4, 1, 1, 1, SessionEndType.ABNORMAL, "历史", null))
        val snapshot = snapshots.capture(source, File(root, "images"), File(root, "snapshot"))
        SQLiteDatabase.openDatabase(snapshot.databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT endPage,endType FROM study_sessions", null).use { row -> row.moveToFirst(); assertEquals(1, row.getInt(0)); assertEquals("ABNORMAL", row.getString(1)) }
        }
    }

    @Test fun pendingCloseoutAndActiveSessionAreBothRejected() = runTest {
        seedAllTables()
        source.openHelper.writableDatabase.execSQL("UPDATE session_focus_contexts SET closeoutState='PENDING'")
        rejected { snapshots.assertBackupEligible(source) }
        source.openHelper.writableDatabase.execSQL("UPDATE session_focus_contexts SET closeoutState='COMPLETED'")
        source.openHelper.writableDatabase.execSQL("UPDATE study_sessions SET endedAt=NULL,endType=NULL,endPage=NULL,activeSlot=1")
        rejected { snapshots.assertBackupEligible(source) }
    }

    @Test fun corruptedJpegAndIncorrectMetadataAreRejectedWithoutDeletingFacts() = runTest {
        seedAllTables()
        source.openHelper.writableDatabase.execSQL("UPDATE image_assets SET width=5")
        rejected { snapshots.capture(source, File(root, "images"), File(root, "wrong-size")) }
        source.openHelper.writableDatabase.execSQL("UPDATE image_assets SET width=4")
        File(root, "images/11223344-5566-7788-99aa-bbccddeeff00.jpg").writeText("invalid JPEG")
        source.openHelper.writableDatabase.execSQL("UPDATE image_assets SET fileSize=12")
        rejected { snapshots.capture(source, File(root, "images"), File(root, "corrupt")) }
        assertEquals(1, source.imageAssetDao().listAll().size)
    }

    @Test fun pageZeroAndUnknownEnumsCannotCrossBackupBoundary() = runTest {
        seedAllTables()
        source.openHelper.writableDatabase.execSQL("UPDATE study_sessions SET startPage=0")
        rejected { snapshots.assertBackupEligible(source) }
        source.openHelper.writableDatabase.execSQL("UPDATE study_sessions SET startPage=1")
        source.openHelper.writableDatabase.execSQL("UPDATE notes SET semanticType='UNKNOWN'")
        rejected { snapshots.assertBackupEligible(source) }
    }

    @Test fun deletedZeroLengthCloseoutSegmentDoesNotEraseItsBoundaryEvent() = runTest {
        seedAllTables()
        // Frozen closeout deletes a zero-length trailing segment, while the same-time event remains.
        source.openHelper.writableDatabase.execSQL("DELETE FROM session_segments")
        source.openHelper.writableDatabase.execSQL("UPDATE focus_events SET occurredAt=4")
        val snapshot = snapshots.capture(source, File(root, "images"), File(root, "snapshot"))
        SQLiteDatabase.openDatabase(snapshot.databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT segmentId,occurredAt FROM focus_events", null).use { row -> row.moveToFirst(); assertEquals("segment", row.getString(0)); assertEquals(4L, row.getLong(1)) }
        }
    }

    @Test fun committedWalRowsAreIncludedWhileSourceDatabaseStaysOpen() = runTest {
        source.close()
        val live = File(root, "live.sqlite")
        source = Room.databaseBuilder(context, MirraDatabase::class.java, live.absolutePath)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        seedAllTables()
        assertTrue(File(live.path + "-wal").length() > 0)
        val snapshot = snapshots.capture(source, File(root, "images"), File(root, "snapshot"))
        assertEquals(1L, snapshot.tableCounts.getValue("notes"))
        assertEquals("阅读中文笔记", source.noteDao().get("note")?.content)
        SQLiteDatabase.openDatabase(snapshot.databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT content FROM notes", null).use { row -> row.moveToFirst(); assertEquals("阅读中文笔记", row.getString(0)) }
        }
    }

    @Test fun oversizedRowTextIsRejectedBeforeCursorMaterialization() = runTest {
        seedAllTables()
        source.openHelper.writableDatabase.execSQL("UPDATE notes SET content=?", arrayOf("a".repeat(1_048_577)))
        rejected { snapshots.assertBackupEligible(source) }
        source.openHelper.readableDatabase.query("SELECT LENGTH(content) FROM notes").use { row ->
            assertTrue(row.moveToFirst()); assertEquals(1_048_577L, row.getLong(0))
        }
    }

    @Test fun unicodeCompatibilityExpansionCannotBypassStagedSearchBudget() = runTest {
        seedAllTables()
        val original = "\uFDFA".repeat(60_000)
        source.openHelper.writableDatabase.execSQL("UPDATE notes SET content=?", arrayOf(original))
        rejected { snapshots.capture(source, File(root, "images"), File(root, "snapshot")) }
        assertEquals(original, source.noteDao().get("note")?.content)
    }

    private suspend fun seedItem() = source.learningItemDao().insert(LearningItemEntity("item", "安全学习", LearningItemStatus.IN_PROGRESS, 20, 2, 1, "翻开书", 1, 4, null))
    private suspend fun seedAllTables() {
        seedItem()
        source.intentDao().insert(StudyIntentEntity("intent", "item", 1, 2, 3, 3, IntentOutcome.CONVERTED, null))
        source.sessionDao().insert(StudySessionEntity("session", "item", "intent", 3, null, 4, 1, 2, 2, SessionEndType.NORMAL, "结束中文", null))
        source.noteDao().insert(NoteEntity("note", "item", "session", NoteSemanticType.UNDERSTANDING, "阅读中文笔记", 2, 3, 4))
        val image = File(root, "images/11223344-5566-7788-99aa-bbccddeeff00.jpg")
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { bitmap -> image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }; bitmap.recycle() }
        source.imageAssetDao().insert(ImageAssetEntity("image", "note", "images/${image.name}", "中文图片", 4, 4, image.length(), 3))
        source.topicDao().insert(TopicEntity("topic", "主题", 3))
        source.topicDao().insertCrossRef(NoteTopicCrossRef("note", "topic"))
        source.focusDao().upsertRiskApp(RiskAppEntity("com.example.risk", "风险", 1, 2))
        source.focusDao().insertContext(SessionFocusContextEntity(sessionId = "session", dndAccessAtStart = true, monitoringStatus = MonitoringCoverage.PARTIAL, monitoringLostAt = 4, priorDndInterruptionFilter = 1, dndRuleId = "old-rule", dndLifecycle = DndLifecycle.RELEASED, closeoutState = FocusCloseoutState.COMPLETED, requestedEndPage = 2, closeoutStartedAt = 4, lastHeartbeatAt = 3, createdAt = 3, updatedAt = 4))
        source.focusDao().insertRiskSnapshots(listOf(SessionRiskAppSnapshotEntity("session", "com.example.risk", "历史风险")))
        source.focusDao().insertSegment(SessionSegmentEntity("segment", "session", SessionSegmentType.FOCUS, 3, 4, null, null, null, 0, null, null))
        source.focusDao().insertEvent(FocusEventEntity("event", "session", FocusEventType.USER_PRESENT, 3, null, "segment", null))
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Invalid backup must fail") } catch (_: BackupValidationException) { }
    }
}
