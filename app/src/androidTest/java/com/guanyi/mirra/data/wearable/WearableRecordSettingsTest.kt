package com.guanyi.mirra.data.wearable

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class WearableRecordSettingsTest {
    @Test fun recordConsentIsDefaultOffDeviceLocalAndExactOnReopen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "record-settings-test-" + UUID.randomUUID()).also { check(it.mkdirs()) }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        try {
            assertFalse(WearableLocalSettings(isolated).recordSyncEnabled)
            val settings = WearableLocalSettings(isolated)
            settings.setRecordSyncEnabled(true)
            assertTrue(settings.recordSyncEnabled); assertTrue(WearableLocalSettings(isolated).recordSyncEnabled)
            settings.setRecordSyncEnabled(false)
            assertFalse(settings.recordSyncEnabled); assertFalse(WearableLocalSettings(isolated).recordSyncEnabled)
            File(root, "wearable-record-sync-enabled").writeBytes(byteArrayOf(1, 1))
            assertFalse(WearableLocalSettings(isolated).recordSyncEnabled)
        } finally { check(root.parentFile == context.cacheDir && root.name.startsWith("record-settings-test-")); root.deleteRecursively() }
    }
}
