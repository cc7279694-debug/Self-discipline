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
import com.guanyi.mirra.data.repository.DefaultImageRepository
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultNoteRepository
import com.guanyi.mirra.data.repository.DefaultTopicRepository
import com.guanyi.mirra.data.search.SearchIndexWriter
import com.guanyi.mirra.data.storage.DefaultImageStorageService
import com.guanyi.mirra.data.storage.ManagedImagePath
import com.guanyi.mirra.data.wearable.wearableCompletedFixture
import com.guanyi.mirra.domain.DefaultSearchEngine
import com.guanyi.mirra.domain.maintenance.PendingEditRegistry
import com.guanyi.mirra.domain.wearable.WearableLocalRecord
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in installed-app upgrade fixture, exclusively for a dedicated API37 emulator.
 * Common arguments: mirraV141DedicatedAvd=true, mirraV141Run=[A-Za-z0-9_-]{1,64}.
 * Invoke installedUpgradePreservesOriginalFacts with mirraV141Stage=prepare on the original
 * 1.4.0/code9 APK; the host then actually installs 1.4.1/code10 with install -r, without clearing
 * data or uninstalling, and invokes assert_upgrade, optionally round_trip, then restore_baseline.
 * The test APK may be installed before prepare; it must not replace the original target APK.
 * No stage claims UI, migration4->5, radio, or physical-device evidence.
 *
 * Before any synthetic mutation, a production FullBackupService snapshot is durably saved.
 * Nonportable preferences, device ownership, active learning and unreferenced media are refused.
 * Failure keeps the private baseline and marker; restore_baseline works on either APK version.
 * Hashes cover all columns in all 13 authoritative tables, referenced JPEG bytes, and portable
 * preference values/presence. Cleanup uses the formal restore service and reacquires its new
 * storage owner. Never copy a live database, wipe data, or delete this cache to hide a failure.
 */
@RunWith(AndroidJUnit4::class)
class V141InstalledUpgradeFixture {
    @Test fun installedUpgradePreservesOriginalFacts() {
        val request = request() // Ordinary suites skip before acquiring the app or touching files.
        runBlocking(Dispatchers.IO) {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MirraApplication
            require(app.packageName == "com.guanyi.mirra")
            // The pre-seed baseline must survive cache eviction between APK versions.
            // This distinct owned subtree is outside the production full-restore journal.
            val base = File(app.noBackupFilesDir.canonicalFile, "v141-installed-upgrade")
            val root = File(base, request.run)
            check(root.canonicalFile.parentFile == base.canonicalFile)
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val installed = info.longVersionCode to checkNotNull(info.versionName)
            val allowed = when (request.stage) {
                "prepare" -> setOf(9L to "1.4.0")
                "restore_baseline" -> setOf(9L to "1.4.0", 10L to "1.4.1")
                else -> setOf(10L to "1.4.1")
            }
            require(installed in allowed) { "Stage requires its exact original or upgraded APK version" }
            when (request.stage) {
                "prepare" -> prepare(app, root, request)
                "assert_upgrade" -> {
                    val marker = marker(root, request)
                    check(marker.getProperty("stage") in setOf("prepared", "upgrade_asserted"))
                    verify(app, marker)
                    marker.setProperty("stage", "upgrade_asserted"); saveMarker(root, marker)
                    report("UPGRADE_ASSERTED", 10)
                }
                "round_trip" -> roundTrip(app, root, marker(root, request))
                "restore_baseline" -> restoreBaseline(app, base, root, request)
            }
        }
    }

