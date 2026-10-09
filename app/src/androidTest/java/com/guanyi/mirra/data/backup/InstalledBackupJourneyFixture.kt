package com.guanyi.mirra.data.backup

import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.MirraApplication
import com.guanyi.mirra.StoragePresentation
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.data.preferences.PortableAppPreferencesSnapshot
import com.guanyi.mirra.data.preferences.PreferenceSnapshotValue
import com.guanyi.mirra.domain.DefaultSearchEngine
import com.guanyi.mirra.domain.maintenance.PendingEditRegistry
import com.guanyi.mirra.navigation.TopLevelDestination
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicitly opt-in installed-app fixture for a dedicated API 37 emulator. Ordinary suites skip
 * before obtaining the application or touching files. This does not automate or fake SAF UI.
 *
 * Invoke the same method with mirra4cJourneyDedicatedAvd=true, a unique mirra4cJourneyRun
 * ([A-Za-z0-9_-]{1,64}), and mirra4cJourneyStage in this sequence:
 * prepare -> actual Data Management/CreateDocument export -> mutate -> actual
 * Data Management/OpenDocument inspection and confirmed restore -> assert -> restore_baseline.
 * assert optionally accepts mirra4cJourneyExpected=mutated to prove import cancellation preserved
 * the mutated data; it does not advance the stage. The default is restored.
 * The host must retain its actual SAF/cancellation evidence separately. Do not clear app data,
 * uninstall the target, or let other instrumented tests change its storage between these stages.
 *
 * Only prepare/mutate add or change synthetic prefixed records. Before that, prepare uses the
 * installed production service to save the complete current portable baseline privately.
 * A failed/interrupted stage retains this baseline for explicit restore_baseline recovery.
 * Cleanup restores the original baseline through the same production service and checks every
 * authoritative column, referenced JPEG byte and portable preference value/presence before
 * deleting this run's cache. Device ownership and nonportable preferences are refused up front
 * because portable restoration cannot promise their exact preservation.
 */
@RunWith(AndroidJUnit4::class)
class InstalledBackupJourneyFixture {
    @Test fun installedSafBackupRestoreJourney() {
        val request = request()
        runBlocking(Dispatchers.IO) {
            val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MirraApplication
            val base = File(application.cacheDir.canonicalFile, "installed-backup-journey")
            val root = File(base, request.run)
            check(root.canonicalFile.parentFile == base.canonicalFile) { "Unsafe fixture cache path" }
            when (request.stage) {
                "prepare" -> prepare(application, root, request)
                "mutate" -> mutate(application, root, request)
                "assert" -> assertRestored(application, root, request)
                "restore_baseline" -> restoreBaseline(application, root, base, request)
            }
        }
    }

    private suspend fun prepare(application: MirraApplication, root: File, request: Request) {
        check(!root.exists()) { "Run cache already exists; restore its baseline before using a new run" }
        val owner = selectedOwner(application)
        val baseline = exclusive(application, owner) {
            assertPortableBaseline(owner)
            frame(owner)
        }
        androidDurableFiles().directory(root)
        val marker = Properties().apply {
            setProperty("version", "1")
            setProperty("run", request.run)
            setProperty("stage", "saving_baseline")
            setProperty("prefix", "mirra4c-synthetic-${UUID.randomUUID()}-")
            setProperty("imageName", "${UUID.randomUUID()}.jpg")
            setProperty("baseline.digest", baseline.digest)
            baseline.counts.forEach { (table, count) -> setProperty("baseline.count.$table", count.toString()) }
        }
        writeMarker(root, marker)
        val service = checkNotNull(application.container.fullBackupService)
        val prepared = service.prepareBackup()
        try {
            service.saveBackup(prepared, Uri.fromFile(File(root, BASELINE_FILE)))
        } finally { service.discardPreparedBackup(prepared) }
        check(File(root, BASELINE_FILE).isFile) { "Private baseline was not saved" }
        marker.setProperty("baseline.archiveSha256", hashFile(File(root, BASELINE_FILE)))
        marker.setProperty("stage", "baseline_saved")
        writeMarker(root, marker)

        // Recheck after production preparation; seed only if the complete baseline is unchanged.
        val expected = exclusive(application, owner) {
            assertPortableBaseline(owner)
            assertEquals("Current facts changed before synthetic seeding", baseline.digest, frame(owner).digest)
            seed(owner, marker)
            setControlledPreferences(owner)
            frame(owner)
        }
        AUTHORITATIVE_TABLES.forEach { table ->
            assertEquals("Exactly one synthetic row must be added to $table", baseline.counts.getValue(table) + 1, expected.counts.getValue(table))
            marker.setProperty("expected.count.$table", expected.counts.getValue(table).toString())
        }
        marker.setProperty("expected.digest", expected.digest)
        marker.setProperty("expected.imageSha256", hashFile(imageFile(owner, marker)))
        marker.setProperty("stage", "prepared")
        writeMarker(root, marker)
        report("PREPARED", expected)
    }

