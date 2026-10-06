package com.guanyi.mirra

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreservationEvidenceRunTest {
    private val target = ApplicationProvider.getApplicationContext<Context>()
    private val base = target.cacheDir.canonicalFile.resolve("preservation-path-test-only")
    private val context = object : ContextWrapper(target) {
        override fun getNoBackupFilesDir(): File = base
    }

    @Test fun missingKeyKeepsLegacyMarkerPath() {
        assertEquals(base.resolve("mirra3d-preservation/manifest.json"), preservationEvidenceMarker(context, null))
    }

    @Test fun explicitKeyUsesNewMarkerWithoutReusingLegacyOrAnotherRun() {
        val keyed = preservationEvidenceMarker(context, "storage-isolation-v2")
        assertEquals(base.resolve("mirra3d-preservation/storage-isolation-v2/manifest.json"), keyed)
        assertNotEquals(preservationEvidenceMarker(context, null), keyed)
        assertNotEquals(preservationEvidenceMarker(context, "another-run"), keyed)
    }

    @Test fun validKeyLengthBoundariesAreAccepted() {
        assertEquals("manifest.json", preservationEvidenceMarker(context, "A").name)
        assertEquals("A".repeat(64), preservationEvidenceMarker(context, "A".repeat(64)).parentFile!!.name)
    }

    @Test fun invalidKeyNeverFallsBackToLegacyMarker() {
        listOf("", " ", ".", "..", "../old", "a/b", "a\\b", "中文", "A".repeat(65)).forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { preservationEvidenceMarker(context, key) }
        }
    }
}
