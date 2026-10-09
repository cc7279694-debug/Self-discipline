package com.guanyi.mirra.data.backup

import com.guanyi.mirra.data.preferences.PortableAppPreferencesSnapshot
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Format limits are enforced against bytes actually read, not only ZIP declarations. */
data class BackupLimits(
    val archiveBytes: Long = 3_221_225_472L,
    val expandedBytes: Long = 4_294_967_296L,
    val databaseBytes: Long = 536_870_912L,
    val imageBytes: Long = 16_777_216L,
    val metadataBytes: Long = 2_097_152L,
    val preferencesBytes: Long = 16_384L,
    val entries: Int = 10_003,
    val compressionRatio: Long = 200,
    val rowsPerTable: Long = 100_000,
    val totalRows: Long = 1_000_000,
    val rowTextBytes: Long = 1_048_576,
    val elapsedMillis: Long = 600_000,
)

data class BackupFileRecord(val path: String, val bytes: Long, val sha256: String)
data class BackupMetadata(
    val appId: String,
    val backupFormatVersion: Int,
    val roomSchemaVersion: Int,
    val ownershipNormalizationVersion: Int,
    val createdAt: Long,
    val tableCounts: Map<String, Long>,
    val imageCount: Int,
    val files: List<BackupFileRecord>,
    val appVersion: String = "0.1.0",
)
data class BackupSnapshot(val root: File, val tableCounts: Map<String, Long>, val imageCount: Int) {
    val databaseFile: File get() = File(root, "database.sqlite")
    val imagesDirectory: File get() = File(root, "images")
}
data class ValidatedBackup(
    val root: File,
    val metadata: BackupMetadata,
    val portablePrefs: PortableAppPreferencesSnapshot,
)

class BackupValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal object BackupSearchBudget {
    fun requireBounded(parts: List<String?>, maximumChars: Long) {
        if (maximumChars < 0 || parts.sumOf { it?.length?.toLong() ?: 0 } > maximumChars)
            throw BackupValidationException("Search document exceeds character budget")
        var expanded = 0L
        var documents = 0
        parts.forEach { part ->
            val text = part?.trim()?.takeIf(String::isNotEmpty) ?: return@forEach
            // Mirrors DefaultSearchEngine's joined text and SearchTextNormalizer exactly.
            // Check before allocating its code-point arrays and per-token strings; do not
            // replace, truncate or otherwise change the frozen engine's resulting text.
            val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            expanded += normalized.length.toLong() + if (documents++ == 0) 0 else 1
            if (expanded > maximumChars) throw BackupValidationException("Normalized search document exceeds character budget")
        }
    }
}