    private suspend fun mutate(application: MirraApplication, root: File, request: Request) {
        val marker = readMarker(root, request)
        check(marker.getProperty("stage") == "prepared") { "Mutation requires a completed prepare stage" }
        val owner = selectedOwner(application)
        val mutated = exclusive(application, owner) {
            assertEquals("Expected backup facts changed before mutation", marker.digest("expected"), frame(owner).digest)
            assertSyntheticReferences(owner, marker)
            val noteId = marker.id("note")
            val oldNote = checkNotNull(owner.backupDatabase.noteDao().get(noteId))
            val image = imageFile(owner, marker)
            val previousImageBytes = image.readBytes()
            // Only this run's note and JPEG change; existing records/files are never deleted.
            try {
                owner.backupDatabase.withTransaction {
                    assertEquals(1, owner.backupDatabase.noteDao().updateContent(
                        noteId, MUTATED_TEXT, oldNote.semanticType, 36, oldNote.updatedAt + 1,
                    ))
                    writeJpeg(image, Color.rgb(230, 90, 25))
                    owner.backupDatabase.openHelper.writableDatabase.execSQL(
                        "UPDATE image_assets SET fileSize=? WHERE id=? AND noteId=? AND localPath=?",
                        arrayOf<Any?>(image.length(), marker.id("image"), noteId, "images/${marker.getProperty("imageName")}"),
                    )
                    replaceFixtureSearch(owner, marker, MUTATED_TEXT)
                }
            } catch (failure: Throwable) {
                // Match Room's rolled-back metadata after a normal failure/cancellation.
                try { writeAtomic(image, previousImageBytes) }
                catch (rollbackFailure: Throwable) { failure.addSuppressed(rollbackFailure) }
                throw failure
            }
            owner.backupPreferences.repository.setThemeId(MirraThemeId.NIGHT)
            // Keep both protective side effects disabled throughout this synthetic journey.
            owner.backupPreferences.repository.setDndEnabled(false)
            owner.backupPreferences.repository.setCrossAppInterventionEnabled(false)
            frame(owner)
        }
        assertNotEquals("Synthetic mutation must change the complete business digest", marker.digest("expected"), mutated.digest)
        assertNotEquals("Synthetic JPEG bytes must change", marker.getProperty("expected.imageSha256"), hashFile(imageFile(owner, marker)))
        marker.setProperty("mutated.digest", mutated.digest)
        marker.setProperty("mutated.imageSha256", hashFile(imageFile(owner, marker)))
        marker.setProperty("stage", "mutated")
        writeMarker(root, marker)
        report("MUTATED", mutated)
    }

