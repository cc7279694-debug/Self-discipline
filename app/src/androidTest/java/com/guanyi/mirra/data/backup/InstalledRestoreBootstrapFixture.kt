package com.guanyi.mirra.data.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.guanyi.mirra.MirraApplication
import com.guanyi.mirra.StoragePresentation
import com.guanyi.mirra.data.preferences.StoredPreferenceValue
import com.guanyi.mirra.domain.DefaultSearchEngine
import com.guanyi.mirra.domain.maintenance.PendingEditRegistry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Properties
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
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OPT-IN ONLY: exercises the real installed Application's startup journal, not a sandbox recovery.
 *
 * Dedicated API 37 AVD arguments: mirra4cBootstrapDedicatedAvd=true,
 * mirra4cBootstrapRun=[A-Za-z0-9_-]{1,64}, mirra4cBootstrapBoundary=images:published|COMMITTED.
 * 1. Driver force-stops the target so no Activity reader exists, then instruments prepare with
 *    mirra4cBootstrapStage=prepare. The genuine owners are drained and closed before copying.
 * 2. Driver force-stops and cold-starts the target, retaining external stop/start evidence.
 * 3. Driver instruments assert with mirra4cBootstrapStage=assert and the same Run/Boundary.
 *    Assert NEVER calls recoverBeforeOpeningStores: Application.onCreate must have selected OLD
 *    or NEW before OPEN. The fixture then restores every original resource using the real host
 *    replacement protocol and verifies all business columns, images, raw preference types/values
 *    and missing-key state before deleting only its unique private cache run.
 *
 * Failed assertions keep the original snapshot and marker. Do not clear data, uninstall, or remove
 * its cache to make a failure green. This is abrupt process loss, not physical power-loss evidence.
 */
@RunWith(AndroidJUnit4::class)
class InstalledRestoreBootstrapFixture {
    @Test fun prepareInstalledJournalForExternalProcessStop() {
        val request = request("prepare")
        assertNoActivityReaders()
        runBlocking(Dispatchers.IO) {
            val application = application()
            val owner = selectedOwner(application)
            val root = root(application, request)
            check(!root.exists()) { "Existing evidence must be recovered, not overwritten" }
            check(!installedRestoreJournal(application).hasPendingRecovery()) { "Existing restore decision must not be replaced" }
            check(owner.storagePaths.database.isFile && owner.storagePaths.preferences.isFile && owner.storagePaths.images.isDirectory) {
                "Original three resources must exist before this fixture can preserve their exact presence"
            }
            val needed = bytes(owner.storagePaths.database) + bytes(owner.storagePaths.preferences) + bytes(owner.storagePaths.images)
            check(application.filesDir.usableSpace > Math.addExact(Math.multiplyExact(needed, 4), 32L * 1024 * 1024)) {
                "Insufficient room for complete original and journal safety snapshots"
            }
            val files = androidDurableFiles()
            files.directory(root)
            val synthetic = RestoreAndroidFixture(application, File(root, "synthetic"))
            synthetic.seed()
            val candidateFrame = closedFrame(synthetic.candidate)
            val marker = Properties().apply {
                setProperty("version", "1"); setProperty("run", request.run)
                setProperty("boundary", request.boundary); setProperty("preparedPid", Process.myPid().toString())
                setProperty("stage", "candidate_ready"); setProperty("new.digest", candidateFrame.digest)
            }
            writeMarker(root, marker)
            application.setMaintenanceBusy(true)
            var edits: PendingEditRegistry.FrozenEdits? = null
            var retired = false
            try {
                edits = owner.pendingEdits.freezeAndFlush()
                owner.storageGate.coordinator.withExclusive(15_000) { permit ->
                    application.requireCurrent(owner)
                    assertNoActivityReaders()
                    assertNoActive(owner)
                    owner.assertRuntimeQuiescent()
                    val oldFrame = ownerFrame(owner)
                    // Stop admission permanently BEFORE closing any actual storage owner.
                    checkNotNull(edits).retire()
                    owner.storageGate.retire(permit, "Installed bootstrap process-loss fixture")
                    retired = true
                    owner.closeStorageOwners()
                    val original = original(root)
                    copyResources(owner.storagePaths, original)
                    assertEquals("Closing/copying may not change original business facts", oldFrame, closedFrame(original))
                    marker.setProperty("old.digest", oldFrame.digest)
                    marker.setProperty("old.search", oldFrame.search)
                    marker.setProperty("original.resources", resourceDigest(original))
                    marker.setProperty("stage", "original_saved")
                    writeMarker(root, marker)
                    blockApplication(application)
                    val journal = RestoreJournal(File(application.noBackupFilesDir, "full-restore"),
                        installedRestoreResources(application), files, ::verifyClosedDatabase,
                        effect = { if (it == request.boundary) throw RestoreInjectedDeath() })
                    try {
                        journal.apply(journal.prepare(synthetic.candidate))
                        fail("Requested installed durable boundary was not reached")
                    } catch (_: RestoreInjectedDeath) {
                        // Error bypasses compensation and leaves the production bootstrap decision.
                    }
                    assertTrue("Installed startup must receive a pending decision", journal.hasPendingRecovery())
                    assertEquals(StoragePresentation.BLOCKED, application.storageState.value.phase)
                    assertTrue(RestoreFileAccess.blocked)
                    marker.setProperty("stage", "prepared")
                    writeMarker(root, marker)
                    println("MIRRA_4C_INSTALLED_BOOTSTRAP_PREPARED boundary=${request.boundary} actualInstalledJournal=true originalSnapshot=true ownersClosed=true")
                }
            } catch (failure: Throwable) {
                if (retired) blockApplication(application)
                throw failure
            } finally {
                withContext(NonCancellable) {
                    if (!retired) edits?.release()
                    if (!retired) application.setMaintenanceBusy(false)
                }
            }
        }
    }

