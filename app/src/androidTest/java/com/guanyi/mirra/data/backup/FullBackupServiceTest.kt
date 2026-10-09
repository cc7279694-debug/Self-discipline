package com.guanyi.mirra.data.backup

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.domain.maintenance.*
import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.domain.backup.FullBackupOperationException
import com.guanyi.mirra.domain.backup.FullBackupErrorCode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
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
        fun service(space: () -> Long = { Long.MAX_VALUE }) = DefaultFullBackupService(context, owner, host, File(root, "work-${UUID.randomUUID()}"), availableBytes = space)
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
