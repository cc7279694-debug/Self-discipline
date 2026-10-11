package com.guanyi.mirra.data.wearable

import android.content.Context
import android.util.AtomicFile
import com.guanyi.mirra.domain.wearable.WearableSettingsStore
import java.io.File

/** A device-local preference. It is never one of Full Backup v1's four portable settings. */
class WearableLocalSettings(context: Context) : WearableSettingsStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "wearable-companion-enabled"))
    @Volatile private var selected = try { file.openRead().use { it.read() == 1 } } catch (_: Exception) { false }
    override val enabled get() = selected
    @Synchronized override fun setEnabled(value: Boolean) {
        val output = file.startWrite()
        try { output.write(if (value) 1 else 0); file.finishWrite(output); selected = value }
        catch (failure: Exception) { file.failWrite(output); throw failure }
    }
}
