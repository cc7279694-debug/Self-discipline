package com.guanyi.mirra.data.export

import com.guanyi.mirra.data.backup.DurableFiles
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** Synchronous private-workspace housekeeping; invoke from the IO dispatcher. */
class ExportWorkspace(
    private val baseDirectory: File,
    private val files: DurableFiles,
    processId: String = CURRENT_PROCESS_ID,
) {
    private val processId: String

    init {
        require(UUID_NAME.matches(processId)) { "Invalid export process identity" }
        require(baseDirectory.name !in setOf("", ".", "..") && baseDirectory.absoluteFile.parentFile != null) {
            "Export workspace must be an explicit dedicated directory"
        }
        this.processId = processId.lowercase(Locale.ROOT)
    }

    /** Returns a reserved name, not a created operation directory; the service owns its mkdir/cleanup. */
    fun newOperationDirectory(): File = synchronized(RECLAMATION_LOCK) {
        files.directory(baseDirectory)
        reclaimLocked()
        var candidate: File
        do {
            candidate = File(baseDirectory, "${processId}_${UUID.randomUUID()}")
        } while (candidate.exists())
        if (candidate.canonicalFile.parentFile != baseDirectory.canonicalFile) {
            throw IOException("Export operation path escaped its workspace")
        }
        candidate
    }

    fun reclaimPreviousProcesses(): Unit = synchronized(RECLAMATION_LOCK) {
        if (baseDirectory.exists()) {
            // Reuses the frozen direct-entry/canonical guard, including Android-owned ancestor aliases.
            files.directory(baseDirectory)
            reclaimLocked()
        }
    }

    private fun reclaimLocked() {
        val entries = baseDirectory.listFiles() ?: throw IOException("Cannot enumerate export workspace")
        var removed = false
        entries.forEach { entry ->
            if (entry.isDirectory && isPreviousOperation(entry.name)) {
                files.deleteOwned(entry, baseDirectory)
                removed = true
            }
        }
        if (removed) files.syncDirectory(baseDirectory)
    }

    private fun isPreviousOperation(name: String): Boolean {
        if (UUID_NAME.matches(name)) return true // Legacy operation names before process identity was recorded.
        val operation = PROCESS_OPERATION.matchEntire(name) ?: return false
        return operation.groupValues[1].lowercase(Locale.ROOT) != processId
    }

    private companion object {
        // One identity and one lock for the complete process, across all storage generations.
        val CURRENT_PROCESS_ID: String = UUID.randomUUID().toString()
        val RECLAMATION_LOCK = Any()
        const val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        val UUID_NAME = Regex(UUID_PATTERN)
        val PROCESS_OPERATION = Regex("($UUID_PATTERN)_($UUID_PATTERN)")
    }
}
