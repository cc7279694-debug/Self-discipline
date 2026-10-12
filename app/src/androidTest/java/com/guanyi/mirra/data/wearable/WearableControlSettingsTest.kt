package com.guanyi.mirra.data.wearable

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WearableControlSettingsTest {
    @Test fun controlConsentDefaultsOffAndPersistsOnlyInIsolatedNoBackupDirectory() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "control-settings-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
        try {
            val first = WearableLocalSettings(isolated)
            assertFalse(first.controlEnabled); assertFalse(first.enabled)
            first.setEnabled(true); first.setControlEnabled(true)
            val reopened = WearableLocalSettings(isolated)
            assertTrue(reopened.controlEnabled); assertTrue(reopened.enabled)
            reopened.setControlEnabled(false)
            assertFalse(WearableLocalSettings(isolated).controlEnabled)
            assertTrue(WearableLocalSettings(isolated).enabled)
            assertEquals(setOf("wearable-companion-enabled", "wearable-control-enabled"), directory.list()!!.toSet())
        } finally { directory.deleteRecursively() }
    }
}
