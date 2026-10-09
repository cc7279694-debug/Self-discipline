package com.guanyi.mirra.data.export

import android.content.Context
import android.net.Uri
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.guanyi.mirra.data.backup.BackupArchive
import com.guanyi.mirra.data.backup.androidDurableFiles
import com.guanyi.mirra.domain.backup.*
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultDataExportService(
    private val context: Context,
    private val backup: FullBackupService,
    private val workRoot: File = File(context.noBackupFilesDir, "readable-export-work"),
    private val requireCurrent: suspend () -> Unit = {},
    private val availableBytes: () -> Long = { context.filesDir.usableSpace },
) : DataExportService {
    private val serial = Mutex()
    private val files = androidDurableFiles()
    private val workspace = ExportWorkspace(workRoot, files)
    private val limits = ReadableExportLimits()
    private data class OwnedExport(val prepared: PreparedDataExport, val hash: ByteArray)
    private val preparedExports = mutableMapOf<String, OwnedExport>()

    override suspend fun prepareExport(format: DataExportFormat): PreparedDataExport {
        var awaitingDelivery: PreparedDataExport? = null
        try {
            return translated { serial.withLock {
            requireCurrent()
            requireSpace(RESERVE_BYTES)
            val root = workspace.newOperationDirectory()
            var snapshot: PreparedBackup? = null
            var published = false
            try {
                files.directory(root)
                // Frozen 4C admission/drain, strict preferences, relationships and image validation.
                // The final readable artifact never contains JPEGs or private storage paths.
                snapshot = backup.prepareBackup()
                requireSpace(snapshot.file.length() * 2 + RESERVE_BYTES)
                val captured = BackupArchive().validateAndExtract(snapshot.file, File(root, "input"))
                val output = File(root, "facts.${format.extension}")
                val operation = currentCoroutineContext()
                SQLiteDatabase.openDatabase(File(captured.root, "database.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.execSQL("PRAGMA query_only=ON")
                    db.execSQL("PRAGMA trusted_schema=OFF")
                    val source = ExportRowSource { table, consume ->
                        val columns = table.columns.joinToString(",") { "`${it.name}`" }
                        db.rawQuery("SELECT $columns FROM `${table.name}` ORDER BY 1", null).use { cursor ->
                            while (cursor.moveToNext()) {
                                operation.ensureActive()
                                consume(table.columns.indices.map { index ->
                                    when (cursor.getType(index)) {
                                        Cursor.FIELD_TYPE_NULL -> null
                                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                        else -> throw ReadableExportException("Unexpected business field type")
                                    }
                                })
                            }
                        }
                    }
                    FileOutputStream(output).use { stream ->
                        val buffered = BufferedOutputStream(stream)
                        ReadableExportWriter(limits).write(format, source, buffered, snapshot.createdAt,
                            captured.metadata.tableCounts) {
                            operation.ensureActive()
                            requireSpace(RESERVE_BYTES)
                        }
                        buffered.flush()
                        stream.fd.sync()
                    }
                }
                val prepared = PreparedDataExport(output, snapshot.createdAt, format)
                val digest = digest(output.inputStream(), limits.totalBytes)
                files.deleteOwned(captured.root, root)
                // Complete upstream cleanup before registering a handle. A failed cleanup must
                // not leave an unreachable published artifact in this service.
                withContext(NonCancellable) {
                    val finished = snapshot
                    snapshot = null
                    backup.discardPreparedBackup(finished)
                }
                currentCoroutineContext().ensureActive()
                // Final check follows all I/O, including the frozen snapshot cleanup.
                requireCurrent()
                preparedExports[output.canonicalPath] = OwnedExport(prepared, digest)
                awaitingDelivery = prepared
                published = true
                prepared
            } finally {
                withContext(NonCancellable) {
                    try { snapshot?.let { backup.discardPreparedBackup(it) } }
                    finally { if (!published && root.exists()) files.deleteOwned(root, workRoot) }
                }
            }
            } }
        } catch (failure: Throwable) {
            // withContext has prompt cancellation when returning to the caller. The caller
            // may never receive the prepared value, so the service retains cleanup duty.
            withContext(NonCancellable) { awaitingDelivery?.let { discardPreparedExport(it) } }
            throw failure
        }
    }

    override suspend fun saveExport(prepared: PreparedDataExport, destination: Uri): Unit = translated {
        serial.withLock {
            requireCurrent()
            val owned = preparedExports[prepared.file.canonicalPath]
            if (owned == null || owned.prepared != prepared || !prepared.file.isFile ||
                !MessageDigest.isEqual(owned.hash, digest(prepared.file.inputStream(), limits.totalBytes))) {
                throw FullBackupOperationException(FullBackupErrorCode.INVALID_ARCHIVE)
            }
            // A provider failure may leave a partial external document, never alter live facts.
            val output = context.contentResolver.openOutputStream(destination, "wt")
                ?: throw FullBackupOperationException(FullBackupErrorCode.WRITE_OR_READ_FAILED)
            output.use { target ->
                prepared.file.inputStream().use { source -> copy(source, target) }
                target.flush()
            }
            val readBack = context.contentResolver.openInputStream(destination)
                ?: throw FullBackupOperationException(FullBackupErrorCode.WRITE_OR_READ_FAILED)
            if (!MessageDigest.isEqual(owned.hash, digest(readBack, limits.totalBytes))) {
                throw FullBackupOperationException(FullBackupErrorCode.WRITE_OR_READ_FAILED)
            }
        }
    }

    override suspend fun discardPreparedExport(prepared: PreparedDataExport): Unit = translated {
        serial.withLock {
            val key = prepared.file.canonicalPath
            val owned = preparedExports[key] ?: return@withLock
            if (owned.prepared != prepared) throw FullBackupOperationException(FullBackupErrorCode.INVALID_ARCHIVE)
            files.deleteOwned(checkNotNull(prepared.file.parentFile), workRoot)
            preparedExports.remove(key)
        }
    }

    private fun requireSpace(bytes: Long) {
        if (bytes < 0 || availableBytes() < bytes) throw FullBackupOperationException(FullBackupErrorCode.LOW_SPACE)
    }

    /** Private temporary housekeeping only: never opens business resources or removes current-process work. */
    suspend fun reclaimPreviousProcesses(): Unit = translated {
        serial.withLock { workspace.reclaimPreviousProcesses() }
    }

    private suspend fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(65_536)
        var count = 0L
        val start = android.os.SystemClock.elapsedRealtime()
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            checkBudget(count, start)
            output.write(buffer, 0, read)
        }
    }

    private suspend fun digest(input: InputStream, maximum: Long): ByteArray = input.use { source ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        var total = 0L
        val start = android.os.SystemClock.elapsedRealtime()
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = source.read(buffer)
            if (read < 0) break
            total += read
            if (total > maximum) throw ReadableExportException("Export file exceeds limit")
            checkBudget(total, start)
            hash.update(buffer, 0, read)
        }
        hash.digest()
    }

    private fun checkBudget(total: Long, start: Long) {
        if (total > limits.totalBytes || android.os.SystemClock.elapsedRealtime() - start > limits.elapsedMillis)
            throw ReadableExportException("Export file/time limit exceeded")
    }

    private suspend fun <T> translated(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (known: FullBackupException) { throw known }
        catch (failure: Exception) { throw FullBackupOperationException(FullBackupErrorCode.WRITE_OR_READ_FAILED, failure) }
    }

    private companion object { const val RESERVE_BYTES = 32L * 1024 * 1024 }
}
