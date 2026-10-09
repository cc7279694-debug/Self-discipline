package com.guanyi.mirra.data.backup

import android.content.Context
import android.net.Uri
import com.guanyi.mirra.domain.backup.*
import com.guanyi.mirra.domain.maintenance.MaintenanceUnavailableException
import com.guanyi.mirra.domain.maintenance.PendingEditRegistry
import com.guanyi.mirra.domain.maintenance.RetiredStorageEpochException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class DefaultFullBackupService(
    private val context: Context,
    private val owner: BackupStorageOwner,
    private val host: BackupStorageHost,
    private val workRoot: File = File(context.noBackupFilesDir, "full-backup-work"),
    private val availableBytes: () -> Long = { context.filesDir.usableSpace },
) : FullBackupService {
    private val serial = Mutex()
    private val limits = BackupLimits()
    private val codec = BackupArchive(limits)
    private val database = BackupDatabaseSnapshot(context, limits)
    private val files = androidDurableFiles()
    private data class Export(val prepared: PreparedBackup, val root: File, val hash: String)
    private data class Import(val candidate: RestoreCandidate, val root: File, val archive: File, val hash: String)
    private val exports = mutableMapOf<String, Export>()
    private val imports = mutableMapOf<String, Import>()

    override suspend fun prepareBackup(): PreparedBackup = translated {
        serial.withLock {
            requireCurrentAndEligible()
            val root = newWork()
            try {
                maintenance {
                    val needed = resourceBytes(owner.storagePaths)
                    requireSpace(needed * 3)
                    val preferences = owner.backupPreferences.repository.readStrictSnapshot().portable
                    val snapshot = database.capture(owner.backupDatabase, owner.storagePaths.images, File(root, "snapshot"))
                    val archive = File(root, "Mirra-backup.zip")
                    val metadata = codec.create(snapshot, archive, preferences, System.currentTimeMillis())
                    // Validate the bytes we will hand to SAF, not just the in-memory manifest.
                    codec.validateAndExtract(archive, File(root, "verify"))
                    val prepared = PreparedBackup(archive, metadata.createdAt,
                        metadata.tableCounts.getValue("learning_items").toInt(),
                        metadata.tableCounts.getValue("notes").toInt(), metadata.imageCount)
                    exports[archive.canonicalPath] = Export(prepared, root, hash(archive))
                    prepared
                }
            } catch (failure: Throwable) { cleanup(root); throw failure }
        }
    }

    override suspend fun saveBackup(prepared: PreparedBackup, destination: Uri): Unit = translated {
        serial.withLock {
            host.requireCurrent(owner)
            val saved = exports[prepared.file.canonicalPath]?.takeIf { it.prepared == prepared }
                ?: throw FullBackupOperationException(FullBackupErrorCode.INVALID_ARCHIVE)
            check(hash(saved.prepared.file) == saved.hash) { "Prepared package changed" }
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                    saved.prepared.file.inputStream().use { input -> copyBounded(input, output, limits.archiveBytes) }
                    output.flush()
                } ?: throw IOException("Document could not be opened for writing")
                // SAF providers do not all guarantee write durability. Verify a fresh readback.
                val readback = File(saved.root, "readback-${UUID.randomUUID()}.zip")
                try {
                    context.contentResolver.openInputStream(destination)?.use { input ->
                        readback.outputStream().use { copyBounded(input, it, limits.archiveBytes) }
                    } ?: throw IOException("Saved document could not be reopened")
                    check(hash(readback) == saved.hash) { "Saved document bytes differ" }
                    codec.validateAndExtract(readback, File(saved.root, "readback-check-${UUID.randomUUID()}"))
                } finally { if (readback.exists()) files.deleteOwned(readback, workRoot) }
            }
        }
    }

    override suspend fun inspectBackup(source: Uri): RestoreCandidate = translated {
        serial.withLock {
            requireCurrentAndEligible()
            val root = newWork()
            try {
                val archive = File(root, "input.zip")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(source)?.use { input ->
                        archive.outputStream().use { output -> copyBounded(input, output, limits.archiveBytes, checkSpace = true) }
                    } ?: throw IOException("Document could not be read")
                }
                val validated = withContext(Dispatchers.IO) { codec.validateAndExtract(archive, File(root, "verified")) }
                requireSpace(validated.metadata.files.sumOf { it.bytes } * 4)
                val snapshot = database.validateAndReconstruct(validated, File(root, "staging"))
                writeStagingPreferences(snapshot.root, validated)
                val metadata = validated.metadata
                val candidate = RestoreCandidate(UUID.randomUUID().toString(), metadata.createdAt,
                    metadata.tableCounts.getValue("learning_items").toInt(), metadata.tableCounts.getValue("notes").toInt(),
                    metadata.imageCount, metadata.appVersion, metadata.roomSchemaVersion, metadata.backupFormatVersion)
                imports[candidate.token] = Import(candidate, root, archive, hash(archive))
                candidate
            } catch (failure: Throwable) { cleanup(root); throw failure }
        }
    }

    override suspend fun restore(candidate: RestoreCandidate) = host.runRestore {
        translated {
            serial.withLock {
                requireCurrentAndEligible()
                val saved = imports[candidate.token]?.takeIf { it.candidate == candidate }
                    ?: throw FullBackupOperationException(FullBackupErrorCode.INVALID_ARCHIVE)
                check(hash(saved.archive) == saved.hash) { "Validated source changed" }
                // Revalidate immutable package at confirmation. Never trust UI token or old staging.
                val validated = withContext(Dispatchers.IO) {
                    codec.validateAndExtract(saved.archive, File(saved.root, "confirm-${UUID.randomUUID()}"))
                }
                requireSpace(validated.metadata.files.sumOf { it.bytes } * 4 + resourceBytes(owner.storagePaths) * 3)
                val snapshot = database.validateAndReconstruct(validated, File(saved.root, "selected-${UUID.randomUUID()}"))
                val preferenceFile = writeStagingPreferences(snapshot.root, validated)
                var switching = false
                try {
                    maintenance { permit, edits ->
                        requireSpace(resourceBytes(owner.storagePaths) * 3 + resourceBytes(
                            RestoreResources(snapshot.databaseFile, preferenceFile, snapshot.imagesDirectory)) * 3)
                        edits.retire()
                        switching = true
                        host.replace(owner, RestoreResources(snapshot.databaseFile, preferenceFile, snapshot.imagesDirectory), permit)
                    }
                    imports.remove(candidate.token)
                    cleanup(saved.root)
                } catch (failure: Exception) {
                    // Once resource owners retire, only the host's journal/bootstrap can select
                    // a generation. UI must not claim the old dataset is still selected.
                    if (switching) throw FullBackupOperationException(FullBackupErrorCode.RESTORE_BLOCKED, failure)
                    throw failure
                }
            }
        }
    }

    override suspend fun discardPreparedBackup(prepared: PreparedBackup) { withContext(Dispatchers.IO) {
        serial.withLock {
            exports[prepared.file.canonicalPath]?.takeIf { it.prepared == prepared }?.let {
                cleanup(it.root); exports.remove(prepared.file.canonicalPath)
            }
        }
    } }
    override suspend fun discardRestoreCandidate(candidate: RestoreCandidate) { withContext(Dispatchers.IO) {
        serial.withLock { imports[candidate.token]?.takeIf { it.candidate == candidate }?.let {
            cleanup(it.root); imports.remove(candidate.token)
        } }
    } }

    private suspend fun requireCurrentAndEligible() {
        host.requireCurrent(owner)
        owner.startup.await()
        owner.backupDatabase.openHelper.readableDatabase.query("""
            SELECT (SELECT COUNT(*) FROM study_intents WHERE activeSlot IS NOT NULL) +
              (SELECT COUNT(*) FROM study_sessions WHERE activeSlot IS NOT NULL) +
              (SELECT COUNT(*) FROM session_focus_contexts WHERE closeoutState='PENDING')
        """.trimIndent()).use {
            check(it.moveToFirst())
            if (it.getLong(0)>0) throw FullBackupOperationException(FullBackupErrorCode.ACTIVE_LEARNING)
        }
        try { database.assertBackupEligible(owner.backupDatabase) }
        catch (failure: BackupValidationException) { throw FullBackupOperationException(FullBackupErrorCode.OWNED_CLEANUP, failure) }
    }
    private suspend fun <T> maintenance(block: suspend (com.guanyi.mirra.domain.maintenance.MaintenanceCoordinator.ExclusivePermit,
        PendingEditRegistry.FrozenEdits) -> T): T {
        host.setMaintenanceBusy(true)
        var edits: PendingEditRegistry.FrozenEdits? = null
        try {
            edits = owner.pendingEdits.freezeAndFlush()
            return owner.storageGate.coordinator.withExclusive(15_000) { permit ->
                requireCurrentAndEligible()
                try { owner.assertRuntimeQuiescent() }
                catch (failure: Exception) { throw FullBackupOperationException(FullBackupErrorCode.OWNED_CLEANUP, failure) }
                block(permit, checkNotNull(edits))
            }
        } finally {
            withContext(NonCancellable) { edits?.release(); host.setMaintenanceBusy(false) }
        }
    }
    private suspend fun <T> maintenance(block: suspend () -> T): T = maintenance { _, _ -> block() }

    private suspend fun writeStagingPreferences(root: File, validated: ValidatedBackup): File {
        val file = File(root, "portable.preferences_pb")
        val preferences = ManagedPreferences(file)
        try { preferences.writePortable(validated.portablePrefs) } finally { preferences.close() }
        return file
    }
    private suspend fun newWork(): File = withContext(Dispatchers.IO) {
        requireSpace(0)
        files.directory(workRoot)
        File(workRoot, UUID.randomUUID().toString()).also(files::directory)
    }
    private fun requireSpace(bytes: Long) {
        if (bytes < 0 || availableBytes() < bytes + RESERVE_BYTES) throw FullBackupOperationException(FullBackupErrorCode.LOW_SPACE)
    }
    private fun resourceBytes(resources: RestoreResources): Long = listOf(resources.database, resources.preferences, resources.images)
        .sumOf { resource -> if (resource.isDirectory) resource.walkTopDown().filter { it.isFile }.sumOf { it.length() } else resource.length() }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65_536); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private suspend fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, maximum: Long, checkSpace: Boolean = false) {
        val buffer = ByteArray(65_536)
        var total = 0L
        val start = android.os.SystemClock.elapsedRealtime()
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer); if (count < 0) break
            total += count
            if (total > maximum || android.os.SystemClock.elapsedRealtime() - start > limits.elapsedMillis)
                throw BackupValidationException("Document limit exceeded")
            if (checkSpace) requireSpace(count.toLong())
            output.write(buffer, 0, count)
        }
    }
    private fun cleanup(root: File) {
        if (root.exists()) files.deleteOwned(root, workRoot)
    }
    private suspend fun <T> translated(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (known: FullBackupException) { throw known }
        catch (failure: BackupValidationException) { throw FullBackupOperationException(FullBackupErrorCode.INVALID_ARCHIVE, failure) }
        catch (failure: MaintenanceUnavailableException) { throw FullBackupOperationException(FullBackupErrorCode.STORAGE_BUSY, failure) }
        catch (failure: RetiredStorageEpochException) { throw FullBackupOperationException(FullBackupErrorCode.STORAGE_BUSY, failure) }
        catch (failure: Exception) { throw FullBackupOperationException(FullBackupErrorCode.WRITE_OR_READ_FAILED, failure) }
    }
    private companion object { const val RESERVE_BYTES = 32L * 1024 * 1024 }
}
