package com.guanyi.mirra.data.backup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.CancellationSignal
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.storage.ImageImportPolicy
import com.guanyi.mirra.data.storage.ManagedImagePath
import com.guanyi.mirra.domain.DefaultSearchEngine
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupDatabaseSnapshot(private val context: Context, private val limits: BackupLimits = BackupLimits()) {
    /** Caller holds the complete maintenance barrier through database and referenced-media capture. */
    suspend fun capture(source: MirraDatabase, imagesRootFile: File, outputDir: File, normalizeOwnership: Boolean = true): BackupSnapshot = ioChecked {
        prepareEmptyDirectory(outputDir)
        val clean = newDatabase(File(outputDir, "database.sqlite"))
        try {
            val target = clean.openHelper.writableDatabase
            SqlBudget(limits.elapsedMillis).use { budget ->
                val counts = source.withTransaction {
                    val query = roomQuery(source.openHelper.readableDatabase, budget)
                    val counts = BackupValidation(limits).validateBusiness(query, requirePortableOwnership = false)
                    copyRows(query, target, normalizeOwnership, budget)
                    copyImages(query, imagesRootFile, File(outputDir, "images"), budget)
                    counts
                }
                finishCleanDatabase(target, counts, normalizeOwnership, budget)
                clean.close()
                finishFiles(outputDir, budget)
                BackupSnapshot(outputDir.canonicalFile, counts, counts.getValue("image_assets").toInt())
            }
        } finally { clean.close() }
    }

    /** Incoming bytes are never opened through Room and no incoming schema object is published. */
    suspend fun validateAndReconstruct(validated: ValidatedBackup, newEmptyDir: File): BackupSnapshot = ioChecked {
        prepareEmptyDirectory(newEmptyDir)
        val file = File(validated.root, "database.sqlite")
        demand(file.isFile && file.length() in 1..limits.databaseBytes, "Missing or oversized backup database")
        listOf("-wal", "-shm", "-journal").forEach { suffix -> demand(!File(file.path + suffix).exists(), "Incoming database has sidecars") }
        val clean = newDatabase(File(newEmptyDir, "database.sqlite"))
        try {
            val target = clean.openHelper.writableDatabase
            SqlBudget(limits.elapsedMillis).use { budget ->
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { incoming ->
                    incoming.execSQL("PRAGMA query_only=ON")
                    incoming.execSQL("PRAGMA trusted_schema=OFF")
                    val query: (String) -> Cursor = { sql -> budget.check(); incoming.rawQuery(sql, null, budget.signal) }
                    val validator = BackupValidation(limits)
                    validator.validateSchema(query, target)
                    val counts = validator.validateBusiness(query, requirePortableOwnership = true)
                    demand(counts == validated.metadata.tableCounts && counts.getValue("image_assets") == validated.metadata.imageCount.toLong(), "Database and manifest counts disagree")
                    copyRows(query, target, normalizeOwnership = false, budget)
                    copyImages(query, File(validated.root, "images"), File(newEmptyDir, "images"), budget)
                    finishCleanDatabase(target, counts, portable = true, budget)
                    clean.close()
                    finishFiles(newEmptyDir, budget)
                    BackupSnapshot(newEmptyDir.canonicalFile, counts, counts.getValue("image_assets").toInt())
                }
            }
        } finally { clean.close() }
    }

    suspend fun assertBackupEligible(source: MirraDatabase) = ioChecked {
        SqlBudget(limits.elapsedMillis).use { budget ->
            source.withTransaction {
                BackupValidation(limits).validateBusiness(roomQuery(source.openHelper.readableDatabase, budget), requirePortableOwnership = false)
            }
        }
        Unit
    }

    private fun newDatabase(file: File): MirraDatabase = Room.databaseBuilder(context, MirraDatabase::class.java, file.absolutePath)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()

    private fun roomQuery(database: SupportSQLiteDatabase, budget: SqlBudget): (String) -> Cursor = { sql ->
        budget.check(); database.query(SimpleSQLiteQuery(sql), budget.signal)
    }

    private fun copyRows(query: (String) -> Cursor, target: SupportSQLiteDatabase, normalizeOwnership: Boolean, budget: SqlBudget) {
        target.beginTransaction()
        try {
            AUTHORITATIVE_TABLES.forEach { table ->
                val columns = target.query("PRAGMA table_info(`$table`)").use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(rows.getColumnIndexOrThrow("name"))) } }
                val names = columns.joinToString(",") { "`$it`" }
                val parameters = columns.joinToString(",") { "?" }
                target.compileStatement("INSERT INTO `$table` ($names) VALUES ($parameters)").use { statement ->
                    query("SELECT $names FROM `$table` LIMIT ${limits.rowsPerTable + 1}").use { rows ->
                        var count = 0L
                        while (rows.moveToNext()) {
                            budget.check(); demand(++count <= limits.rowsPerTable, "Table exceeds row limit")
                            statement.clearBindings()
                            columns.forEachIndexed { index, column ->
                                val position = index + 1
                                when {
                                    normalizeOwnership && table == "session_focus_contexts" && column in setOf("dndRuleId", "priorDndInterruptionFilter") -> statement.bindNull(position)
                                    normalizeOwnership && table == "session_focus_contexts" && column == "dndLifecycle" -> statement.bindString(position, "NOT_APPLIED")
                                    rows.isNull(index) -> statement.bindNull(position)
                                    rows.getType(index) == Cursor.FIELD_TYPE_INTEGER -> statement.bindLong(position, rows.getLong(index))
                                    rows.getType(index) == Cursor.FIELD_TYPE_STRING -> statement.bindString(position, rows.getString(index))
                                    else -> throw BackupValidationException("Unsupported SQLite value type")
                                }
                            }
                            statement.executeInsert()
                        }
                    }
                }
            }
            target.setTransactionSuccessful()
        } finally { target.endTransaction() }
    }

    private fun copyImages(query: (String) -> Cursor, sourceImages: File, targetImages: File, budget: SqlBudget) {
        demand(sourceImages.canonicalFile != targetImages.canonicalFile, "Source and target images must be distinct")
        demand(targetImages.mkdir(), "Cannot create staged images")
        val paths = HashSet<String>()
        val sourceRoot = sourceImages.canonicalFile
        query("SELECT localPath,width,height,fileSize FROM image_assets ORDER BY localPath LIMIT ${limits.entries}").use { rows ->
            while (rows.moveToNext()) {
                budget.check()
                val path = rows.getString(0)
                demand(ManagedImagePath.isValid(path) && paths.add(path.lowercase(java.util.Locale.ROOT)), "Unsafe or duplicate image path")
                demand(paths.size <= limits.entries - 3, "Too many referenced images")
                val name = path.removePrefix("images/")
                val source = File(sourceImages, name)
                demand(source.isFile && source.canonicalFile.parentFile == sourceRoot && source.canonicalFile.name == name, "Missing or unsafe referenced image")
                val width = rows.getInt(1); val height = rows.getInt(2); val bytes = rows.getLong(3)
                validateImage(source, width, height, bytes)
                val copied = File(targetImages, name)
                copyExact(source, copied, bytes, budget)
                validateImage(copied, width, height, bytes)
            }
        }
        // Only database references are copied. Source orphans are deliberately excluded.
    }

    private fun copyExact(source: File, target: File, expectedBytes: Long, budget: SqlBudget) {
        demand(target.createNewFile(), "Staged image already exists")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        source.inputStream().use { input -> FileOutputStream(target).use { output ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                total += count; demand(total <= expectedBytes && total <= limits.imageBytes, "Image byte limit exceeded")
                budget.check(); digest.update(buffer, 0, count); output.write(buffer, 0, count)
            }
            output.fd.sync()
        } }
        demand(total == expectedBytes, "Image size changed during capture")
        val copiedDigest = MessageDigest.getInstance("SHA-256")
        target.inputStream().use { input -> val buffer = ByteArray(65_536); while (true) { val count = input.read(buffer); if (count < 0) break; budget.check(); copiedDigest.update(buffer, 0, count) } }
        demand(digest.digest().contentEquals(copiedDigest.digest()), "Staged image copy hash disagrees")
    }

    private fun validateImage(file: File, width: Int, height: Int, bytes: Long) {
        demand(bytes in 4..limits.imageBytes && file.length() == bytes, "Image byte metadata disagrees")
        demand(width in 1..ImageImportPolicy.MAX_EDGE && height in 1..ImageImportPolicy.MAX_EDGE, "Image pixels exceed limit")
        RandomAccessFile(file, "r").use { input ->
            demand(input.readUnsignedShort() == 0xffd8, "Image is not JPEG")
            input.seek(bytes - 2); demand(input.readUnsignedShort() == 0xffd9, "JPEG is truncated")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        demand(bounds.outMimeType == "image/jpeg" && bounds.outWidth == width && bounds.outHeight == height, "Image dimension metadata disagrees")
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                demand(info.size.width == width && info.size.height == height && info.mimeType == "image/jpeg", "JPEG decode bounds disagree")
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                decoder.setOnPartialImageListener { false }
            }
        } else {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                ?: throw BackupValidationException("JPEG cannot be decoded")
        }
        try { demand(bitmap.width == width && bitmap.height == height, "JPEG decode dimensions disagree") } finally { bitmap.recycle() }
    }

    private fun finishCleanDatabase(target: SupportSQLiteDatabase, counts: Map<String, Long>, portable: Boolean, budget: SqlBudget) {
        rebuildSearch(target, budget)
        demand(BackupValidation(limits).validateBusiness(roomQuery(target, budget), portable) == counts, "Reconstructed facts changed")
        target.query("PRAGMA wal_checkpoint(TRUNCATE)").use { rows ->
            // TRUNCATE journal databases report -1 frames; no live WAL is copied.
            if (rows.moveToFirst()) demand(rows.getInt(0) == 0, "Database checkpoint is busy")
        }
    }

    /** Stream documents and ordered caption rows; neither full-table listAll nor per-note SQL is used. */
    private fun rebuildSearch(database: SupportSQLiteDatabase, budget: SqlBudget) {
        val engine = DefaultSearchEngine()
        val query = roomQuery(database, budget)
        var expected = 0L
        val probes = linkedMapOf<String, Pair<String, String>>()
        database.beginTransaction()
        try {
            database.execSQL("DELETE FROM search_fts")
            database.compileStatement("INSERT INTO search_fts(entityType,entityId,searchableText,normalizedTokens) VALUES (?,?,?,?)").use { insert ->
                fun document(type: String, id: String, parts: List<String?>) {
                    budget.check(); BackupSearchBudget.requireBounded(parts, MAX_DOCUMENT_CHARS)
                    val built = engine.buildDocument(parts)
                    insert.clearBindings(); insert.bindString(1, type); insert.bindString(2, id); insert.bindString(3, built.searchableText); insert.bindString(4, built.normalizedTokens); insert.executeInsert()
                    expected++
                    if (type !in probes) built.normalizedTokens.split(' ').firstOrNull { it.isNotBlank() }?.let { probes[type] = id to it }
                }
                listOf(Triple("learning_items", "name", "LEARNING_ITEM"), Triple("topics", "name", "TOPIC"), Triple("study_sessions", "generatedSummary", "SESSION")).forEach { (table, column, type) ->
                    query("SELECT id,`$column` FROM `$table` LIMIT ${limits.rowsPerTable + 1}").use { rows -> while (rows.moveToNext()) {
                        budget.check()
                        val text = rows.getString(1)
                        if (type != "SESSION" || !text.isNullOrBlank()) document(type, rows.getString(0), listOf(text))
                    } }
                }
                query("SELECT noteId,caption FROM image_assets ORDER BY noteId,id LIMIT ${limits.rowsPerTable + 1}").use { images ->
                    var hasImage = images.moveToFirst()
                    query("SELECT id,content FROM notes ORDER BY id LIMIT ${limits.rowsPerTable + 1}").use { notes -> while (notes.moveToNext()) {
                        budget.check()
                        val id = notes.getString(0)
                        val parts = mutableListOf<String?>(notes.getString(1))
                        var chars = parts[0]?.length?.toLong() ?: 0
                        while (hasImage && images.getString(0) == id) {
                            budget.check()
                            val caption = images.getString(1); chars += caption?.length ?: 0
                            demand(chars <= MAX_DOCUMENT_CHARS, "Note/caption search document exceeds limit")
                            parts += caption; hasImage = images.moveToNext()
                        }
                        document("NOTE", id, parts)
                    } }
                    demand(!hasImage, "Orphan caption during FTS rebuild")
                }
            }
            query("SELECT COUNT(*) FROM search_fts").use { rows -> demand(rows.moveToFirst() && rows.getLong(0) == expected, "Search index row count disagrees") }
            probes.forEach { (type, probe) ->
                database.compileStatement("SELECT COUNT(*) FROM search_fts WHERE entityType=? AND entityId=? AND search_fts MATCH ?").use { query ->
                    query.bindString(1, type); query.bindString(2, probe.first); query.bindString(3, "\"${probe.second}\"")
                    demand(query.simpleQueryForLong() == 1L, "Staged search probe failed")
                }
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    private fun finishFiles(root: File, budget: SqlBudget) {
        val database = File(root, "database.sqlite")
        demand(database.length() in 1..limits.databaseBytes, "Staged database exceeds limit")
        demand(!File(database.path + "-wal").exists(), "Staged database still requires WAL")
        FileOutputStream(database, true).use { it.fd.sync() }
        androidDurableFiles().syncDirectory(File(root, "images"))
        androidDurableFiles().syncDirectory(root)
        // Prove this closed main file is independently readable; no install migrations or callbacks.
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { closed ->
            budget.check()
            closed.rawQuery("PRAGMA integrity_check", null, budget.signal).use { rows -> demand(rows.moveToFirst() && rows.getString(0) == "ok" && !rows.moveToNext(), "Closed staged database failed integrity") }
            budget.check()
            closed.rawQuery("PRAGMA foreign_key_check", null, budget.signal).use { rows -> demand(!rows.moveToFirst(), "Closed staged database has invalid references") }
        }
    }

    private fun prepareEmptyDirectory(directory: File) {
        demand(!directory.exists() || (directory.isDirectory && directory.listFiles()?.isEmpty() == true), "Snapshot directory must be empty")
        demand(directory.exists() || directory.mkdirs(), "Cannot create snapshot directory")
    }
    private fun demand(condition: Boolean, message: String) { if (!condition) throw BackupValidationException(message) }
    private suspend fun <T> ioChecked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (failure: CancellationException) { throw failure }
        catch (failure: BackupValidationException) { throw failure }
        catch (failure: Exception) { throw BackupValidationException("Cannot capture or validate backup", failure) }
    }

    private companion object { const val MAX_DOCUMENT_CHARS = 1_000_000L }
}

private class SqlBudget(private val millis: Long) : AutoCloseable {
    val signal = CancellationSignal()
    private val started = System.nanoTime()
    private val timer = Executors.newSingleThreadScheduledExecutor { work -> Thread(work, "mirra-backup-query-limit").apply { isDaemon = true } }
    private val deadline = timer.schedule({ signal.cancel() }, millis, TimeUnit.MILLISECONDS)
    fun check() { if (signal.isCanceled || (System.nanoTime() - started) / 1_000_000 > millis) throw BackupValidationException("Backup database time limit exceeded") }
    override fun close() { deadline.cancel(false); timer.shutdownNow() }
}
