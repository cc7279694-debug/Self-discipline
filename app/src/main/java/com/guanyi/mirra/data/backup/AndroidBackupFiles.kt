package com.guanyi.mirra.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import java.io.File

internal fun androidDurableFiles() = DurableFiles(syncDirectory = { directory ->
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try {
        check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "Sync target is not a directory" }
        Os.fsync(descriptor)
    } finally { Os.close(descriptor) }
}, atomicReplace = { source, target -> Os.rename(source.absolutePath, target.absolutePath) })

internal fun installedRestoreResources(context: Context) = RestoreResources(
    context.getDatabasePath("mirra.db"),
    File(context.filesDir, "datastore/mirra_preferences.preferences_pb"),
    File(context.filesDir, "images"),
)

internal fun installedRestoreJournal(context: Context) = RestoreJournal(
    File(context.noBackupFilesDir, "full-restore"), installedRestoreResources(context), androidDurableFiles(),
    verify = ::verifyClosedDatabase,
)

internal fun verifyClosedDatabase(resources: RestoreResources) {
    SQLiteDatabase.openDatabase(resources.database.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
        database.rawQuery("PRAGMA integrity_check", null).use { rows ->
            check(rows.moveToFirst() && rows.getString(0) == "ok" && !rows.moveToNext()) { "Database integrity check failed" }
        }
        database.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) { "Database references are invalid" } }
        database.rawQuery("PRAGMA user_version", null).use { check(it.moveToFirst() && it.getInt(0) == 4) { "Unsupported restored database" } }
    }
}