    @Test fun assertApplicationBootstrapAndRestoreOriginalInstalledData() {
        val request = request("assert")
        runBlocking(Dispatchers.IO) {
            val application = application()
            val root = root(application, request)
            val marker = readMarker(root, request)
            assertNotEquals("Host must actually stop and cold-start the app process",
                marker.getProperty("preparedPid").toInt(), Process.myPid())
            // No journal recovery call here: waiting for OPEN requires real Application bootstrap.
            val owner = selectedOwner(application)
            assertFalse("Application must clear its genuine journal before OPEN", installedRestoreJournal(application).hasPendingRecovery())
            assertFalse("FileProvider gate must reopen only after safe bootstrap", RestoreFileAccess.blocked)
            val committed = request.boundary == "COMMITTED"
            val selected = exclusive(application, owner) {
                val frame = ownerFrame(owner)
                assertEquals("Bootstrap must choose one complete DB/prefs/images generation",
                    marker.getProperty(if (committed) "new.digest" else "old.digest"), frame.digest)
                if (!committed) assertEquals("Original search results must survive rollback", marker.getProperty("old.search"), frame.search)
                else assertCandidateSearch(owner)
                frame
            }
            marker.setProperty("stage", "bootstrap_asserted")
            writeMarker(root, marker)
            println("MIRRA_4C_INSTALLED_BOOTSTRAP_ASSERTED boundary=${request.boundary} selected=${if (committed) "new" else "old"} actualApplicationBootstrap=true resources=3 tables=12 independentRecoverCall=false")

            val safetySnapshot = original(root)
            assertEquals("Original safety snapshot must remain intact", marker.getProperty("original.resources"), resourceDigest(safetySnapshot))
            verifyClosedDatabase(safetySnapshot)
            // Restore complete original raw resources, not portable-only projection, using real host.
            application.runRestore {
                val selectedOwner = selectedOwner(application)
                application.setMaintenanceBusy(true)
                var edits: PendingEditRegistry.FrozenEdits? = null
                try {
                    edits = selectedOwner.pendingEdits.freezeAndFlush()
                    selectedOwner.storageGate.coordinator.withExclusive(15_000) { permit ->
                        application.requireCurrent(selectedOwner)
                        assertNoActive(selectedOwner)
                        selectedOwner.assertRuntimeQuiescent()
                        assertEquals(marker.getProperty("original.resources"), resourceDigest(safetySnapshot))
                        checkNotNull(edits).retire()
                        application.replace(selectedOwner, safetySnapshot, permit)
                    }
                } finally {
                    withContext(NonCancellable) { edits?.release(); application.setMaintenanceBusy(false) }
                }
            }
            val restoredOwner = selectedOwner(application)
            assertTrue("Replacement must use a fresh owner", restoredOwner !== owner)
            val restored = exclusive(application, restoredOwner) { ownerFrame(restoredOwner) }
            assertEquals("ALL original IDs, columns, images and raw preference keys/types/presence must return",
                marker.getProperty("old.digest"), restored.digest)
            assertEquals("Original derived search results must return", marker.getProperty("old.search"), restored.search)
            assertFalse(installedRestoreJournal(application).hasPendingRecovery())
            assertFalse(RestoreFileAccess.blocked)
            marker.setProperty("stage", "original_restored")
            writeMarker(root, marker)
            val family = File(application.cacheDir.canonicalFile, FAMILY)
            check(root.canonicalFile.parentFile == family.canonicalFile && root.name == request.run)
            androidDurableFiles().deleteOwned(root, family)
            assertFalse(root.exists())
            println("MIRRA_4C_INSTALLED_BOOTSTRAP_ORIGINAL_RESTORED boundary=${request.boundary} originalDigestMatched=true allRawPreferenceKeys=true originalSearchMatched=true cacheRemoved=true physicalPowerCut=false selectedWasDifferent=${selected.digest != restored.digest}")
        }
    }

