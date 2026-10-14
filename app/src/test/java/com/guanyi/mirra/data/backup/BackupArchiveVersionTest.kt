package com.guanyi.mirra.data.backup

import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.data.preferences.PortableAppPreferencesSnapshot
import com.guanyi.mirra.data.preferences.PreferenceSnapshotValue
import com.guanyi.mirra.navigation.TopLevelDestination
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BackupArchiveVersionTest {
    private val root = Files.createTempDirectory("mirra-backup-version").toFile()
    private val legacyTables = listOf("learning_items", "study_intents", "study_sessions", "notes", "image_assets",
        "topics", "note_topic_cross_refs", "risk_apps", "session_focus_contexts", "session_risk_app_snapshots",
        "session_segments", "focus_events")
    private val prefs = PortableAppPreferencesSnapshot(
        PreferenceSnapshotValue(TopLevelDestination.Start, false), PreferenceSnapshotValue(MirraThemeId.BLUE, false),
        PreferenceSnapshotValue(false, false), PreferenceSnapshotValue(false, false))
    @After fun close() { root.deleteRecursively() }

    @Test fun newExportExplicitlyDeclaresFormatTwoSchemaFiveAndIndependentRecords() {
        val archive = newArchive()
        val restored = BackupArchive().validateAndExtract(archive, File(root, "new-unpacked"))
        assertEquals(2, restored.metadata.backupFormatVersion)
        assertEquals(5, restored.metadata.roomSchemaVersion)
        assertEquals((legacyTables + "wearable_completed_records").toSet(), restored.metadata.tableCounts.keys)
        assertEquals(0L, restored.metadata.tableCounts.getValue("wearable_completed_records"))
    }

    @Test fun frozenFormatOneSchemaFourInventoryRemainsReadable() {
        val archive = rewrite(newArchive(), 1, 4, legacyTables.toSet(), "legacy.zip")
        val restored = BackupArchive().validateAndExtract(archive, File(root, "legacy-unpacked"))
        assertEquals(1, restored.metadata.backupFormatVersion)
        assertEquals(4, restored.metadata.roomSchemaVersion)
        assertEquals(legacyTables.toSet(), restored.metadata.tableCounts.keys)
    }

    @Test fun unsupportedPairsAndMissingNewTableStayRejected() {
        val archive = newArchive()
        listOf(1 to 5, 2 to 4, 2 to 6, 3 to 5).forEachIndexed { index, pair ->
            rejected { BackupArchive().validateAndExtract(rewrite(archive, pair.first, pair.second,
                (legacyTables + "wearable_completed_records").toSet(), "bad-$index.zip"), File(root, "bad-$index")) }
        }
        rejected { BackupArchive().validateAndExtract(rewrite(archive, 2, 5, legacyTables.toSet(), "missing.zip"), File(root, "missing")) }
    }

    @Test fun declaredApplicationVersionIsRetainedRatherThanReplacedWithTheFixtureDefault() {
        val validated = BackupArchive().validateAndExtract(newArchive("1.4.0"), File(root, "version-unpacked"))
        assertEquals("1.4.0", validated.metadata.appVersion)
    }

    private fun newArchive(appVersion: String = "0.1.0"): File {
        val snapshot = File(root, "snapshot-${System.nanoTime()}").apply { mkdir() }
        File(snapshot, "database.sqlite").writeText("opaque bytes: database verification belongs to the native snapshot boundary")
        File(snapshot, "images").mkdir()
        return File(root, "new-${System.nanoTime()}.zip").also {
            BackupArchive(appVersion = appVersion).create(BackupSnapshot(snapshot, (legacyTables + "wearable_completed_records").associateWith { 0L }, 0), it, prefs, 1)
        }
    }

    private fun rewrite(source: File, format: Int, schema: Int, tables: Set<String>, name: String): File {
        val entries = ZipFile(source).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() } }
        val metadata = Json.parseToJsonElement(entries.getValue("metadata.json").toString(Charsets.UTF_8)).jsonObject
        val changed = buildJsonObject {
            metadata.forEach { (key, value) -> put(key, when (key) {
                "backupFormatVersion" -> JsonPrimitive(format)
                "roomSchemaVersion" -> JsonPrimitive(schema)
                "tableCounts" -> JsonObject(value.jsonObject.filterKeys { it in tables })
                else -> value
            }) }
        }.toString().toByteArray(Charsets.UTF_8)
        return File(root, name).also { destination -> ZipOutputStream(destination.outputStream()).use { zip ->
            (entries + ("metadata.json" to changed)).forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
        } }
    }

    private fun rejected(work: () -> Unit) {
        try { work(); fail("Unsupported backup pair or inventory must be rejected") } catch (_: BackupValidationException) { }
    }
}
