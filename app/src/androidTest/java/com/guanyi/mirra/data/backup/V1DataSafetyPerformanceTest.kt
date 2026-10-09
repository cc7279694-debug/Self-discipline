package com.guanyi.mirra.data.backup

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.export.DefaultDataExportService
import com.guanyi.mirra.data.export.ReadableExportSchema
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.data.search.SearchIndexRebuilder
import com.guanyi.mirra.domain.DefaultSearchEngine
import com.guanyi.mirra.domain.backup.DataExportFormat
import com.guanyi.mirra.domain.maintenance.MaintenanceCoordinator
import com.guanyi.mirra.domain.maintenance.PendingEditRegistry
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Controlled debug/instrumentation measurements, never the installed storage owners.
 * All services and cleanup receive this test's fresh canonical cache root explicitly.
 * Java heap/PSS samples describe the whole instrumented process, include sampling
 * overhead, and are not peak-memory measurements or a production/OEM performance SLA.
 */
@RunWith(AndroidJUnit4::class)
class V1DataSafetyPerformanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun longChineseNotesAndSixteenJpegsRoundTripThroughFormalBackupAndBothExports() = runBlocking {
        withContext(Dispatchers.IO) {
            val fixture = Fixture()
            try {
                measured("seed") { fixture.seed() }
                measured("existingFtsRebuild") {
                    SearchIndexRebuilder(fixture.owner.backupDatabase, DefaultSearchEngine()).rebuild()
                }
                val original = fixture.facts()
                val images = fixture.imageBytes()
                val preferences = fixture.owner.backupPreferences.repository.readStrictSnapshot().portable
                assertEquals(12, original.size)
                assertEquals(NOTE_COUNT.toLong(), original.getValue("notes").count)
                assertEquals(IMAGE_COUNT.toLong(), original.getValue("image_assets").count)
                assertEquals(NOTE_COUNT.toLong(), measured("originalFtsQuery") { fixture.chineseNoteMatches() })
                emit("scale notes=$NOTE_COUNT images=$IMAGE_COUNT imageEdge=$IMAGE_EDGE tables=12 " +
                    "authoritativeRows=${original.values.sumOf { it.count }} " +
                    "noteUtf8Bytes=${fixture.noteUtf8Bytes} jpegBytes=${images.values.sumOf { it.size.toLong() }} " +
                    "syntheticCacheOnly=true installedStorage=false")

                val backup = fixture.backup()
                val prepared = measured("backupPrepareIncludesSnapshotImagesAndFts") { backup.prepareBackup() }
                assertEquals(NOTE_COUNT, prepared.noteCount)
                assertEquals(IMAGE_COUNT, prepared.imageCount)
                val archive = File(fixture.root, "saved-backup.zip")
                measured("backupSaveAndReadback") { backup.saveBackup(prepared, Uri.fromFile(archive)) }
                assertArrayEquals(digest(prepared.file.inputStream()), digest(archive.inputStream()))
                val candidate = measured("backupInspectIncludesValidationReconstructAndFts") {
                    backup.inspectBackup(Uri.fromFile(archive))
                }
                assertEquals(NOTE_COUNT, candidate.noteCount)
                assertEquals(IMAGE_COUNT, candidate.imageCount)
                fixture.assertOriginal(original, images, preferences)

                val export = DefaultDataExportService(context, backup, File(fixture.root, "export-work"),
                    requireCurrent = { fixture.host.requireCurrent(fixture.owner) },
                    availableBytes = { fixture.root.usableSpace })
                for (format in DataExportFormat.entries) {
                    val readable = measured("${format.name}PrepareIncludesFrozenBackupSnapshot") { export.prepareExport(format) }
                    val output = File(fixture.root, "saved-${format.name}.${format.extension}")
                    measured("${format.name}SaveAndReadback") { export.saveExport(readable, Uri.fromFile(output)) }
                    assertArrayEquals(digest(readable.file.inputStream()), digest(output.inputStream()))
                    if (format == DataExportFormat.JSON) fixture.assertJson(output) else fixture.assertCsv(output)
                    emit("artifact format=${format.name} bytes=${output.length()} allWhitelistedFieldsMatched=true")
                    fixture.assertOriginal(original, images, preferences)
                    export.discardPreparedExport(readable)
                    assertFalse(readable.file.exists())
                }
                emit("artifact format=FULL_BACKUP bytes=${archive.length()} referencedJpegs=$IMAGE_COUNT")
                backup.discardPreparedBackup(prepared)
                assertFalse(prepared.file.exists())

                // Legitimate synthetic current data differs, so this verifies actual replacement.
                fixture.owner.backupDatabase.openHelper.writableDatabase.execSQL(
                    "UPDATE notes SET content='受控的恢复前变化' WHERE id='note-0000'")
                fixture.owner.backupPreferences.repository.setThemeId(MirraThemeId.MONO)
                val changedImage = fixture.writeImage(0, colorOffset = 97)
                fixture.owner.backupDatabase.openHelper.writableDatabase.execSQL(
                    "UPDATE image_assets SET fileSize=? WHERE id='image-00'", arrayOf(changedImage.length()))
                fixture.owner.backupDatabase.searchFtsDao().clear()
                assertNotEquals(original, fixture.facts())
                assertFalse(images.getValue(changedImage.name).contentEquals(changedImage.readBytes()))

                measured("backupRestoreIncludesRevalidationFtsAndDurableJournal") { backup.restore(candidate) }
                fixture.assertOriginal(original, images, preferences)
                assertEquals(NOTE_COUNT.toLong(), measured("restoredFtsQuery") { fixture.chineseNoteMatches() })
                assertEquals(IMAGE_COUNT.toLong(), fixture.searchMatches("受控图片说明", "NOTE"))
                assertEquals(1L, fixture.searchMatches("受控中文学习书", "LEARNING_ITEM"))
                assertEquals(1L, fixture.searchMatches("受控中文主题", "TOPIC"))
                assertEquals(1L, fixture.searchMatches("受控中文阅读总结", "SESSION"))
                assertFalse(fixture.journal().hasPendingRecovery())
                emit("roundtrip all12TableFieldsMatched=true jpegBytesMatched=true portableValuePresenceMatched=true " +
                    "chineseFtsMatches=$NOTE_COUNT journalPending=false physicalPowerLoss=false")
            } finally {
                fixture.close()
            }
        }
    }

    private data class Memory(val heapBytes: Long, val pssKb: Long)
    private fun memory(): Memory {
        val runtime = Runtime.getRuntime()
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return Memory(runtime.totalMemory() - runtime.freeMemory(), info.totalPss.toLong())
    }

    private suspend fun <T> measured(stage: String, block: suspend () -> T): T = coroutineScope {
        val before = memory()
        val heapMax = AtomicLong(before.heapBytes)
        val pssMax = AtomicLong(before.pssKb)
        val samples = AtomicLong(1)
        fun updateMax(target: AtomicLong, value: Long) {
            var previous = target.get()
            while (value > previous && !target.compareAndSet(previous, value)) previous = target.get()
        }
        fun sample(value: Memory) {
            updateMax(heapMax, value.heapBytes)
            updateMax(pssMax, value.pssKb)
            samples.incrementAndGet()
        }
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MILLIS)
                sample(memory())
            }
        }
        val started = SystemClock.elapsedRealtime()
        var returned = false
        try {
            block().also { returned = true }
        } finally {
            val elapsed = SystemClock.elapsedRealtime() - started
            withContext(NonCancellable) { sampler.cancelAndJoin() }
            val after = memory()
            sample(after)
            emit("stage=$stage elapsedMs=$elapsed returned=$returned heapBeforeBytes=${before.heapBytes} " +
                "heapAfterBytes=${after.heapBytes} heapDeltaBytes=${after.heapBytes - before.heapBytes} " +
                "sampledHeapMaxBytes=${heapMax.get()} pssBeforeKb=${before.pssKb} pssAfterKb=${after.pssKb} " +
                "sampledPssMaxKb=${pssMax.get()} samples=${samples.get()} sampleIntervalMs=$SAMPLE_INTERVAL_MILLIS " +
                "processWide=true peakMeasured=false")
        }
    }

    private data class TableProof(val count: Long, val sha256: String)

    private inner class Fixture {
        private val cache = context.cacheDir.canonicalFile
        val root = File(cache, "v1-data-safety-performance/${UUID.randomUUID()}").also {
            require(it.canonicalFile == it.absoluteFile && it.canonicalPath.startsWith(cache.path + File.separator))
            check(!it.exists() && it.mkdirs()) { "Never overwrite an existing fixture" }
        }
        var owner = Owner(RestoreResources(File(root, "live/database.sqlite"),
            File(root, "live/preferences.preferences_pb"), File(root, "live/images")))
        var noteUtf8Bytes = 0L
        val host = object : BackupStorageHost {
            override suspend fun requireCurrent(owner: BackupStorageOwner) { check(owner === this@Fixture.owner) }
            override suspend fun setMaintenanceBusy(busy: Boolean) = Unit
            override suspend fun replace(owner: BackupStorageOwner, candidate: RestoreResources,
                permit: MaintenanceCoordinator.ExclusivePermit) {
                owner.storageGate.retire(permit, "Replacing synthetic performance resources")
                owner.closeStorageOwners()
                val journal = journal()
                try { journal.apply(journal.prepare(candidate)) } finally {
                    if (!journal.hasPendingRecovery()) this@Fixture.owner = Owner(owner.storagePaths)
                }
            }
        }
        fun journal() = RestoreJournal(File(root, "journal"), owner.storagePaths,
            androidDurableFiles(), ::verifyClosedDatabase)
        fun backup() = DefaultFullBackupService(context, owner, host, File(root, "backup-work"),
            availableBytes = { root.usableSpace })

        suspend fun seed() {
            val db = owner.backupDatabase
            db.withTransaction {
                db.learningItemDao().insert(LearningItemEntity("book", "受控中文学习书", LearningItemStatus.IN_PROGRESS,
                    320, 42, 1, "拿起书并翻到上次页码", 1, 60002, null))
                db.intentDao().insert(StudyIntentEntity("intent", "book", 1, 1, 2, 2, IntentOutcome.CONVERTED, null))
                db.sessionDao().insert(StudySessionEntity("session", "book", "intent", 2, null, 60002,
                    40, 42, 42, SessionEndType.NORMAL, "受控中文阅读总结", null))
                repeat(NOTE_COUNT) { index ->
                    val content = (if (index % 4 == 0) "=" else "") + "受控中文笔记 $index，\"引用\"\n" +
                        "阅读理解与长期学习记录，需要保留完整中文内容和换行。".repeat(24) + "\n最后一行 $index"
                    noteUtf8Bytes += content.toByteArray(Charsets.UTF_8).size
                    db.noteDao().insert(NoteEntity(noteId(index), "book", "session", NoteSemanticType.UNDERSTANDING,
                        content, 35, 2 + index.toLong(), 60002))
                }
                repeat(IMAGE_COUNT) { index ->
                    val image = writeImage(index)
                    db.imageAssetDao().insert(ImageAssetEntity("image-${index.toString().padStart(2, '0')}",
                        noteId(index), "images/${image.name}", "受控图片说明 $index", IMAGE_EDGE, IMAGE_EDGE,
                        image.length(), 2))
                }
                db.topicDao().insert(TopicEntity("topic", "受控中文主题", 2))
                db.topicDao().insertCrossRef(NoteTopicCrossRef(noteId(0), "topic"))
                db.focusDao().upsertRiskApp(RiskAppEntity("com.example.synthetic", "受控风险应用", 1, 2))
                db.focusDao().insertContext(SessionFocusContextEntity(sessionId = "session",
                    monitoringStatus = MonitoringCoverage.FULL, monitoringLostAt = null,
                    priorDndInterruptionFilter = null, dndRuleId = null, dndLifecycle = DndLifecycle.NOT_APPLIED,
                    closeoutState = FocusCloseoutState.COMPLETED, requestedEndPage = 42,
                    closeoutStartedAt = 60002, lastHeartbeatAt = 60002, createdAt = 2, updatedAt = 60002))
                db.focusDao().insertRiskSnapshots(listOf(SessionRiskAppSnapshotEntity(
                    "session", "com.example.synthetic", "受控历史风险名称")))
                db.focusDao().insertSegment(SessionSegmentEntity("segment", "session", SessionSegmentType.FOCUS,
                    2, 60002, null, null, null, 0, null, null))
                db.focusDao().insertEvent(FocusEventEntity("event", "session", FocusEventType.USER_PRESENT,
                    3, null, "segment", null))
            }
            owner.backupPreferences.repository.setThemeId(MirraThemeId.NIGHT)
        }

        fun writeImage(index: Int, colorOffset: Int = 0): File {
            val file = File(owner.storagePaths.images, imageName(index))
            val pixels = IntArray(IMAGE_EDGE * IMAGE_EDGE) { offset ->
                val x = offset % IMAGE_EDGE
                val y = offset / IMAGE_EDGE
                val red = (x + index * 11 + colorOffset) % 256
                val green = (y + index * 7 + colorOffset) % 256
                val blue = (x * 3 + y * 5 + index * 13 + colorOffset) % 256
                (0xff shl 24) or (red shl 16) or (green shl 8) or blue
            }
            val bitmap = Bitmap.createBitmap(pixels, IMAGE_EDGE, IMAGE_EDGE, Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) } }
            finally { bitmap.recycle() }
            return file
        }

        fun facts(): Map<String, TableProof> = AUTHORITATIVE_TABLES.associateWith { table ->
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            owner.backupDatabase.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY 1,2").use { cursor ->
                while (cursor.moveToNext()) {
                    hash.update(row(cursor).toString().toByteArray(Charsets.UTF_8))
                    hash.update('\n'.code.toByte())
                    count++
                }
            }
            TableProof(count, hash.digest().joinToString("") { "%02x".format(it) })
        }

        fun imageBytes(): Map<String, ByteArray> = checkNotNull(owner.storagePaths.images.listFiles())
            .sortedBy { it.name }.associate { it.name to it.readBytes() }

        suspend fun assertOriginal(expectedFacts: Map<String, TableProof>, images: Map<String, ByteArray>,
            preferences: com.guanyi.mirra.data.preferences.PortableAppPreferencesSnapshot) {
            assertEquals("Every authoritative column and ID must retain its original value", expectedFacts, facts())
            assertEquals(images.keys, imageBytes().keys)
            images.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(owner.storagePaths.images, name).readBytes()) }
            assertEquals(preferences, owner.backupPreferences.repository.readStrictSnapshot().portable)
        }

        fun chineseNoteMatches() = searchMatches("受控中文笔记", "NOTE")

        fun searchMatches(text: String, type: String): Long {
            val query = checkNotNull(DefaultSearchEngine().buildQuery(text)).expression
            return owner.backupDatabase.openHelper.readableDatabase.query(SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM search_fts WHERE search_fts MATCH ? AND entityType=?", arrayOf(query, type))).use {
                check(it.moveToFirst()); it.getLong(0)
            }
        }

        fun assertJson(file: File) {
            val document = Json.parseToJsonElement(file.readText()).jsonObject
            val tables = document.getValue("tables").jsonObject
            assertEquals(AUTHORITATIVE_TABLES.toSet(), tables.keys)
            for (table in ReadableExportSchema.tables) {
                val actual = tables.getValue(table.name).jsonArray
                val columns = table.columns.joinToString(",") { "`${it.name}`" }
                var index = 0
                owner.backupDatabase.openHelper.readableDatabase.query("SELECT $columns FROM `${table.name}` ORDER BY 1").use {
                    while (it.moveToNext()) { assertEquals("${table.name} row $index", row(it), actual[index]); index++ }
                }
                assertEquals(table.name, index, actual.size)
            }
            assertFalse(document.getValue("metadata").jsonObject.getValue("includesImageBytes").jsonPrimitive.boolean)
        }

        fun assertCsv(file: File) {
            ZipFile(file).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                assertEquals(AUTHORITATIVE_TABLES.map { "$it.csv" }.toSet() + setOf("metadata.json", "schema.json", "README.txt"), names)
                for (table in ReadableExportSchema.tables) {
                    val expected = MessageDigest.getInstance("SHA-256")
                    expected.update((table.columns.joinToString(",") { it.name } + "\r\n").toByteArray(Charsets.UTF_8))
                    val columns = table.columns.joinToString(",") { "`${it.name}`" }
                    owner.backupDatabase.openHelper.readableDatabase.query("SELECT $columns FROM `${table.name}` ORDER BY 1").use { cursor ->
                        while (cursor.moveToNext()) {
                            val values = row(cursor)
                            val record = table.columns.joinToString(",") { column ->
                                val value = values.getValue(column.name)
                                when {
                                    value == JsonNull -> ""
                                    !value.jsonPrimitive.isString -> value.jsonPrimitive.content
                                    else -> {
                                        // Fixture strings are ordinary text, or intentionally start '='.
                                        val text = value.jsonPrimitive.content
                                        val safe = if (text.startsWith("=")) "'$text" else text
                                        "\"" + safe.replace("\"", "\"\"") + "\""
                                    }
                                }
                            } + "\r\n"
                            expected.update(record.toByteArray(Charsets.UTF_8))
                        }
                    }
                    assertArrayEquals("Every ${table.name} CSV field must match", expected.digest(),
                        digest(zip.getInputStream(zip.getEntry("${table.name}.csv"))))
                }
            }
        }

        suspend fun close() {
            owner.closeStorageOwners()
            require(root.canonicalFile == root.absoluteFile && root.canonicalPath.startsWith(cache.path + File.separator))
            require(checkNotNull(root.parentFile).name == "v1-data-safety-performance")
            check(root.deleteRecursively()) { "Synthetic fixture cleanup must complete" }
        }
    }

    private inner class Owner(override val storagePaths: RestoreResources) : BackupStorageOwner {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var closed = false
        override val storageGate = StorageMaintenanceGate(leaseScope = scope)
        override val pendingEdits = PendingEditRegistry()
        init { checkNotNull(storagePaths.database.parentFile).mkdirs(); storagePaths.images.mkdirs() }
        override val backupPreferences = ManagedPreferences(storagePaths.preferences)
        override val backupDatabase = Room.databaseBuilder(context, MirraDatabase::class.java, storagePaths.database.absolutePath).build()
        override val startup = CompletableDeferred(Unit)
        override suspend fun assertRuntimeQuiescent() = Unit // This sandbox has no monitor/UI owners.
        override suspend fun closeStorageOwners() {
            if (closed) return
            closed = true
            backupPreferences.close()
            backupDatabase.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
                check(it.moveToFirst() && it.getInt(0) == 0)
            }
            backupDatabase.close()
            scope.cancel()
        }
    }

    private fun row(cursor: Cursor): JsonObject = buildJsonObject {
        for (index in 0 until cursor.columnCount) put(cursor.getColumnName(index), when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> JsonNull
            Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(index))
            Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(index))
            else -> error("Unexpected authoritative field type")
        })
    }

    private fun digest(input: InputStream): ByteArray = input.use { source ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        hash.digest()
    }

    private fun emit(message: String) { Log.i("MirraV1Perf", "MIRRA_V1_PERF $message") }
    private fun noteId(index: Int) = "note-${index.toString().padStart(4, '0')}"
    private fun imageName(index: Int) = UUID.nameUUIDFromBytes("mirra-v1-performance-image-$index".toByteArray()).toString() + ".jpg"

    private companion object {
        const val NOTE_COUNT = 1000
        const val IMAGE_COUNT = 16
        const val IMAGE_EDGE = 256
        const val SAMPLE_INTERVAL_MILLIS = 100L
    }
}