    private data class Request(val run: String, val boundary: String)
    private data class Frame(val digest: String, val search: String)
    private fun request(stage: String): Request {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit installed bootstrap fixture stage required", args.getString("mirra4cBootstrapStage") == stage)
        require(args.getString("mirra4cBootstrapDedicatedAvd") == "true") { "Dedicated AVD authorization required" }
        require(Build.VERSION.SDK_INT == 37 && (Build.FINGERPRINT.startsWith("generic") ||
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))) { "Physical devices are prohibited" }
        val run = checkNotNull(args.getString("mirra4cBootstrapRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val boundary = checkNotNull(args.getString("mirra4cBootstrapBoundary"))
        require(boundary in setOf("images:published", "COMMITTED"))
        return Request(run, boundary)
    }
    private fun application() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MirraApplication
    private fun root(application: MirraApplication, request: Request): File {
        val family = File(application.cacheDir.canonicalFile, FAMILY)
        return File(family, request.run).also { check(it.canonicalFile.parentFile == family.canonicalFile) }
    }
    private fun original(root: File) = RestoreResources(File(root, "original/database.sqlite"),
        File(root, "original/preferences.preferences_pb"), File(root, "original/images"))
    private suspend fun selectedOwner(application: MirraApplication): BackupStorageOwner {
        val state = withTimeout(30_000) {
            application.storageState.first { it.phase == StoragePresentation.OPEN || it.phase == StoragePresentation.BLOCKED }
        }
        check(state.phase == StoragePresentation.OPEN) { "Application did not safely open storage" }
        return (application.container as BackupStorageOwner).also { it.startup.await(); application.requireCurrent(it) }
    }
    private fun assertNoActivityReaders() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            check(Stage.values().filter { it != Stage.DESTROYED }.all { monitor.getActivitiesInStage(it).isEmpty() }) {
                "Prepare requires a force-stopped target with no live Activity readers"
            }
        }
    }
    private fun blockApplication(application: MirraApplication) {
        RestoreFileAccess.blocked = true
        // Test-only reflection; no production testing hook or direct recovery is introduced.
        MirraApplication::class.java.getDeclaredMethod("showStorageBlocked").apply { isAccessible = true }.invoke(application)
    }
    private suspend fun <T> exclusive(application: MirraApplication, owner: BackupStorageOwner, block: suspend () -> T): T {
        application.setMaintenanceBusy(true)
        var edits: PendingEditRegistry.FrozenEdits? = null
        try {
            edits = owner.pendingEdits.freezeAndFlush()
            return owner.storageGate.coordinator.withExclusive(15_000) {
                application.requireCurrent(owner); assertNoActive(owner); owner.assertRuntimeQuiescent(); block()
            }
        } finally { withContext(NonCancellable) { edits?.release(); application.setMaintenanceBusy(false) } }
    }
    private fun assertNoActive(owner: BackupStorageOwner) {
        owner.backupDatabase.openHelper.readableDatabase.query("""
            SELECT (SELECT COUNT(*) FROM study_intents WHERE activeSlot IS NOT NULL OR outcome IS NULL OR endedAt IS NULL) +
                (SELECT COUNT(*) FROM study_sessions WHERE activeSlot IS NOT NULL OR endedAt IS NULL OR endType IS NULL) +
                (SELECT COUNT(*) FROM session_segments WHERE activeSlot IS NOT NULL OR endedAt IS NULL) +
                (SELECT COUNT(*) FROM session_focus_contexts WHERE closeoutState='PENDING')
        """.trimIndent()).use { check(it.moveToFirst() && it.getLong(0) == 0L) { "No learning may be ended for this fixture" } }
    }
    private fun copyResources(from: RestoreResources, to: RestoreResources) {
        val files = androidDurableFiles()
        files.copy(from.database, to.database); files.copy(from.preferences, to.preferences); files.copy(from.images, to.images)
        verifyClosedDatabase(to)
    }
    private suspend fun ownerFrame(owner: BackupStorageOwner): Frame {
        val database = owner.backupDatabase.openHelper.readableDatabase
        return frame({ sql -> database.query(sql) }, owner.backupPreferences, owner.storagePaths.images)
    }
    private suspend fun closedFrame(resources: RestoreResources): Frame {
        val preferences = ManagedPreferences(resources.preferences)
        try {
            return SQLiteDatabase.openDatabase(resources.database.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                frame({ sql -> database.rawQuery(sql, null) }, preferences, resources.images)
            }
        } finally { preferences.close() }
    }
    private suspend fun frame(query: (String) -> Cursor, preferences: ManagedPreferences, images: File): Frame {
        val prefs = preferences.repository.readStrictSnapshot().original.values
        val business = digest { output ->
            AUTHORITATIVE_TABLES.sorted().forEach { table ->
                output.text(table)
                query("SELECT * FROM `$table` ORDER BY " + query("PRAGMA table_info(`$table`)").use { columns ->
                    buildList { while (columns.moveToNext()) add("`${columns.getString(1)}`") }.joinToString(",")
                }).use { rows -> writeRows(output, rows) }
            }
            output.writeInt(prefs.size)
            prefs.toSortedMap().forEach { (key, value) ->
                output.text(key)
                when (value) {
                    is StoredPreferenceValue.BooleanValue -> { output.writeByte(1); output.writeBoolean(value.value) }
                    is StoredPreferenceValue.FloatValue -> { output.writeByte(2); output.writeInt(value.value.toRawBits()) }
                    is StoredPreferenceValue.DoubleValue -> { output.writeByte(3); output.writeLong(value.value.toRawBits()) }
                    is StoredPreferenceValue.IntValue -> { output.writeByte(4); output.writeInt(value.value) }
                    is StoredPreferenceValue.LongValue -> { output.writeByte(5); output.writeLong(value.value) }
                    is StoredPreferenceValue.StringValue -> { output.writeByte(6); output.text(value.value) }
                    is StoredPreferenceValue.StringSetValue -> { output.writeByte(7); output.writeInt(value.value.size); value.value.sorted().forEach { output.text(it) } }
                    is StoredPreferenceValue.ByteArrayValue -> { output.writeByte(8); val bytes = value.value; output.writeInt(bytes.size); output.write(bytes) }
                }
            }
            output.text(treeDigest(images))
        }
        val search = digest { output -> query("SELECT entityType,entityId,searchableText,normalizedTokens FROM search_fts ORDER BY entityType,entityId,searchableText,normalizedTokens").use { writeRows(output, it) } }
        return Frame(business, search)
    }
    private fun writeRows(output: DataOutputStream, rows: Cursor) {
        output.writeInt(rows.columnCount); rows.columnNames.forEach { output.text(it) }
        while (rows.moveToNext()) {
            output.writeByte(1)
            for (column in 0 until rows.columnCount) {
                val type = rows.getType(column); output.writeInt(type)
                when (type) {
                    Cursor.FIELD_TYPE_NULL -> Unit
                    Cursor.FIELD_TYPE_INTEGER -> output.writeLong(rows.getLong(column))
                    Cursor.FIELD_TYPE_FLOAT -> output.writeLong(rows.getDouble(column).toRawBits())
                    Cursor.FIELD_TYPE_STRING -> output.text(rows.getString(column))
                    Cursor.FIELD_TYPE_BLOB -> { val bytes = rows.getBlob(column); output.writeInt(bytes.size); output.write(bytes) }
                    else -> error("Unsupported SQLite cell type")
                }
            }
        }
        output.writeByte(0)
    }
    private fun assertCandidateSearch(owner: BackupStorageOwner) {
        val query = checkNotNull(DefaultSearchEngine().buildQuery("候选中文")).expression
        owner.backupDatabase.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM search_fts WHERE entityType='NOTE' AND entityId='new-note-0000' AND search_fts MATCH ?", arrayOf(query),
        ).use { assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)) }
    }
    private fun resourceDigest(resources: RestoreResources) = digest {
        it.text(treeDigest(resources.database)); it.text(treeDigest(resources.preferences)); it.text(treeDigest(resources.images))
    }
    private fun treeDigest(root: File) = digest { output ->
        fun visit(file: File, relative: String) {
            check(File(checkNotNull(file.parentFile).canonicalFile, file.name) == file.canonicalFile) { "Symbolic entries prohibited" }
            output.text(relative)
            when {
                file.isDirectory -> { output.writeByte(1); checkNotNull(file.listFiles()).sortedBy { it.name }.forEach { visit(it, "$relative/${it.name}") } }
                file.isFile -> { output.writeByte(2); output.writeLong(file.length()); file.inputStream().use { it.copyTo(output) } }
                !file.exists() -> output.writeByte(0)
                else -> error("Unsupported storage resource")
            }
        }
        visit(root, "root")
    }
    private fun bytes(file: File): Long {
        check(File(checkNotNull(file.parentFile).canonicalFile, file.name) == file.canonicalFile) { "Symbolic entries prohibited" }
        return when {
            file.isFile -> file.length()
            file.isDirectory -> checkNotNull(file.listFiles()).fold(0L) { sum, child -> Math.addExact(sum, bytes(child)) }
            else -> 0L
        }
    }
    private fun digest(block: (DataOutputStream) -> Unit): String {
        val sha = MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(object : OutputStream() { override fun write(value: Int) = Unit; override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit }, sha)).use(block)
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
    private fun DataOutputStream.text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); writeInt(bytes.size); write(bytes) }
    private fun writeMarker(root: File, marker: Properties) {
        val bytes = ByteArrayOutputStream().also { marker.store(it, "Dedicated AVD installed bootstrap fixture") }.toByteArray()
        check(bytes.size <= 16_384)
        androidDurableFiles().write(File(root, "bootstrap-fixture.properties"), bytes)
    }
    private fun readMarker(root: File, request: Request): Properties {
        val file = File(root, "bootstrap-fixture.properties")
        check(file.isFile && file.length() in 1..16_384 && File(root.canonicalFile, file.name) == file.canonicalFile)
        return Properties().apply {
            file.inputStream().use { load(it) }
            check(getProperty("version") == "1" && getProperty("run") == request.run &&
                getProperty("boundary") == request.boundary && getProperty("stage") == "prepared")
            listOf("old.digest", "old.search", "new.digest", "original.resources").forEach { check(getProperty(it).matches(Regex("[0-9a-f]{64}"))) }
        }
    }
    private companion object { const val FAMILY = "installed-restore-bootstrap" }
}
