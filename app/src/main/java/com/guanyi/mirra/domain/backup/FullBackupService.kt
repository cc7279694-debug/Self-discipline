package com.guanyi.mirra.domain.backup

import android.net.Uri
import java.io.File

/** The service owns storage isolation, validation, maintenance admission and safe replacement. */
interface FullBackupService {
    /** Returns only after a complete private package is validated and normal storage admission resumes. */
    suspend fun prepareBackup(): PreparedBackup
    suspend fun saveBackup(prepared: PreparedBackup, destination: Uri)
    /** Copies and validates the external document privately; never changes live resources. */
    suspend fun inspectBackup(source: Uri): RestoreCandidate
    /** Rechecks eligibility and performs a full replacement with a durable current-data safety snapshot. */
    suspend fun restore(candidate: RestoreCandidate)
    suspend fun discardPreparedBackup(prepared: PreparedBackup) = Unit
    suspend fun discardRestoreCandidate(candidate: RestoreCandidate) = Unit
}

data class PreparedBackup(
    val file: File,
    val createdAt: Long,
    val itemCount: Int,
    val noteCount: Int,
    val imageCount: Int,
)

/** The token is resolved by the service, never interpreted as a path by the UI. */
data class RestoreCandidate(
    val token: String,
    val createdAt: Long,
    val itemCount: Int,
    val noteCount: Int,
    val imageCount: Int,
    val appVersion: String = "",
    val schemaVersion: Int = 4,
    val backupFormatVersion: Int = 1,
)
