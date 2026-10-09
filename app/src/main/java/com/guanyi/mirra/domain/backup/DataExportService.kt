package com.guanyi.mirra.domain.backup

import android.net.Uri
import java.io.File

/** Readable facts, not an input to Full Backup Restore. */
enum class DataExportFormat(val mimeType: String, val extension: String) {
    JSON("application/json", "json"), CSV_ZIP("application/zip", "zip"),
}

data class PreparedDataExport(
    val file: File,
    val createdAt: Long,
    val format: DataExportFormat,
)

interface DataExportService {
    suspend fun prepareExport(format: DataExportFormat): PreparedDataExport
    suspend fun saveExport(prepared: PreparedDataExport, destination: Uri)
    suspend fun discardPreparedExport(prepared: PreparedDataExport)
}
