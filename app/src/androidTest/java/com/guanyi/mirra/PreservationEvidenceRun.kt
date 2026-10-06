package com.guanyi.mirra

import android.content.Context
import java.io.File

/** A key selects new evidence, never changes or repairs a previously recorded fixture. */
internal fun preservationEvidenceMarker(context: Context, runKey: String?): File {
    require(runKey == null || Regex("[A-Za-z0-9_-]{1,64}").matches(runKey)) {
        "mirra3dEvidenceRun must contain 1–64 ASCII letters, digits, underscores or hyphens"
    }
    val parent = context.noBackupFilesDir.canonicalFile.resolve("mirra3d-preservation")
    val directory = if (runKey == null) parent else parent.resolve(runKey)
    val marker = directory.resolve("manifest.json")
    require(marker.canonicalFile == marker.absoluteFile &&
        marker.canonicalPath.startsWith(parent.canonicalPath + File.separator))
    return marker
}
