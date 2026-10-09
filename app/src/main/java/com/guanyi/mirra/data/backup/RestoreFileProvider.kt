package com.guanyi.mirra.data.backup

import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.guanyi.mirra.R
import java.io.File

object RestoreFileAccess { @Volatile var blocked = false }

/** Providers can initialize before Application.onCreate; check private journal without opening stores. */
class RestoreFileProvider : FileProvider(R.xml.file_paths) {
    private fun checkAccess() {
        val app = checkNotNull(context)
        check(!RestoreFileAccess.blocked && !File(app.noBackupFilesDir, "full-restore/restore.journal").exists()) {
            "Mirra storage is awaiting restore recovery"
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? { checkAccess(); return super.openFile(uri, mode) }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        checkAccess(); return super.query(uri, projection, selection, selectionArgs, sortOrder)
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        checkAccess(); return super.delete(uri, selection, selectionArgs)
    }
    override fun getType(uri: Uri): String? { checkAccess(); return super.getType(uri) }
}
