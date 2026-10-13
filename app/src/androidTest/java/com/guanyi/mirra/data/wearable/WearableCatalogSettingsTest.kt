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
class WearableCatalogSettingsTest {
    @Test fun expandedConsentAndBookSelectionAreSeparateNoBackupFilesAndDoNotChangeOldConsent() {
        isolated { context, directory ->
            val first = WearableLocalSettings(context)
            assertFalse(first.catalogEnabled); assertTrue(first.catalogSelectedIds.isEmpty())
            first.setEnabled(true); first.setControlEnabled(true)
            first.setCatalogEnabled(true); first.setCatalogSelectedIds(listOf("other-b", "other-a"))
            val reopened = WearableLocalSettings(context)
            assertTrue(reopened.enabled); assertTrue(reopened.controlEnabled); assertTrue(reopened.catalogEnabled)
            assertEquals(listOf("other-a", "other-b"), reopened.catalogSelectedIds)
            reopened.setCatalogEnabled(false)
            assertFalse(WearableLocalSettings(context).catalogEnabled)
            assertTrue(WearableLocalSettings(context).enabled)
            assertEquals(setOf("wearable-companion-enabled", "wearable-control-enabled", "wearable-catalog-enabled", "wearable-catalog-books-v1"), directory.list()!!.toSet())
        }
    }
    @Test fun invalidSelectionNeverOverwritesLastConfirmedLocalSelection() {
        isolated { context, _ ->
            val settings = WearableLocalSettings(context); settings.setCatalogSelectedIds(listOf("safe"))
            listOf(listOf("duplicate", "duplicate"), listOf("bad.id"), (1..12).map { "book-$it" }).forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) { settings.setCatalogSelectedIds(invalid) }
                assertEquals(listOf("safe"), WearableLocalSettings(context).catalogSelectedIds)
            }
        }
    }
    private fun isolated(block: (Context, File) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "catalog-settings-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
        try { block(context, directory) } finally { directory.deleteRecursively() }
    }
}