    private suspend fun prepare(app: MirraApplication, root: File, request: Request) {
        check(!root.exists()) { "Existing run must be recovered, never overwritten" }
        val owner = owner(app)
        val before = exclusive(app, owner) { portableBaseline(owner); frame(owner) }
        androidDurableFiles().directory(root)
        val marker = Properties().apply {
            setProperty("version", "1"); setProperty("run", request.run); setProperty("stage", "saving_baseline")
            setProperty("baseline.digest", before.digest)
            setProperty("prefix", "v141-${UUID.randomUUID()}-")
            before.counts.forEach { (table, count) -> setProperty("baseline.count.$table", count.toString()) }
        }
        saveMarker(root, marker)
        val baseline = File(root, "baseline.zip")
        saveBackup(app, baseline)
        marker.setProperty("baseline.sha256", hash(baseline))
        marker.setProperty("stage", "baseline_saved"); saveMarker(root, marker)
        exclusive(app, owner) {
            portableBaseline(owner)
            check(before.digest == frame(owner).digest) { "Baseline changed before synthetic seeding" }
            seed(app, owner, root, marker)
        }
        // The installed captured receiver owns its own gate/Room transaction, so do not nest it
        // within the exclusive lease. Its returned receipt proves durable commit, not SDK ACK.
        app.requireCurrent(owner)
        val parsed = WearableLocalRecord.parseCanonical(wearableCompletedFixture()
            .replace("install-a", marker.id("wrist")).replace("book-a", marker.id("book")))
        val receiver = checkNotNull(app.container.wearableRecordReceiver)
        val receipt = receiver.persist(parsed.canonical, parsed.digest)
        check(!receipt.alreadyPresent && receipt.installationId == parsed.installationId && receipt.sessionId == parsed.sessionId)
        val expected = exclusive(app, owner) {
            val row = checkNotNull(owner.backupDatabase.wearableCompletedRecordDao().get(parsed.installationId, parsed.sessionId))
            marker.setProperty("wrist.installation", row.installationId); marker.setProperty("wrist.session", row.sessionId)
            marker.setProperty("wrist.digest", row.digest); marker.setProperty("wrist.bytes", row.bytes.toString())
            marker.setProperty("wrist.receivedAt", row.receivedAt.toString())
            assertSearch(owner, marker); frame(owner)
        }
        assertEquals(13, expected.counts.size)
        AUTHORITATIVE_TABLES.forEach { table ->
            assertEquals("One synthetic row per authoritative table", before.counts.getValue(table) + 1, expected.counts.getValue(table))
            marker.setProperty("expected.count.$table", expected.counts.getValue(table).toString())
        }
        marker.setProperty("expected.digest", expected.digest); marker.setProperty("stage", "prepared")
        saveMarker(root, marker)
        report("PREPARED", 9)
    }