    private suspend fun assertRestored(application: MirraApplication, root: File, request: Request) {
        val marker = readMarker(root, request)
        check(marker.getProperty("stage") == "mutated") { "Restore assertion requires the completed mutation stage" }
        val cancelledInspection = request.expected == "mutated"
        val expectedName = if (cancelledInspection) "mutated" else "expected"
        val owner = selectedOwner(application)
        val actual = exclusive(application, owner) {
            val frame = frame(owner)
            assertEquals("All IDs, columns, JPEG bytes and portable presence must match exactly", marker.digest(expectedName), frame.digest)
            AUTHORITATIVE_TABLES.forEach { table ->
                assertEquals("Restored row inventory differs for $table", marker.getProperty("expected.count.$table").toLong(), frame.counts.getValue(table))
            }
            assertSyntheticReferences(owner, marker)
            val preferences = if (cancelledInspection) controlledPreferences().copy(themeId = PreferenceSnapshotValue(MirraThemeId.NIGHT, true)) else controlledPreferences()
            assertEquals(preferences, owner.backupPreferences.repository.readStrictSnapshot().portable)
            assertEquals("Synthetic JPEG bytes differ", marker.getProperty("$expectedName.imageSha256"), hashFile(imageFile(owner, marker)))
            val query = checkNotNull(DefaultSearchEngine().buildQuery(if (cancelledInspection) MUTATED_SEARCH_TERM else SEARCH_TERM))
            owner.backupDatabase.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM search_fts WHERE entityType=? AND entityId=? AND search_fts MATCH ?",
                arrayOf("NOTE", marker.id("note"), query.expression),
            ).use { rows -> assertTrue(rows.moveToFirst()); assertEquals("FTS must find the fixture note", 1L, rows.getLong(0)) }
            frame
        }
        if (cancelledInspection) report("MUTATED_UNCHANGED_ASSERTED", actual)
        else {
            marker.setProperty("stage", "asserted")
            writeMarker(root, marker)
            report("RESTORED_ASSERTED", actual)
        }
    }

    private suspend fun restoreBaseline(application: MirraApplication, root: File, base: File, request: Request) {
        val marker = readMarker(root, request)
        // baseline_saved also permits recovery after a failed/partially completed seed stage.
        check(marker.getProperty("stage") in setOf("baseline_saved", "prepared", "mutated", "asserted")) {
            "No completely saved baseline is available; leave current data and cache untouched"
        }
        val archive = File(root, BASELINE_FILE)
        assertEquals("Private baseline archive changed", marker.getProperty("baseline.archiveSha256"), hashFile(archive))
        selectedOwner(application)
        val service = checkNotNull(application.container.fullBackupService)
        val candidate = service.inspectBackup(Uri.fromFile(archive))
        try { service.restore(candidate) } finally { service.discardRestoreCandidate(candidate) }
        // Restore selects a new owner; never query or reopen the retired owner.
        val owner = selectedOwner(application)
        val restored = exclusive(application, owner) {
            assertPortableBaseline(owner)
            frame(owner)
        }
        assertEquals("Original installed business data/preferences must be restored completely", marker.digest("baseline"), restored.digest)
        AUTHORITATIVE_TABLES.forEach { table ->
            assertEquals("Original row inventory differs for $table", marker.getProperty("baseline.count.$table").toLong(), restored.counts.getValue(table))
        }
        // Verified exact target: only this test's unique private cache run, never the parent/DB.
        check(root.canonicalFile.parentFile == base.canonicalFile && root.name == request.run)
        androidDurableFiles().deleteOwned(root, base)
        assertFalse("Completed run cache should be removed", root.exists())
        report("BASELINE_RESTORED_CACHE_REMOVED", restored)
    }

    private suspend fun selectedOwner(application: MirraApplication): BackupStorageOwner {
        val state = withTimeout(15_000) {
            application.storageState.first { it.phase == StoragePresentation.OPEN || it.phase == StoragePresentation.BLOCKED }
        }
        check(state.phase == StoragePresentation.OPEN) { "Installed storage is blocked; fixture cannot proceed" }
        val owner = application.container as? BackupStorageOwner ?: error("Installed production storage owner is required")
        owner.startup.await()
        application.requireCurrent(owner)
        return owner
    }

    private suspend fun <T> exclusive(application: MirraApplication, owner: BackupStorageOwner, block: suspend () -> T): T {
        application.requireCurrent(owner)
        application.setMaintenanceBusy(true)
        var edits: PendingEditRegistry.FrozenEdits? = null
        try {
            edits = owner.pendingEdits.freezeAndFlush()
            return owner.storageGate.coordinator.withExclusive(15_000) {
                application.requireCurrent(owner)
                assertNoActive(owner)
                owner.assertRuntimeQuiescent()
                block()
            }
        } finally {
            withContext(NonCancellable) { edits?.release(); application.setMaintenanceBusy(false) }
        }
    }

    private fun assertNoActive(owner: BackupStorageOwner) {
        owner.backupDatabase.openHelper.readableDatabase.query("""
            SELECT (SELECT COUNT(*) FROM study_intents WHERE activeSlot IS NOT NULL OR outcome IS NULL OR endedAt IS NULL) +
                (SELECT COUNT(*) FROM study_sessions WHERE activeSlot IS NOT NULL OR endedAt IS NULL OR endType IS NULL) +
                (SELECT COUNT(*) FROM session_segments WHERE activeSlot IS NOT NULL OR endedAt IS NULL) +
                (SELECT COUNT(*) FROM session_focus_contexts WHERE closeoutState='PENDING')
        """.trimIndent()).use { rows ->
            check(rows.moveToFirst() && rows.getLong(0) == 0L) { "Existing learning is active/pending; no synthetic writes are allowed" }
        }
    }

    private suspend fun assertPortableBaseline(owner: BackupStorageOwner) {
        owner.backupDatabase.openHelper.readableDatabase.query("""
            SELECT COUNT(*) FROM session_focus_contexts
            WHERE dndLifecycle <> 'NOT_APPLIED' OR dndRuleId IS NOT NULL OR priorDndInterruptionFilter IS NOT NULL
        """.trimIndent()).use { rows ->
            check(rows.moveToFirst() && rows.getLong(0) == 0L) { "Original device ownership is not portable; fixture will not alter it" }
        }
        val snapshot = owner.backupPreferences.repository.readStrictSnapshot()
        check(snapshot.original.values.keys.all { it in PORTABLE_KEYS }) { "Nonportable original preferences prevent exact baseline restoration" }
    }

    private suspend fun seed(owner: BackupStorageOwner, marker: Properties) {
        val database = owner.backupDatabase
        val image = imageFile(owner, marker)
        check(!image.exists()) { "Synthetic JPEG path already exists" }
        check(owner.storagePaths.images.exists() || owner.storagePaths.images.mkdirs())
        database.withTransaction {
            syntheticKeys().forEach { (table, key) ->
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table` WHERE `${key.first}`=?", arrayOf(marker.id(key.second))).use { rows ->
                    check(rows.moveToFirst() && rows.getLong(0) == 0L) { "Synthetic identifier collision; no existing record may be replaced" }
                }
            }
            database.learningItemDao().insert(LearningItemEntity(marker.id("book"), SYNTHETIC_LABEL, LearningItemStatus.PAUSED, 320, 42, null, SYNTHETIC_LABEL, 1, 60002, null))
            database.intentDao().insert(StudyIntentEntity(marker.id("intent"), marker.id("book"), 1, 1, 2, 2, IntentOutcome.CONVERTED, null))
            database.sessionDao().insert(StudySessionEntity(marker.id("session"), marker.id("book"), marker.id("intent"), 2, null, 60002, 40, 42, 42, SessionEndType.NORMAL, SYNTHETIC_LABEL, null))
            database.noteDao().insert(NoteEntity(marker.id("note"), marker.id("book"), marker.id("session"), NoteSemanticType.UNDERSTANDING, ORIGINAL_TEXT, 35, 2, 60002))
            writeJpeg(image, Color.rgb(35, 105, 210))
            database.imageAssetDao().insert(ImageAssetEntity(marker.id("image"), marker.id("note"), "images/${image.name}", SYNTHETIC_LABEL, 8, 8, image.length(), 2))
            database.topicDao().insert(TopicEntity(marker.id("topic"), "$SYNTHETIC_LABEL ${marker.getProperty("prefix")}", 2))
            database.topicDao().insertCrossRef(NoteTopicCrossRef(marker.id("note"), marker.id("topic")))
            database.focusDao().upsertRiskApp(RiskAppEntity(marker.id("risk-package"), SYNTHETIC_LABEL, 1, 2))
            database.focusDao().insertContext(SessionFocusContextEntity(
                sessionId = marker.id("session"), monitoringStatus = MonitoringCoverage.NONE,
                monitoringLostAt = 2, priorDndInterruptionFilter = null, dndRuleId = null,
                closeoutState = FocusCloseoutState.COMPLETED, requestedEndPage = 42,
                closeoutStartedAt = 60002, lastHeartbeatAt = 60002, createdAt = 2, updatedAt = 60002,
            ))
            database.focusDao().insertRiskSnapshots(listOf(SessionRiskAppSnapshotEntity(marker.id("session"), marker.id("risk-package"), SYNTHETIC_LABEL)))
            database.focusDao().insertSegment(SessionSegmentEntity(marker.id("segment"), marker.id("session"), SessionSegmentType.UNMONITORED, 2, 60002, null, SYNTHETIC_LABEL, null, 0, null, null))
            database.focusDao().insertEvent(FocusEventEntity(marker.id("event"), marker.id("session"), FocusEventType.USER_PRESENT, 3, null, marker.id("segment"), null))
            replaceFixtureSearch(owner, marker, ORIGINAL_TEXT)
        }
    }

    private suspend fun replaceFixtureSearch(owner: BackupStorageOwner, marker: Properties, content: String) {
        val built = DefaultSearchEngine().buildDocument(listOf(content, SYNTHETIC_LABEL))
        owner.backupDatabase.searchFtsDao().delete("NOTE", marker.id("note"))
        owner.backupDatabase.searchFtsDao().insert(SearchFtsEntity("NOTE", marker.id("note"), built.searchableText, built.normalizedTokens))
    }

    private suspend fun setControlledPreferences(owner: BackupStorageOwner) {
        with(owner.backupPreferences.repository) {
            setDndEnabled(false)
            setCrossAppInterventionEnabled(false)
            setThemeId(MirraThemeId.MONO)
            setLastDestination(TopLevelDestination.Profile)
        }
        assertEquals(controlledPreferences(), owner.backupPreferences.repository.readStrictSnapshot().portable)
    }

    private fun controlledPreferences() = PortableAppPreferencesSnapshot(
        PreferenceSnapshotValue(TopLevelDestination.Profile, true), PreferenceSnapshotValue(MirraThemeId.MONO, true),
        PreferenceSnapshotValue(false, true), PreferenceSnapshotValue(false, true),
    )

    private fun assertSyntheticReferences(owner: BackupStorageOwner, marker: Properties) {
        val keys = syntheticKeys()
        assertEquals(AUTHORITATIVE_TABLES.toSet(), keys.keys)
        keys.forEach { (table, key) ->
            owner.backupDatabase.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table` WHERE `${key.first}`=?", arrayOf(marker.id(key.second))).use { rows ->
                assertTrue(rows.moveToFirst()); assertEquals("Synthetic identifier missing from $table", 1L, rows.getLong(0))
            }
        }
    }

    private fun syntheticKeys() = mapOf(
            "learning_items" to ("id" to "book"), "study_intents" to ("id" to "intent"),
            "study_sessions" to ("id" to "session"), "notes" to ("id" to "note"),
            "image_assets" to ("id" to "image"), "topics" to ("id" to "topic"),
            "note_topic_cross_refs" to ("noteId" to "note"), "risk_apps" to ("packageName" to "risk-package"),
            "session_focus_contexts" to ("sessionId" to "session"), "session_risk_app_snapshots" to ("sessionId" to "session"),
            "session_segments" to ("id" to "segment"), "focus_events" to ("id" to "event"),
        )

    /** Canonical typed, length-framed digest: all rows/all columns, stable ordering, no raw logs. */
    private suspend fun frame(owner: BackupStorageOwner): Frame {
        val hash = CanonicalHash()
        val counts = linkedMapOf<String, Long>()
        owner.backupDatabase.withTransaction {
            val database = owner.backupDatabase.openHelper.readableDatabase
            assertEquals(12, AUTHORITATIVE_TABLES.size)
            for (table in AUTHORITATIVE_TABLES.sorted()) {
                hash.text(table)
                val columns = database.query("PRAGMA table_info(`$table`)").use { rows ->
                    buildList { while (rows.moveToNext()) add(rows.getString(rows.getColumnIndexOrThrow("name"))) }.sorted()
                }
                check(columns.isNotEmpty() && columns.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*")) })
                val names = columns.joinToString(",") { "`$it`" }
                hash.long(columns.size.toLong()); columns.forEach(hash::text)
                var count = 0L
                database.query("SELECT $names FROM `$table` ORDER BY $names").use { rows ->
                    while (rows.moveToNext()) {
                        count++; hash.text("row")
                        columns.indices.forEach { index ->
                            val type = rows.getType(index); hash.long(type.toLong())
                            when (type) {
                                Cursor.FIELD_TYPE_NULL -> Unit
                                Cursor.FIELD_TYPE_INTEGER -> hash.long(rows.getLong(index))
                                Cursor.FIELD_TYPE_FLOAT -> hash.long(java.lang.Double.doubleToLongBits(rows.getDouble(index)))
                                Cursor.FIELD_TYPE_STRING -> hash.text(rows.getString(index))
                                Cursor.FIELD_TYPE_BLOB -> hash.bytes(rows.getBlob(index))
                                else -> error("Unsupported canonical SQLite type")
                            }
                        }
                    }
                }
                counts[table] = count; hash.text("table-end"); hash.long(count)
            }
            database.query("SELECT localPath,fileSize FROM image_assets ORDER BY localPath COLLATE BINARY").use { rows ->
                while (rows.moveToNext()) {
                    val path = rows.getString(0)
                    check(com.guanyi.mirra.data.storage.ManagedImagePath.isValid(path)) { "Unsafe referenced media path" }
                    val image = File(owner.storagePaths.images, path.removePrefix("images/"))
                    check(image.canonicalFile.parentFile == owner.storagePaths.images.canonicalFile && image.isFile)
                    assertEquals("Referenced JPEG size differs", rows.getLong(1), image.length())
                    hash.text(path); hash.file(image)
                }
            }
        }
        val preferences = owner.backupPreferences.repository.readStrictSnapshot()
        // Baseline guard restricts original keys to these four; presence hashes exact absence too.
        check(preferences.original.values.keys.all { it in PORTABLE_KEYS })
        with(preferences.portable) {
            hash.preference("last_destination", lastDestination.present, lastDestination.value.storageValue)
            hash.preference("theme_id", themeId.present, themeId.value.storageValue)
            hash.preference("dnd_enabled", dndEnabled.present, dndEnabled.value.toString())
            hash.preference("cross_app_intervention_enabled", crossAppInterventionEnabled.present, crossAppInterventionEnabled.value.toString())
        }
        return Frame(hash.finish(), counts)
    }

    private fun imageFile(owner: BackupStorageOwner, marker: Properties): File {
        val name = checkNotNull(marker.getProperty("imageName"))
        require(name.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")))
        return File(owner.storagePaths.images, name).also {
            check(it.canonicalFile.parentFile == owner.storagePaths.images.canonicalFile)
        }
    }

    private fun writeJpeg(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            val bytes = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }.toByteArray()
            writeAtomic(file, bytes)
        } finally { bitmap.recycle() }
    }

    private fun readMarker(root: File, request: Request): Properties {
        val file = File(root, MARKER_FILE)
        check(root.isDirectory && root.canonicalFile == root.absoluteFile && file.isFile && file.length() in 1..16_384)
        return Properties().apply {
            file.inputStream().use { load(it) }
            check(getProperty("version") == "1" && getProperty("run") == request.run)
            require(getProperty("prefix").matches(Regex("mirra4c-synthetic-[0-9a-f-]{36}-")))
        }
    }

    private fun writeMarker(root: File, marker: Properties) {
        val bytes = ByteArrayOutputStream().also { marker.store(it, "Synthetic fixture hashes only; no user text") }.toByteArray()
        check(bytes.size <= 16_384)
        writeAtomic(File(root, MARKER_FILE), bytes)
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val parent = checkNotNull(file.parentFile)
        check(file.canonicalFile.parentFile == parent.canonicalFile)
        val temporary = File(parent, ".${file.name}.fixture-${UUID.randomUUID()}.tmp")
        val files = androidDurableFiles()
        try {
            files.write(temporary, bytes)
            files.atomicReplace(temporary, file)
            files.syncDirectory(parent)
        } finally { if (temporary.exists()) files.deleteOwned(temporary, parent) }
    }

    private fun Properties.id(suffix: String): String = checkNotNull(getProperty("prefix")) + suffix
    private fun Properties.digest(name: String): String = checkNotNull(getProperty("$name.digest")).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
    private fun report(stage: String, frame: Frame) {
        println("MIRRA_4C_INSTALLED_JOURNEY stage=$stage syntheticFixture=true authoritativeTables=${frame.counts.size} totalRows=${frame.counts.values.sum()} businessSha256=${frame.digest} rawDataLogged=false")
    }

    private fun request(): Request {
        val arguments = InstrumentationRegistry.getArguments()
        val stage = arguments.getString("mirra4cJourneyStage")
        assumeTrue("Explicit installed SAF journey stage required", stage != null)
        require(stage in setOf("prepare", "mutate", "assert", "restore_baseline"))
        require(arguments.getString("mirra4cJourneyDedicatedAvd") == "true") { "Explicit dedicated emulator confirmation required" }
        require(Build.VERSION.SDK_INT == 37 && (Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("emulator"))) {
            "Fixture is restricted to the dedicated API 37 emulator"
        }
        val run = checkNotNull(arguments.getString("mirra4cJourneyRun")) { "Unique fixture run required" }
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val expected = arguments.getString("mirra4cJourneyExpected") ?: "restored"
        require(expected in setOf("restored", "mutated"))
        return Request(run, checkNotNull(stage), expected)
    }

    private data class Request(val run: String, val stage: String, val expected: String)
    private data class Frame(val digest: String, val counts: Map<String, Long>)
    private class CanonicalHash {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val output = DataOutputStream(DigestOutputStream(object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }, digest))
        fun long(value: Long) { output.writeLong(value) }
        fun bytes(value: ByteArray) { long(value.size.toLong()); output.write(value) }
        fun text(value: String) = bytes(value.toByteArray(Charsets.UTF_8))
        fun preference(name: String, present: Boolean, value: String) { text(name); long(if (present) 1 else 0); text(value) }
        fun file(file: File) {
            val size = file.length(); long(size)
            var actual = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    actual += count; output.write(buffer, 0, count)
                }
            }
            check(actual == size) { "JPEG bytes changed during canonical capture" }
        }
        fun finish(): String = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private companion object {
        const val BASELINE_FILE = "original-baseline.zip"
        const val MARKER_FILE = "journey.properties"
        const val SYNTHETIC_LABEL = "[SYNTHETIC FIXTURE] Installed SAF round-trip; not recorded monitoring"
        const val SEARCH_TERM = "mirrasafjourneyoriginal"
        const val MUTATED_SEARCH_TERM = "mirrasafjourneymutated"
        const val ORIGINAL_TEXT = "$SYNTHETIC_LABEL $SEARCH_TERM 原始合成笔记"
        const val MUTATED_TEXT = "$SYNTHETIC_LABEL $MUTATED_SEARCH_TERM 已修改合成笔记"
        val PORTABLE_KEYS = setOf("last_destination", "theme_id", "dnd_enabled", "cross_app_intervention_enabled")
    }
}
