package com.guanyi.mirra.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.system.Os
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.local.model.ReadingRecordSource
import com.guanyi.mirra.data.repository.DefaultReadingRecordRepository
import com.guanyi.mirra.domain.ReadingRecordService
import com.guanyi.mirra.domain.TimelineTrust
import com.guanyi.mirra.domain.maintenance.*
import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.domain.backup.FullBackupOperationException
import com.guanyi.mirra.domain.backup.FullBackupErrorCode
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullBackupServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun realAndroidRoundTripRestoresIdsTextMediaPreferencesAndSearch() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            val service = fixture.service()
            val prepared = service.prepareBackup()
            val exported = File(fixture.root, "export.zip")
            service.saveBackup(prepared, Uri.fromFile(exported))
            val candidate = service.inspectBackup(Uri.fromFile(exported))
            fixture.owner.backupDatabase.openHelper.writableDatabase.execSQL("UPDATE notes SET content='changed'")
            fixture.owner.backupPreferences.repository.setThemeId(MirraThemeId.MONO)
            service.restore(candidate)
            fixture.owner.backupDatabase.openHelper.readableDatabase.query("SELECT id,content FROM notes").use {
                assertTrue(it.moveToFirst()); assertEquals("note", it.getString(0)); assertEquals("原始中文笔记", it.getString(1))
            }
            assertEquals(MirraThemeId.NIGHT, fixture.owner.backupPreferences.repository.readStrictSnapshot().portable.themeId.value)
            assertArrayEquals(fixture.originalImage, File(fixture.owner.storagePaths.images, fixture.imageName).readBytes())
            fixture.owner.backupDatabase.openHelper.readableDatabase.query("SELECT COUNT(*) FROM search_fts WHERE search_fts MATCH '原始'").use { it.moveToFirst(); assertTrue(it.getInt(0)>0) }
        } finally { fixture.close() }
    }

    @Test fun emptyDatabaseRoundTripUsesFormalFormat() = runBlocking {
        val fixture = Fixture()
        try {
            val prepared = fixture.service().prepareBackup()
            val validated = BackupArchive().validateAndExtract(prepared.file, File(fixture.root, "check"))
            assertEquals(12, validated.metadata.tableCounts.size)
            assertTrue(validated.metadata.tableCounts.values.all { it == 0L })
        } finally { fixture.close() }
    }

    @Test fun legacyNormalActiveContextRoundTripPreservesFactsWithoutBackfill() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seedLegacyCloseoutShape()
            val original = fixture.legacyRecords()
            fixture.assertLegacyRecords(original)
            val originalEvents = fixture.owner.backupDatabase.focusDao().listEvents("session")
            val service = fixture.service()
            val prepared = try { service.prepareBackup() } catch (failure: FullBackupOperationException) {
                throw AssertionError("Ended pre-3D NORMAL/FULL/ACTIVE history with null closeout boundaries must remain backupable", failure)
            }
            try {
                val exported = File(fixture.root, "legacy-export.zip")
                service.saveBackup(prepared, Uri.fromFile(exported))
                val unpacked = BackupArchive().validateAndExtract(exported, File(fixture.root, "legacy-snapshot"))
                assertEquals(2L, unpacked.metadata.tableCounts.getValue("study_sessions"))
                assertEquals(2L, unpacked.metadata.tableCounts.getValue("session_focus_contexts"))
                assertEquals(3L, unpacked.metadata.tableCounts.getValue("session_segments"))
                SQLiteDatabase.openDatabase(File(unpacked.root, "database.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("""
                        SELECT s.id,s.endType,s.activeSlot,s.stableStartedAt,c.monitoringStatus,
                            c.closeoutState,c.requestedEndPage,c.closeoutStartedAt,c.usageAccessAtStart
                        FROM study_sessions s JOIN session_focus_contexts c ON c.sessionId=s.id ORDER BY s.id
                    """.trimIndent(), null).use { rows ->
                        for (id in listOf("gap-session", "session")) {
                            assertTrue(rows.moveToNext()); assertEquals(id, rows.getString(0))
                            assertEquals("NORMAL", rows.getString(1)); assertTrue(rows.isNull(2)); assertTrue(rows.isNull(3))
                            assertEquals("FULL", rows.getString(4)); assertEquals("ACTIVE", rows.getString(5))
                            assertTrue(rows.isNull(6)); assertTrue(rows.isNull(7)); assertEquals(1, rows.getInt(8))
                        }
                        assertFalse(rows.moveToNext())
                    }
                }
                val candidate = service.inspectBackup(Uri.fromFile(exported))
                try {
                    assertEquals("Preparation and inspection must not backfill historical facts", original, fixture.legacyRecords())
                    val live = fixture.owner.backupDatabase.openHelper.writableDatabase
                    live.execSQL("UPDATE study_sessions SET generatedSummary='changed'")
                    live.execSQL("UPDATE session_focus_contexts SET pollIntervalMillis=2000,usageAccessAtStart=0")
                    live.execSQL("UPDATE session_segments SET type='UNMONITORED'")
                    live.execSQL("UPDATE notes SET content='changed'")
                    fixture.owner.backupPreferences.repository.setThemeId(MirraThemeId.MONO)
                    service.restore(candidate)
                    val restored = fixture.legacyRecords()
                    assertEquals("Restore must copy the original context, timeline, IDs and boundaries verbatim", original, restored)
                    fixture.assertLegacyRecords(restored)
                    assertEquals(originalEvents, fixture.owner.backupDatabase.focusDao().listEvents("session"))
                    fixture.assertOriginalFacts()
                } finally { service.discardRestoreCandidate(candidate) }
            } finally { service.discardPreparedBackup(prepared) }
        } finally { fixture.close() }
    }

    @Test fun malformedCloseoutBoundariesRemainRejectedWithoutChangingFacts() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            val malformed = listOf(
                Triple("ACTIVE cannot claim an end boundary", listOf("UPDATE session_focus_contexts SET closeoutState='ACTIVE'"), FullBackupErrorCode.OWNED_CLEANUP),
                Triple("End page requires its paired timestamp", listOf("UPDATE session_focus_contexts SET closeoutState='ABORTED',closeoutStartedAt=NULL"), FullBackupErrorCode.OWNED_CLEANUP),
                Triple("Timestamp requires its paired end page", listOf("UPDATE session_focus_contexts SET closeoutState='ABORTED',requestedEndPage=NULL"), FullBackupErrorCode.OWNED_CLEANUP),
                Triple("COMPLETED requires both boundaries", listOf("UPDATE session_focus_contexts SET requestedEndPage=NULL,closeoutStartedAt=NULL"), FullBackupErrorCode.OWNED_CLEANUP),
                Triple("COMPLETED end page must match the Session", listOf("UPDATE session_focus_contexts SET requestedEndPage=41"), FullBackupErrorCode.OWNED_CLEANUP),
                Triple("COMPLETED timestamp must match the Session", listOf("UPDATE session_focus_contexts SET closeoutStartedAt=60001"), FullBackupErrorCode.OWNED_CLEANUP),
                Triple("COMPLETED requires NORMAL end type", listOf("UPDATE study_sessions SET endType='EARLY'"), FullBackupErrorCode.OWNED_CLEANUP),
                Triple("COMPLETED current page must match the end", listOf("UPDATE study_sessions SET currentPage=41"), FullBackupErrorCode.OWNED_CLEANUP),
                Triple("A closed Session cannot hide an active Segment", listOf("UPDATE session_segments SET activeSlot=1,endedAt=NULL"), FullBackupErrorCode.OWNED_CLEANUP),
                Triple("PENDING remains a live closeout", listOf("UPDATE session_focus_contexts SET closeoutState='PENDING'"), FullBackupErrorCode.ACTIVE_LEARNING),
                Triple("A live Session is not legacy ended history", listOf(
                    "UPDATE session_focus_contexts SET closeoutState='ACTIVE',requestedEndPage=NULL,closeoutStartedAt=NULL",
                    "UPDATE study_sessions SET activeSlot=1,endedAt=NULL,endType=NULL,endPage=NULL",
                ), FullBackupErrorCode.ACTIVE_LEARNING),
            )
            val service = fixture.service()
            for ((label, mutations, expectedCode) in malformed) {
                val live = fixture.owner.backupDatabase.openHelper.writableDatabase
                live.execSQL("UPDATE study_sessions SET activeSlot=NULL,endedAt=60002,endType='NORMAL',endPage=42,currentPage=42")
                live.execSQL("UPDATE session_focus_contexts SET closeoutState='COMPLETED',requestedEndPage=42,closeoutStartedAt=60002")
                live.execSQL("UPDATE session_segments SET activeSlot=NULL,endedAt=60002")
                mutations.forEach { live.execSQL(it) }
                val before = checkNotNull(DefaultReadingRecordRepository(fixture.owner.backupDatabase).observe("session").first())
                try { service.prepareBackup(); fail(label) } catch (failure: FullBackupOperationException) {
                    assertEquals(label, expectedCode, failure.code)
                }
                assertEquals(label, before, DefaultReadingRecordRepository(fixture.owner.backupDatabase).observe("session").first())
                fixture.assertOriginalFacts()
            }
        } finally { fixture.close() }
    }

    @Test fun activeIntentRefusesWithoutAutomaticallyClosingIt() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            fixture.owner.backupDatabase.openHelper.writableDatabase.execSQL("UPDATE study_intents SET outcome=NULL, convertedAt=NULL, endedAt=NULL, activeSlot=1")
            try { fixture.service().prepareBackup(); fail() } catch (failure: FullBackupOperationException) {
                assertEquals(FullBackupErrorCode.ACTIVE_LEARNING, failure.code)
            }
            fixture.owner.backupDatabase.openHelper.readableDatabase.query("SELECT activeSlot FROM study_intents").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        } finally { fixture.close() }
    }

    @Test fun lowSpaceAndUnreadableSafNeverChangeCurrentFacts() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            try { fixture.service(space = { 0 }).prepareBackup(); fail() } catch (failure: FullBackupOperationException) {
                assertEquals(FullBackupErrorCode.LOW_SPACE, failure.code)
            }
            try { fixture.service().inspectBackup(Uri.parse("content://not-a-provider/missing")); fail() } catch (failure: FullBackupOperationException) {
                assertEquals(FullBackupErrorCode.WRITE_OR_READ_FAILED, failure.code)
            }
            fixture.owner.backupDatabase.openHelper.readableDatabase.query("SELECT content FROM notes").use { it.moveToFirst(); assertEquals("原始中文笔记", it.getString(0)) }
        } finally { fixture.close() }
    }

    @Test fun activeSessionAndPendingCloseoutAreNeverAutomaticallyEndedForBackup() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            fixture.owner.backupDatabase.openHelper.writableDatabase.execSQL("UPDATE study_sessions SET activeSlot=1, endedAt=NULL, endType=NULL")
            for (state in listOf("ACTIVE", "PENDING")) {
                fixture.owner.backupDatabase.openHelper.writableDatabase.execSQL("UPDATE session_focus_contexts SET closeoutState=?", arrayOf(state))
                try { fixture.service().prepareBackup(); fail() } catch (failure: FullBackupOperationException) {
                    assertEquals(FullBackupErrorCode.ACTIVE_LEARNING, failure.code)
                }
                fixture.owner.backupDatabase.openHelper.readableDatabase.query("SELECT activeSlot,endedAt FROM study_sessions").use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertTrue(it.isNull(1))
                }
            }
        } finally { fixture.close() }
    }

    @Test fun preparingReclaimsPreviousProcessAndLegacyBackupCopiesOnly() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            val work = File(fixture.root, "full-backup-work").apply { mkdir() }
            val legacy = privateCopy(work, "22222222-2222-2222-2222-222222222222")
            val previous = privateCopy(work, "33333333-3333-3333-3333-333333333333_44444444-4444-4444-4444-444444444444")
            val unknown = privateCopy(work, "unknown-operation")
            val ordinary = File(work, "55555555-5555-5555-5555-555555555555").apply { writeText("ordinary file") }
            val journal = File(fixture.root, "full-restore/restore.journal").apply {
                parentFile!!.mkdir(); writeText("protected restore decision")
            }
            val readable = privateCopy(File(fixture.root, "readable-export-work"), "66666666-6666-6666-6666-666666666666")
            val service = fixture.service(workRoot = work)
            val prepared = service.prepareBackup()
            try {
                assertFalse("Legacy private database copies must not survive a new preparation", legacy.exists())
                assertFalse("Previous-process private copies must be reclaimed", previous.exists())
                assertEquals("private database copy", File(unknown, "snapshot/database.sqlite").readText())
                assertEquals("ordinary file", ordinary.readText())
                assertEquals("protected restore decision", journal.readText())
                assertEquals("private database copy", File(readable, "snapshot/database.sqlite").readText())
                assertTrue(prepared.file.isFile)
                fixture.assertOriginalFacts()
            } finally { service.discardPreparedBackup(prepared) }
        } finally { fixture.close() }
    }

    @Test fun inspectingReclaimsPreviousProcessCandidateCopiesWithoutChangingSource() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            val source = fixture.service()
            val prepared = source.prepareBackup()
            try {
                val originalArchive = prepared.file.readBytes()
                val work = File(fixture.root, "inspect-work").apply { mkdir() }
                val stale = privateCopy(work, "77777777-7777-7777-7777-777777777777_88888888-8888-8888-8888-888888888888")
                val legacy = privateCopy(work, "99999999-9999-9999-9999-999999999999")
                val service = fixture.service(workRoot = work)
                val candidate = service.inspectBackup(Uri.fromFile(prepared.file))
                try {
                    assertFalse("Lost previous-process inspection copies must be reclaimed", stale.exists())
                    assertFalse(legacy.exists())
                    assertArrayEquals(originalArchive, prepared.file.readBytes())
                    assertEquals(1, candidate.noteCount)
                    fixture.assertOriginalFacts()
                } finally { service.discardRestoreCandidate(candidate) }
            } finally { source.discardPreparedBackup(prepared) }
        } finally { fixture.close() }
    }

    @Test fun sameProcessServicesAndReplacementGenerationDoNotDeleteCurrentHandles() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            val work = File(fixture.root, "shared-work")
            val first = fixture.service(workRoot = work)
            val prepared = first.prepareBackup()
            val candidate = first.inspectBackup(Uri.fromFile(prepared.file))
            val second = fixture.service(workRoot = work)
            val secondPrepared = second.prepareBackup()
            assertTrue(prepared.file.isFile)
            first.saveBackup(prepared, Uri.fromFile(File(fixture.root, "saved.zip")))
            fixture.owner.backupDatabase.openHelper.writableDatabase.execSQL("UPDATE notes SET content='changed'")
            first.restore(candidate)
            fixture.assertOriginalFacts()
            val replacement = fixture.service(workRoot = work)
            val replacementPrepared = replacement.prepareBackup()
            try {
                assertTrue("A new generation must retain another current-process prepared package", secondPrepared.file.isFile)
                assertTrue(prepared.file.isFile)
                replacement.saveBackup(replacementPrepared, Uri.fromFile(File(fixture.root, "replacement.zip")))
            } finally {
                replacement.discardPreparedBackup(replacementPrepared)
                second.discardPreparedBackup(secondPrepared)
                first.discardPreparedBackup(prepared)
            }
        } finally { fixture.close() }
    }

    @Test fun priorProcessSymbolicDirectoryFailsClosedWithoutDeletingProtectedResources() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            val work = File(fixture.root, "alias-work").apply { mkdir() }
            val protected = privateCopy(fixture.root, "protected")
            val alias = File(work, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa_bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
            Os.symlink(protected.absolutePath, alias.absolutePath)
            try {
                fixture.service(workRoot = work).prepareBackup()
                fail("Unsafe old-process directory must abort preparation")
            } catch (failure: FullBackupOperationException) {
                assertEquals(FullBackupErrorCode.WRITE_OR_READ_FAILED, failure.code)
            }
            assertEquals("private database copy", File(protected, "snapshot/database.sqlite").readText())
            assertEquals(listOf(alias.name), work.listFiles().orEmpty().map { it.name })
            fixture.assertOriginalFacts()
        } finally { fixture.close() }
    }

    @Test fun previousProcessCleanupFailureCannotPublishAnotherBackup() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            val work = File(fixture.root, "failed-cleanup-work").apply { mkdir() }
            val stale = privateCopy(work, "cccccccc-cccc-cccc-cccc-cccccccccccc_dddddddd-dddd-dddd-dddd-dddddddddddd")
            val undeletable = object : File(stale.path) { override fun delete(): Boolean = false }
            val enumerated = object : File(work.path) { override fun listFiles(): Array<File> = arrayOf(undeletable) }
            try {
                fixture.service(workRoot = enumerated).prepareBackup()
                fail("Cleanup failure must prevent publishing a prepared package")
            } catch (failure: FullBackupOperationException) {
                assertEquals(FullBackupErrorCode.WRITE_OR_READ_FAILED, failure.code)
            }
            assertTrue(stale.exists())
            assertEquals(listOf(stale.name), work.listFiles().orEmpty().map { it.name })
            fixture.assertOriginalFacts()
        } finally { fixture.close() }
    }

    @Test fun cancelledPrepareReturnCannotLeaveAnUnreachablePrivateBackup() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            val work = File(fixture.root, "cancelled-prepare-work")
            val service = fixture.service(workRoot = work)
            cancelBeforeDelivery(operation = { service.prepareBackup() }) {
                assertTrue("The full package must exist before cancelling its delivery",
                    work.listFiles().orEmpty().any { File(it, "Mirra-backup.zip").isFile })
            }
            assertTrue("The caller never received a handle, so the service must clean its package",
                work.listFiles().orEmpty().isEmpty())
            fixture.assertOriginalFacts()
        } finally { fixture.close() }
    }

    @Test fun cancelledInspectionReturnCannotLeaveAnUnreachableRestoreCandidate() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            val source = fixture.service()
            val prepared = source.prepareBackup()
            try {
                val work = File(fixture.root, "cancelled-inspection-work")
                val service = fixture.service(workRoot = work)
                cancelBeforeDelivery(operation = { service.inspectBackup(Uri.fromFile(prepared.file)) }) {
                    assertTrue("Verified staging must exist before cancelling candidate delivery",
                        work.listFiles().orEmpty().any { File(it, "staging/portable.preferences_pb").isFile })
                }
                assertTrue("No undisclosed candidate may retain its private input or staging",
                    work.listFiles().orEmpty().isEmpty())
                assertTrue("Cancellation must not delete the separately owned input package", prepared.file.isFile)
                fixture.assertOriginalFacts()
            } finally { source.discardPreparedBackup(prepared) }
        } finally { fixture.close() }
    }

    private fun privateCopy(base: File, name: String): File = File(base, name).also {
        check(File(it, "snapshot").mkdirs())
        File(it, "snapshot/database.sqlite").writeText("private database copy")
    }

    /** Hold only the caller's final return continuation; production Room/files/dispatch stay real. */
    private fun cancelBeforeDelivery(operation: suspend () -> Unit, beforeCancel: () -> Unit) {
        val dispatcher = QueuedReturnDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var delivered = false
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { operation(); delivered = true }
        var continuation: Runnable? = null
        try {
            continuation = dispatcher.next()
            beforeCancel()
            job.cancel()
            val returning = continuation
            continuation = null
            checkNotNull(returning).run()
            while (!job.isCompleted) dispatcher.next().run()
            assertTrue(job.isCancelled)
            assertFalse("A cancelled caller must not receive the prepared value", delivered)
        } finally {
            job.cancel()
            continuation?.run()
            while (!job.isCompleted) dispatcher.next().run()
            scope.cancel()
        }
    }

    private class QueuedReturnDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun next(): Runnable = checkNotNull(queue.poll(30, TimeUnit.SECONDS)) { "Production operation did not reach the queued return boundary" }
    }

    private inner class Fixture {
        val root = File(context.cacheDir, "full-backup-${UUID.randomUUID()}").apply { mkdir() }
        val imageName = "11223344-5566-7788-99aa-bbccddeeff00.jpg"
        var originalImage = byteArrayOf()
        var owner = Owner(RestoreResources(File(root, "live/database.sqlite"), File(root, "live/prefs.preferences_pb"), File(root, "live/images")))
        val host = object : BackupStorageHost {
            override suspend fun requireCurrent(owner: BackupStorageOwner) { check(owner === this@Fixture.owner) }
            override suspend fun setMaintenanceBusy(busy: Boolean) = Unit
            override suspend fun replace(owner: BackupStorageOwner, candidate: RestoreResources, permit: MaintenanceCoordinator.ExclusivePermit) {
                owner.storageGate.retire(permit, "Replacing test resource generation")
                owner.closeStorageOwners()
                val journal = RestoreJournal(File(root, "journal"), owner.storagePaths, androidDurableFiles(), ::verifyClosedDatabase)
                try { journal.apply(journal.prepare(candidate)) } finally {
                    if (!journal.hasPendingRecovery()) this@Fixture.owner = Owner(owner.storagePaths)
                }
            }
        }
        fun service(space: () -> Long = { Long.MAX_VALUE }, workRoot: File = File(root, "work-${UUID.randomUUID()}")) =
            DefaultFullBackupService(context, owner, host, workRoot, availableBytes = space)
        suspend fun assertOriginalFacts() {
            owner.backupDatabase.openHelper.readableDatabase.query("SELECT id,content FROM notes").use {
                assertTrue(it.moveToFirst()); assertEquals("note", it.getString(0)); assertEquals("原始中文笔记", it.getString(1)); assertFalse(it.moveToNext())
            }
            assertArrayEquals(originalImage, File(owner.storagePaths.images, imageName).readBytes())
            assertEquals(MirraThemeId.NIGHT, owner.backupPreferences.repository.readStrictSnapshot().portable.themeId.value)
        }
        suspend fun seedLegacyCloseoutShape() {
            seed()
            val database = owner.backupDatabase
            database.openHelper.writableDatabase.execSQL("UPDATE session_focus_contexts SET closeoutState='ACTIVE',requestedEndPage=NULL,closeoutStartedAt=NULL,pollIntervalMillis=1000,usageAccessAtStart=1")
            database.openHelper.writableDatabase.execSQL("UPDATE session_segments SET endedAt=30002")
            database.focusDao().insertSegment(SessionSegmentEntity("break", "session", SessionSegmentType.BREAK, 30002, 60002, null, null, null, 0, null, null))
            database.intentDao().insert(StudyIntentEntity("gap-intent", "book", 60001, 60001, 60002, 60002, IntentOutcome.CONVERTED, null))
            database.sessionDao().insert(StudySessionEntity("gap-session", "book", "gap-intent", 60002, null, 120002, 42, 44, 44, SessionEndType.NORMAL, "旧历史有缺口", null))
            database.focusDao().insertContext(SessionFocusContextEntity(sessionId="gap-session", pollIntervalMillis=1000,
                usageAccessAtStart=true, monitoringStatus=MonitoringCoverage.FULL, monitoringLostAt=null,
                priorDndInterruptionFilter=null, dndRuleId=null, requestedEndPage=null, closeoutStartedAt=null,
                lastHeartbeatAt=120002, createdAt=60002, updatedAt=120002))
            database.focusDao().insertSegment(SessionSegmentEntity("gap-segment", "gap-session", SessionSegmentType.FOCUS, 60002, 90002, null, null, null, 0, null, null))
        }
        suspend fun legacyRecords(): List<ReadingRecordSource> {
            val repository = DefaultReadingRecordRepository(owner.backupDatabase)
            return listOf("session", "gap-session").map { checkNotNull(repository.observe(it).first()) }
        }
        fun assertLegacyRecords(records: List<ReadingRecordSource>) {
            for (record in records) {
                assertEquals(SessionEndType.NORMAL, record.session.endType)
                assertNull(record.session.activeSlot); assertNull(record.session.stableStartedAt)
                val historical = checkNotNull(record.context)
                assertEquals(MonitoringCoverage.FULL, historical.monitoringStatus)
                assertEquals(FocusCloseoutState.ACTIVE, historical.closeoutState)
                assertNull(historical.requestedEndPage); assertNull(historical.closeoutStartedAt)
                assertEquals(1000L, historical.pollIntervalMillis); assertTrue(historical.usageAccessAtStart)
                assertNull(historical.monitoringLostAt)
            }
            val projector = ReadingRecordService()
            val trusted = projector.project(records[0])
            assertEquals(TimelineTrust.COMPLETE_TRUSTED, trusted.trust)
            assertEquals(30000L, trusted.effectiveFocusMillis); assertEquals(60000L, trusted.totalDurationMillis)
            val gap = projector.project(records[1])
            assertEquals(TimelineTrust.STRUCTURE_INVALID, gap.trust)
            assertNull("Backup must not manufacture trust by filling the missing historical timeline", gap.effectiveFocusMillis)
        }
        suspend fun seed() {
            val database = owner.backupDatabase
            database.learningItemDao().insert(LearningItemEntity("book", "测试书", LearningItemStatus.IN_PROGRESS, 320, 42, 1, "拿起书", 1, 60002, null))
            database.intentDao().insert(StudyIntentEntity("intent", "book", 1, 1, 2, 2, IntentOutcome.CONVERTED, null))
            database.sessionDao().insert(StudySessionEntity("session", "book", "intent", 2, null, 60002, 40, 42, 42, SessionEndType.NORMAL, "原总结", null))
            database.noteDao().insert(NoteEntity("note", "book", "session", NoteSemanticType.UNDERSTANDING, "原始中文笔记", 35, 2, 60002))
            owner.storagePaths.images.mkdirs()
            val image = File(owner.storagePaths.images, imageName)
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }; bitmap.recycle()
            originalImage = image.readBytes()
            database.imageAssetDao().insert(ImageAssetEntity("image", "note", "images/$imageName", "图注", 8, 8, image.length(), 2))
            database.topicDao().insert(TopicEntity("topic", "主题", 2))
            database.topicDao().insertCrossRef(NoteTopicCrossRef("note", "topic"))
            database.focusDao().upsertRiskApp(RiskAppEntity("com.example.risk", "测试风险", 1, 2))
            database.focusDao().insertContext(SessionFocusContextEntity(sessionId="session", monitoringStatus=MonitoringCoverage.FULL,
                monitoringLostAt=null, priorDndInterruptionFilter=null, dndRuleId=null,
                closeoutState=FocusCloseoutState.COMPLETED, requestedEndPage=42, closeoutStartedAt=60002, lastHeartbeatAt=60002, createdAt=2, updatedAt=60002))
            database.focusDao().insertRiskSnapshots(listOf(SessionRiskAppSnapshotEntity("session", "com.example.risk", "历史名称")))
            database.focusDao().insertSegment(SessionSegmentEntity("segment", "session", SessionSegmentType.FOCUS, 2, 60002, null, null, null, 0, null, null))
            database.focusDao().insertEvent(FocusEventEntity("event", "session", FocusEventType.USER_PRESENT, 3, null, "segment", null))
            owner.backupPreferences.repository.setThemeId(MirraThemeId.NIGHT)
        }
        suspend fun close() { owner.closeStorageOwners(); root.deleteRecursively() }
    }
    private class Owner(override val storagePaths: RestoreResources) : BackupStorageOwner {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        override val storageGate = StorageMaintenanceGate(leaseScope = scope)
        override val pendingEdits = PendingEditRegistry()
        init { storagePaths.database.parentFile!!.mkdirs(); storagePaths.images.mkdirs() }
        override val backupPreferences = ManagedPreferences(storagePaths.preferences)
        override val backupDatabase = Room.databaseBuilder(context, MirraDatabase::class.java, storagePaths.database.absolutePath).build()
        override val startup = CompletableDeferred(Unit)
        override suspend fun assertRuntimeQuiescent() = Unit
        override suspend fun closeStorageOwners() {
            backupPreferences.close()
            backupDatabase.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst(); check(it.getInt(0)==0) }
            backupDatabase.close(); scope.cancel()
        }
    }
}