    private suspend fun seed(app: MirraApplication, owner: BackupStorageOwner, root: File, marker: Properties) {
        val db = owner.backupDatabase
        val search = SearchIndexWriter(db, DefaultSearchEngine())
        val storage = DefaultImageStorageService(app)
        val time = 60_002L
        val source = File(root, "synthetic-source.jpg")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(35, 105, 210))
            val bytes = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }.toByteArray()
            atomic(source, bytes)
        } finally { bitmap.recycle() }
        db.withTransaction {
            mapOf("learning_items" to ("id" to "book"), "notes" to ("id" to "note"),
                "risk_apps" to ("packageName" to "risk"), "topics" to ("id" to "topic"))
                .forEach { (table, key) -> db.openHelper.readableDatabase.query(
                    "SELECT COUNT(*) FROM `$table` WHERE `${key.first}`=?", arrayOf(marker.id(key.second)))
                    .use { check(it.moveToFirst() && it.getLong(0) == 0L) { "Synthetic identity collision" } } }
            DefaultLearningItemRepository(db, clock = { 1L }, newId = { marker.id("book") })
                .create("[SYNTHETIC V141] retained book", 320, 42, "翻到第40页，读第一段。", false)
            db.intentDao().insert(StudyIntentEntity(marker.id("intent"), marker.id("book"), 1, 1, 2, 2, IntentOutcome.CONVERTED, null))
            db.sessionDao().insert(StudySessionEntity(marker.id("session"), marker.id("book"), marker.id("intent"), 2, null, time, 40, 42, 42, SessionEndType.NORMAL, "[SYNTHETIC V141]", null))
            DefaultNoteRepository(db, storage, clock = { time }, newId = { marker.id("note") })
                .save(marker.id("book"), marker.id("session"), "[SYNTHETIC V141] $SEARCH_TERM 中文📖", 42)
            DefaultTopicRepository(db, search, clock = { time }, newId = { marker.id("topic") })
                .createAndLink(marker.id("note"), marker.id("topic"))
            val imported = DefaultImageRepository(db, storage, clock = { time }, newId = { marker.id("image") })
                .importFromGallery(marker.id("note"), listOf(Uri.fromFile(source)))
            check(imported.failedCount == 0 && imported.rejectedCount == 0 && imported.added.size == 1)
            db.focusDao().upsertRiskApp(RiskAppEntity(marker.id("risk"), "[SYNTHETIC V141]", 1, time))
            db.focusDao().insertContext(SessionFocusContextEntity(sessionId = marker.id("session"),
                monitoringStatus = MonitoringCoverage.NONE, monitoringLostAt = 2, priorDndInterruptionFilter = null, dndRuleId = null,
                closeoutState = FocusCloseoutState.COMPLETED, requestedEndPage = 42, closeoutStartedAt = time,
                lastHeartbeatAt = time, createdAt = 2, updatedAt = time))
            db.focusDao().insertRiskSnapshots(listOf(SessionRiskAppSnapshotEntity(marker.id("session"), marker.id("risk"), "[SYNTHETIC V141]")))
            db.focusDao().insertSegment(SessionSegmentEntity(marker.id("segment"), marker.id("session"), SessionSegmentType.UNMONITORED, 2, time, null, "[SYNTHETIC V141]", null, 0, null, null))
            db.focusDao().insertEvent(FocusEventEntity(marker.id("event"), marker.id("session"), FocusEventType.USER_PRESENT, 3, null, marker.id("segment"), null))
            search.reindexSession(marker.id("session"))
        }
        with(owner.backupPreferences.repository) {
            setLastDestination(TopLevelDestination.Profile); setThemeId(MirraThemeId.MONO)
            setDndEnabled(false); setCrossAppInterventionEnabled(false)
        }
    }

    private suspend fun verify(app: MirraApplication, marker: Properties) {
        val owner = owner(app)
        exclusive(app, owner) {
            val actual = frame(owner)
            check(marker.getProperty("expected.digest") == actual.digest) { "Installed upgrade changed retained facts, media or portable preference presence" }
            AUTHORITATIVE_TABLES.forEach { table ->
                assertEquals("Retained table count differs", marker.getProperty("expected.count.$table").toLong(), actual.counts.getValue(table))
            }
            val dao = owner.backupDatabase.wearableCompletedRecordDao()
            val row = checkNotNull(dao.get(marker.getProperty("wrist.installation"), marker.getProperty("wrist.session")))
            val parsed = WearableLocalRecord.parse(row.canonical, row.digest)
            check(row.digest == marker.getProperty("wrist.digest") && row.bytes == marker.getProperty("wrist.bytes").toInt() &&
                row.receivedAt == marker.getProperty("wrist.receivedAt").toLong() && row.installationId == parsed.installationId &&
                row.sessionId == parsed.sessionId && parsed.endPage == null && row.bytes == parsed.bytes)
            check(dao.canonicalBytes(row.installationId, row.sessionId)?.contentEquals(row.canonical.toByteArray(Charsets.UTF_8)) == true)
            check(owner.backupPreferences.repository.readStrictSnapshot().portable == controlledPreferences())
            assertSearch(owner, marker)
        }
    }

    private suspend fun roundTrip(app: MirraApplication, root: File, marker: Properties) {
        check(marker.getProperty("stage") in setOf("upgrade_asserted", "round_trip_asserted"))
        verify(app, marker)
        val archive = File(root, "upgraded-round-trip.zip")
        saveBackup(app, archive)
        restore(app, archive)
        verify(app, marker) // Resolves the new selected owner, never the retired pre-restore owner.
        marker.setProperty("stage", "round_trip_asserted"); saveMarker(root, marker)
        report("FORMAT2_ROUND_TRIP_ASSERTED", 10)
    }

    private suspend fun restoreBaseline(app: MirraApplication, base: File, root: File, request: Request) {
        val marker = marker(root, request)
        val stage = marker.getProperty("stage")
        check(stage in setOf("saving_baseline", "baseline_saved", "prepared", "upgrade_asserted", "round_trip_asserted"))
        if (stage != "saving_baseline") {
            val baseline = File(root, "baseline.zip")
            check(hash(baseline) == marker.getProperty("baseline.sha256")) { "Private baseline archive changed" }
            restore(app, baseline)
        }
        val selected = owner(app)
        exclusive(app, selected) {
            portableBaseline(selected)
            val actual = frame(selected)
            check(actual.digest == marker.getProperty("baseline.digest")) { "Original baseline is not restored; keep all recovery files" }
            AUTHORITATIVE_TABLES.forEach { table ->
                assertEquals("Original baseline count differs", marker.getProperty("baseline.count.$table").toLong(), actual.counts.getValue(table))
            }
        }
        check(root.canonicalFile.parentFile == base.canonicalFile && root.name == request.run)
        androidDurableFiles().deleteOwned(root, base)
        assertFalse(root.exists())
        report("BASELINE_RESTORED")
    }

    private suspend fun saveBackup(app: MirraApplication, destination: File) {
        owner(app)
        val service = checkNotNull(app.container.fullBackupService)
        val prepared = service.prepareBackup()
        try { service.saveBackup(prepared, Uri.fromFile(destination)) }
        finally { service.discardPreparedBackup(prepared) }
        check(destination.isFile && destination.length() > 0)
        // Fresh readback was validated by the service; sync the private archive before seeding.
        java.io.FileOutputStream(destination, true).use { it.fd.sync() }
        androidDurableFiles().syncDirectory(checkNotNull(destination.parentFile))
    }

    private suspend fun restore(app: MirraApplication, archive: File) {
        owner(app)
        val service = checkNotNull(app.container.fullBackupService)
        val candidate = service.inspectBackup(Uri.fromFile(archive))
        try {
            assertEquals(2, candidate.backupFormatVersion); assertEquals(5, candidate.schemaVersion)
            service.restore(candidate)
        } finally { service.discardRestoreCandidate(candidate) }
    }

    private suspend fun owner(app: MirraApplication): BackupStorageOwner {
        val state = withTimeout(15_000) { app.storageState.first { it.phase in setOf(StoragePresentation.OPEN, StoragePresentation.BLOCKED) } }
        check(state.phase == StoragePresentation.OPEN) { "Installed storage is blocked" }
        val owner = app.container as? BackupStorageOwner ?: error("Production storage owner required")
        owner.startup.await(); app.requireCurrent(owner)
        return owner
    }

    private suspend fun <T> exclusive(app: MirraApplication, owner: BackupStorageOwner, block: suspend () -> T): T {
        app.requireCurrent(owner); app.setMaintenanceBusy(true)
        var edits: PendingEditRegistry.FrozenEdits? = null
        try {
            edits = owner.pendingEdits.freezeAndFlush()
            return owner.storageGate.coordinator.withExclusive(15_000) {
                app.requireCurrent(owner); owner.assertRuntimeQuiescent()
                owner.backupDatabase.openHelper.readableDatabase.query("""
                    SELECT (SELECT COUNT(*) FROM study_intents WHERE activeSlot IS NOT NULL OR outcome IS NULL OR endedAt IS NULL) +
                      (SELECT COUNT(*) FROM study_sessions WHERE activeSlot IS NOT NULL OR endedAt IS NULL OR endType IS NULL) +
                      (SELECT COUNT(*) FROM session_segments WHERE activeSlot IS NOT NULL OR endedAt IS NULL) +
                      (SELECT COUNT(*) FROM session_focus_contexts WHERE closeoutState='PENDING')
                """.trimIndent()).use { check(it.moveToFirst() && it.getLong(0) == 0L) { "Active/pending learning must not be changed by this fixture" } }
                block()
            }
        } finally { withContext(NonCancellable) { edits?.release(); app.setMaintenanceBusy(false) } }
    }

    private suspend fun portableBaseline(owner: BackupStorageOwner) {
        check(owner.backupPreferences.repository.readStrictSnapshot().original.values.keys.all { it in PORTABLE_KEYS })
        owner.backupDatabase.openHelper.readableDatabase.query("SELECT COUNT(*) FROM session_focus_contexts WHERE dndLifecycle <> 'NOT_APPLIED' OR dndRuleId IS NOT NULL OR priorDndInterruptionFilter IS NOT NULL")
            .use { check(it.moveToFirst() && it.getLong(0) == 0L) { "Device ownership cannot be restored portably" } }
        val referenced = owner.backupDatabase.imageAssetDao().listAllPaths().map { it.removePrefix("images/") }.toSet()
        check(owner.storagePaths.images.listFiles().orEmpty().all { it.isFile && it.name in referenced && it.canonicalFile.parentFile == owner.storagePaths.images.canonicalFile }) {
            "Unreferenced media cannot be protected by this portable baseline"
        }
    }

    private fun assertSearch(owner: BackupStorageOwner, marker: Properties) {
        val expression = checkNotNull(DefaultSearchEngine().buildQuery(SEARCH_TERM)).expression
        owner.backupDatabase.openHelper.readableDatabase.query("SELECT COUNT(*) FROM search_fts WHERE entityType=? AND entityId=? AND search_fts MATCH ?",
            arrayOf("NOTE", marker.id("note"), expression)).use { check(it.moveToFirst() && it.getLong(0) == 1L) { "Retained synthetic note must remain searchable" } }
    }

    private suspend fun frame(owner: BackupStorageOwner): Frame {
        val canonical = CanonicalHash()
        val counts = linkedMapOf<String, Long>()
        owner.backupDatabase.withTransaction {
            val db = owner.backupDatabase.openHelper.readableDatabase
            db.query("PRAGMA user_version").use { check(it.moveToFirst() && it.getInt(0) == 5) }
            check(AUTHORITATIVE_TABLES.size == 13)
            AUTHORITATIVE_TABLES.sorted().forEach { table ->
                val columns = db.query("PRAGMA table_info(`$table`)").use { rows -> buildList {
                    while (rows.moveToNext()) add(rows.getString(rows.getColumnIndexOrThrow("name")))
                } }.sorted()
                check(columns.isNotEmpty() && columns.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*")) })
                canonical.text(table); canonical.long(columns.size.toLong()); columns.forEach(canonical::text)
                val names = columns.joinToString(",") { "`$it`" }
                var count = 0L
                db.query("SELECT $names FROM `$table` ORDER BY $names").use { rows -> while (rows.moveToNext()) {
                    count++; canonical.text("row")
                    columns.indices.forEach { index ->
                        val type = rows.getType(index); canonical.long(type.toLong())
                        when (type) {
                            Cursor.FIELD_TYPE_NULL -> Unit
                            Cursor.FIELD_TYPE_INTEGER -> canonical.long(rows.getLong(index))
                            Cursor.FIELD_TYPE_FLOAT -> canonical.long(java.lang.Double.doubleToLongBits(rows.getDouble(index)))
                            Cursor.FIELD_TYPE_STRING -> canonical.text(rows.getString(index))
                            Cursor.FIELD_TYPE_BLOB -> canonical.bytes(rows.getBlob(index))
                            else -> error("Unsupported SQLite type")
                        }
                    }
                } }
                counts[table] = count; canonical.text("table-end"); canonical.long(count)
            }
            db.query("SELECT localPath,fileSize FROM image_assets ORDER BY localPath COLLATE BINARY").use { rows -> while (rows.moveToNext()) {
                val path = rows.getString(0); check(ManagedImagePath.isValid(path))
                val image = File(owner.storagePaths.images, path.removePrefix("images/"))
                check(image.isFile && image.canonicalFile.parentFile == owner.storagePaths.images.canonicalFile && image.length() == rows.getLong(1))
                canonical.text(path); canonical.text(hash(image))
            } }
        }
        val snapshot = owner.backupPreferences.repository.readStrictSnapshot()
        check(snapshot.original.values.keys.all { it in PORTABLE_KEYS })
        with(snapshot.portable) {
            canonical.preference("last_destination", lastDestination.present, lastDestination.value.storageValue)
            canonical.preference("theme_id", themeId.present, themeId.value.storageValue)
            canonical.preference("dnd_enabled", dndEnabled.present, dndEnabled.value.toString())
            canonical.preference("cross_app_intervention_enabled", crossAppInterventionEnabled.present, crossAppInterventionEnabled.value.toString())
        }
        return Frame(canonical.finish(), counts)
    }

    private fun marker(root: File, request: Request) = Properties().apply {
        val file = File(root, "marker.properties")
        check(root.isDirectory && root.canonicalFile == root.absoluteFile && file.isFile && file.length() in 1..16_384)
        file.inputStream().use { load(it) }
        check(getProperty("version") == "1" && getProperty("run") == request.run)
        require(getProperty("prefix").matches(Regex("v141-[0-9a-f-]{36}-")))
    }

    private fun saveMarker(root: File, marker: Properties) {
        val bytes = ByteArrayOutputStream().also { marker.store(it, "Synthetic identifiers and private canonical digests; no user text") }.toByteArray()
        check(bytes.size <= 16_384); atomic(File(root, "marker.properties"), bytes)
    }

    private fun atomic(file: File, bytes: ByteArray) {
        val parent = checkNotNull(file.parentFile)
        check(file.canonicalFile.parentFile == parent.canonicalFile)
        val temp = File(parent, ".${file.name}.${UUID.randomUUID()}.tmp")
        val files = androidDurableFiles()
        try { files.write(temp, bytes); files.atomicReplace(temp, file); files.syncDirectory(parent) }
        finally { if (temp.exists()) files.deleteOwned(temp, parent) }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65_536); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun request(): Request {
        val args = InstrumentationRegistry.getArguments()
        val stage = args.getString("mirraV141Stage")
        assumeTrue("Explicit installed upgrade stage required", stage != null)
        require(stage in setOf("prepare", "assert_upgrade", "round_trip", "restore_baseline"))
        require(args.getString("mirraV141DedicatedAvd") == "true")
        require(Build.VERSION.SDK_INT == 37 && (Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("emulator")))
        val run = checkNotNull(args.getString("mirraV141Run"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        return Request(run, checkNotNull(stage))
    }

    private fun Properties.id(suffix: String) = checkNotNull(getProperty("prefix")) + suffix
    private fun report(stage: String, code: Int? = null) {
        val version = code?.let { " versionCode=$it" }.orEmpty()
        println("MIRRA_V141_INSTALLED stage=$stage$version syntheticFixture=true tables=13 format=2 roomSchema=5 rawDataLogged=false")
    }
    private fun controlledPreferences() = PortableAppPreferencesSnapshot(
        PreferenceSnapshotValue(TopLevelDestination.Profile, true), PreferenceSnapshotValue(MirraThemeId.MONO, true),
        PreferenceSnapshotValue(false, true), PreferenceSnapshotValue(false, true))
    private data class Request(val run: String, val stage: String)
    private data class Frame(val digest: String, val counts: Map<String, Long>)
    private class CanonicalHash {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val output = DataOutputStream(DigestOutputStream(object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }, digest))
        fun long(value: Long) = output.writeLong(value)
        fun bytes(value: ByteArray) { long(value.size.toLong()); output.write(value) }
        fun text(value: String) = bytes(value.toByteArray(Charsets.UTF_8))
        fun preference(name: String, present: Boolean, value: String) { text(name); long(if (present) 1 else 0); text(value) }
        fun finish(): String = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
    private companion object {
        const val SEARCH_TERM = "mirrav141retainednote"
        val PORTABLE_KEYS = setOf("last_destination", "theme_id", "dnd_enabled", "cross_app_intervention_enabled")
    }
}
