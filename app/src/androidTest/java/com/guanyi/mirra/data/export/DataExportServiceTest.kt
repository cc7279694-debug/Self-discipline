package com.guanyi.mirra.data.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.backup.*
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.domain.backup.*
import com.guanyi.mirra.domain.maintenance.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataExportServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun jsonExportsAllTablesExactFactsWithoutPathsOrOwnershipAndPreservesSource() = runBlocking {
        val f = Fixture()
        try {
            f.seed()
            val before = f.facts()
            val service = f.service()
            val prepared = service.prepareExport(DataExportFormat.JSON)
            val output = File(f.root, "facts.json")
            service.saveExport(prepared, Uri.fromFile(output))
            val document = Json.parseToJsonElement(output.readText()).jsonObject
            val tables = document.getValue("tables").jsonObject
            assertEquals(12, tables.size)
            assertEquals("=中文,\"引用\"\n第二行", tables.getValue("notes").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)
            assertEquals("note", tables.getValue("image_assets").jsonArray.single().jsonObject.getValue("noteId").jsonPrimitive.content)
            assertEquals("历史风险名称", tables.getValue("session_risk_app_snapshots").jsonArray.single().jsonObject.getValue("labelSnapshot").jsonPrimitive.content)
            assertEquals("com.example.risk", tables.getValue("risk_apps").jsonArray.single().jsonObject.getValue("packageName").jsonPrimitive.content)
            for ((name, rows) in tables) for (row in rows.jsonArray) {
                val dbRow = before.getValue(name).single()
                for ((column, value) in row.jsonObject) assertEquals("$name.$column", dbRow[column], value)
            }
            val text = output.readText()
            listOf("localPath", "dndRuleId", "priorDndInterruptionFilter", "dndLifecycle", "activeSlot", "safety-copy", "database.sqlite").forEach { assertFalse("leaked $it", text.contains("\"$it\"")) }
            assertEquals(before, f.facts())
            assertArrayEquals(f.imageBytes, f.image.readBytes())
            assertEquals(MirraThemeId.NIGHT, f.owner.backupPreferences.repository.readStrictSnapshot().portable.themeId.value)
            try { f.backup.inspectBackup(Uri.fromFile(output)); fail("JSON is not a backup") } catch (_: FullBackupException) { }
            service.discardPreparedExport(prepared)
            assertFalse(prepared.file.exists())
        } finally { f.close() }
    }

    @Test fun csvZipHasTwelveTablesAndDescriptionNoImagesAndNeutralizesFormulaText() = runBlocking {
        val f = Fixture()
        try {
            f.seed()
            val before = f.facts()
            val service = f.service()
            val prepared = service.prepareExport(DataExportFormat.CSV_ZIP)
            val output = File(f.root, "facts.zip")
            service.saveExport(prepared, Uri.fromFile(output))
            ZipFile(output).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                AUTHORITATIVE_TABLES.forEach { assertTrue(names.contains("$it.csv")) }
                assertTrue(names.contains("metadata.json"))
                assertFalse(names.any { it.endsWith(".jpg") || it.endsWith(".sqlite") || it.contains("preferences") })
                val notes = zip.getInputStream(zip.getEntry("notes.csv")).reader(Charsets.UTF_8).readText()
                assertTrue(notes.contains("'=中文"))
                assertTrue(notes.contains("\"\"引用\"\""))
                assertTrue(notes.contains("第二行"))
            }
            assertEquals(before, f.facts())
            try { f.backup.inspectBackup(Uri.fromFile(output)); fail("CSV is not a backup") } catch (_: FullBackupException) { }
            service.discardPreparedExport(prepared)
        } finally { f.close() }
    }

    @Test fun emptyExportsAreReadableAndSourceRemainsEmpty() = runBlocking {
        val f = Fixture()
        try {
            val prepared = f.service().prepareExport(DataExportFormat.JSON)
            val tables = Json.parseToJsonElement(prepared.file.readText()).jsonObject.getValue("tables").jsonObject
            assertEquals(12, tables.size)
            assertTrue(tables.values.all { it.jsonArray.isEmpty() })
            assertTrue(f.facts().values.all { it.isEmpty() })
        } finally { f.close() }
    }

    @Test fun lowSpaceSafFailureAndForgedPreparedHandleCannotMutateFacts() = runBlocking {
        val f = Fixture()
        try {
            f.seed()
            val before = f.facts()
            try { f.service { 0 }.prepareExport(DataExportFormat.JSON); fail() }
            catch (failure: FullBackupException) { assertEquals(FullBackupErrorCode.LOW_SPACE, failure.code) }
            val service = f.service()
            val prepared = service.prepareExport(DataExportFormat.JSON)
            try { service.saveExport(prepared, Uri.parse("content://not-a-provider/missing")); fail() }
            catch (failure: FullBackupException) { assertEquals(FullBackupErrorCode.WRITE_OR_READ_FAILED, failure.code) }
            try { service.saveExport(prepared.copy(file = File(f.root, "outside")), Uri.fromFile(File(f.root, "invalid"))); fail() }
            catch (_: FullBackupException) { }
            assertEquals(before, f.facts())
        } finally { f.close() }
    }

    @Test fun activeIntentSessionAndPendingAreRefusedWithoutEndingLearning() = runBlocking {
        val f = Fixture()
        try {
            f.seed()
            val db = f.owner.backupDatabase.openHelper.writableDatabase
            db.execSQL("UPDATE study_intents SET outcome=NULL,convertedAt=NULL,endedAt=NULL,activeSlot=1")
            assertRefused(f)
            db.execSQL("UPDATE study_intents SET outcome='CONVERTED',convertedAt=2,endedAt=2,activeSlot=NULL")
            db.execSQL("UPDATE study_sessions SET activeSlot=1,endedAt=NULL,endType=NULL")
            for (state in listOf("ACTIVE", "PENDING")) {
                db.execSQL("UPDATE session_focus_contexts SET closeoutState=?", arrayOf(state))
                assertRefused(f)
            }
            db.query("SELECT activeSlot,endedAt FROM study_sessions").use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0));assertTrue(it.isNull(1)) }
        } finally { f.close() }
    }

    private suspend fun assertRefused(f: Fixture) {
        try { f.service().prepareExport(DataExportFormat.JSON); fail() }
        catch (failure: FullBackupException) { assertEquals(FullBackupErrorCode.ACTIVE_LEARNING, failure.code) }
    }

    @Test fun largerRealRoomExportStreamsAllRowsWithoutChangingTextOrOriginalIds() = runBlocking {
        val f = Fixture()
        try {
            f.seed()
            val db = f.owner.backupDatabase.openHelper.writableDatabase
            db.beginTransaction()
            try {
                repeat(3000) { i -> db.execSQL("INSERT INTO notes (id,learningItemId,sessionId,semanticType,content,pageNumber,createdAt,updatedAt) VALUES (?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>("large-$i","book","session","UNDERSTANDING","中文,$i\n\"长正文\" "+"字".repeat(256),35,2,60002)) }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            val before = f.facts()
            val prepared = f.service().prepareExport(DataExportFormat.JSON)
            val rows = Json.parseToJsonElement(prepared.file.readText()).jsonObject.getValue("tables").jsonObject.getValue("notes").jsonArray
            assertEquals(3001, rows.size)
            assertEquals(3001, rows.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet().size)
            assertEquals(before, f.facts())
        } finally { f.close() }
    }

    @Test fun cancellationDrainsSnapshotAndRetiredOwnerCannotPrepareOrSave() = runBlocking {
        val f = Fixture()
        try {
            f.seed()
            val before = f.facts()
            val reached = CompletableDeferred<Unit>()
            val fake = object : FullBackupService by f.backup {
                override suspend fun prepareBackup(): PreparedBackup { reached.complete(Unit); awaitCancellation() }
            }
            val service = DefaultDataExportService(context,fake,File(f.root,"cancel-work"))
            val job = launch { service.prepareExport(DataExportFormat.JSON) }
            reached.await(); job.cancelAndJoin()
            assertEquals(before,f.facts())
            assertTrue(File(f.root,"cancel-work").listFiles().orEmpty().isEmpty())
            var current = true
            val guarded = DefaultDataExportService(context,f.backup,File(f.root,"guarded"),requireCurrent={
                if(!current) throw FullBackupOperationException(FullBackupErrorCode.STORAGE_BUSY)
            })
            val prepared = guarded.prepareExport(DataExportFormat.JSON)
            current=false
            try { guarded.saveExport(prepared,Uri.fromFile(File(f.root,"stale.json")));fail() }
            catch(failure: FullBackupException) { assertEquals(FullBackupErrorCode.STORAGE_BUSY,failure.code) }
            try { guarded.prepareExport(DataExportFormat.JSON);fail() }
            catch(failure: FullBackupException) { assertEquals(FullBackupErrorCode.STORAGE_BUSY,failure.code) }
            guarded.discardPreparedExport(prepared)
            assertFalse(prepared.file.exists())
        } finally { f.close() }
    }

    @Test fun tamperedPreparedExportIsRefusedBeforeOpeningDestination() = runBlocking {
        val f = Fixture()
        try {
            f.seed()
            val before=f.facts()
            val service=f.service()
            val prepared=service.prepareExport(DataExportFormat.JSON)
            prepared.file.appendText("tampered")
            val target=File(f.root,"target.json")
            try { service.saveExport(prepared,Uri.fromFile(target));fail() }
            catch (_: FullBackupException) { }
            assertFalse(target.exists())
            assertEquals(before,f.facts())
        } finally { f.close() }
    }

    @Test fun upstreamSnapshotCleanupFailureCannotPublishAnUnreachableExport() = runBlocking {
        val f=Fixture()
        try {
            f.seed()
            val before=f.facts()
            val delegate=f.backup
            val failing=object: FullBackupService by delegate {
                override suspend fun discardPreparedBackup(prepared: PreparedBackup) {
                    delegate.discardPreparedBackup(prepared)
                    throw java.io.IOException("controlled private cleanup failure")
                }
            }
            val root=File(f.root,"cleanup-failure")
            val service=DefaultDataExportService(context,failing,root)
            try { service.prepareExport(DataExportFormat.JSON);fail() }
            catch (_: FullBackupException) { }
            assertTrue("No unreachable prepared handle may survive a failed prepare",root.listFiles().orEmpty().isEmpty())
            assertEquals(before,f.facts())
        } finally { f.close() }
    }

    @Test fun ownerRetirementDuringSnapshotCleanupPreventsPublishingPreparedHandle() = runBlocking {
        val f=Fixture()
        try {
            f.seed()
            var current=true
            val delegate=f.backup
            val retiring=object: FullBackupService by delegate {
                override suspend fun discardPreparedBackup(prepared: PreparedBackup) {
                    delegate.discardPreparedBackup(prepared)
                    current=false
                }
            }
            val root=File(f.root,"late-retirement")
            val service=DefaultDataExportService(context,retiring,root,requireCurrent={
                if(!current) throw FullBackupOperationException(FullBackupErrorCode.STORAGE_BUSY)
            })
            try { service.prepareExport(DataExportFormat.JSON);fail("Retired snapshot must not be delivered") }
            catch(failure: FullBackupException) { assertEquals(FullBackupErrorCode.STORAGE_BUSY,failure.code) }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { f.close() }
    }

    private inner class Fixture {
        val root = File(context.cacheDir, "readable-export-test-${UUID.randomUUID()}").apply { mkdirs() }
        val owner = Owner(RestoreResources(File(root,"live/database.sqlite"), File(root,"live/prefs.preferences_pb"), File(root,"live/images")))
        val image = File(owner.storagePaths.images,"11223344-5566-7788-99aa-bbccddeeff00.jpg")
        var imageBytes = byteArrayOf()
        val host = object : BackupStorageHost {
            override suspend fun requireCurrent(owner: BackupStorageOwner) { check(owner === this@Fixture.owner) }
            override suspend fun setMaintenanceBusy(busy: Boolean) = Unit
            override suspend fun replace(owner: BackupStorageOwner,candidate: RestoreResources,permit: MaintenanceCoordinator.ExclusivePermit): Unit = error("Not restoring")
        }
        val backup = DefaultFullBackupService(context,owner,host,File(root,"backup-work"),availableBytes={Long.MAX_VALUE})
        fun service(space: () -> Long = {Long.MAX_VALUE}) = DefaultDataExportService(context,backup,File(root,"export-${UUID.randomUUID()}"),availableBytes=space)
        suspend fun seed() {
            val db = owner.backupDatabase
            db.learningItemDao().insert(LearningItemEntity("book","测试书",LearningItemStatus.IN_PROGRESS,320,42,1,"拿起书",1,60002,null))
            db.intentDao().insert(StudyIntentEntity("intent","book",1,1,2,2,IntentOutcome.CONVERTED,null))
            db.sessionDao().insert(StudySessionEntity("session","book","intent",2,null,60002,40,42,42,SessionEndType.NORMAL,"原总结",null))
            db.noteDao().insert(NoteEntity("note","book","session",NoteSemanticType.UNDERSTANDING,"=中文,\"引用\"\n第二行",35,2,60002))
            val bitmap = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,88,it) };bitmap.recycle();imageBytes=image.readBytes()
            db.imageAssetDao().insert(ImageAssetEntity("image","note","images/${image.name}","图注",8,8,image.length(),2))
            db.topicDao().insert(TopicEntity("topic","主题",2));db.topicDao().insertCrossRef(NoteTopicCrossRef("note","topic"))
            db.focusDao().upsertRiskApp(RiskAppEntity("com.example.risk","当前名称",1,2))
            db.focusDao().insertContext(SessionFocusContextEntity(sessionId="session",monitoringStatus=MonitoringCoverage.FULL,monitoringLostAt=null,
                priorDndInterruptionFilter=null,dndRuleId=null,closeoutState=FocusCloseoutState.COMPLETED,requestedEndPage=42,closeoutStartedAt=60002,lastHeartbeatAt=60002,createdAt=2,updatedAt=60002))
            db.focusDao().insertRiskSnapshots(listOf(SessionRiskAppSnapshotEntity("session","com.example.risk","历史风险名称")))
            db.focusDao().insertSegment(SessionSegmentEntity("segment","session",SessionSegmentType.FOCUS,2,60002,null,null,null,0,null,null))
            db.focusDao().insertEvent(FocusEventEntity("event","session",FocusEventType.USER_PRESENT,3,null,"segment",null))
            owner.backupPreferences.repository.setThemeId(MirraThemeId.NIGHT)
        }
        fun facts(): Map<String,List<JsonObject>> = AUTHORITATIVE_TABLES.associateWith { table ->
            owner.backupDatabase.openHelper.readableDatabase.query("SELECT * FROM `$table`").use { cursor ->
                buildList { while(cursor.moveToNext()) add(buildJsonObject {
                    for(i in 0 until cursor.columnCount) put(cursor.getColumnName(i),when(cursor.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> JsonNull
                        android.database.Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(i))
                        else -> JsonPrimitive(cursor.getString(i))
                    })
                }) }
            }
        }
        suspend fun close() { owner.closeStorageOwners(); root.deleteRecursively() }
    }
    private class Owner(override val storagePaths: RestoreResources): BackupStorageOwner {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        override val storageGate = StorageMaintenanceGate(leaseScope=scope)
        override val pendingEdits = PendingEditRegistry()
        init { storagePaths.database.parentFile!!.mkdirs();storagePaths.images.mkdirs() }
        override val backupPreferences = ManagedPreferences(storagePaths.preferences)
        override val backupDatabase = Room.databaseBuilder(context,MirraDatabase::class.java,storagePaths.database.absolutePath).build()
        override val startup = CompletableDeferred(Unit)
        override suspend fun assertRuntimeQuiescent() = Unit
        override suspend fun closeStorageOwners() { backupPreferences.close();backupDatabase.close();scope.cancel() }
    }
}
