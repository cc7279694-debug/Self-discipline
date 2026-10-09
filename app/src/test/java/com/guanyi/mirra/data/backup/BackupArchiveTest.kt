package com.guanyi.mirra.data.backup

import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.data.preferences.PortableAppPreferencesSnapshot
import com.guanyi.mirra.data.preferences.PreferenceSnapshotValue
import com.guanyi.mirra.navigation.TopLevelDestination
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BackupArchiveTest {
    private val root = Files.createTempDirectory("mirra-backup-test").toFile()
    private val prefs = PortableAppPreferencesSnapshot(
        PreferenceSnapshotValue(TopLevelDestination.Knowledge, true),
        PreferenceSnapshotValue(MirraThemeId.BLUE, false),
        PreferenceSnapshotValue(true, true), PreferenceSnapshotValue(false, false),
    )
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun roundTripPreservesExactDatabaseImageAndPreferencePresence() {
        val imageName = "11223344-5566-7788-99aa-bbccddeeff00.jpg"
        val dir = File(root, "snapshot").apply { mkdir() }
        File(dir, "database.sqlite").writeBytes(byteArrayOf(7, 9, 3))
        File(dir, "images").mkdir()
        File(dir, "images/$imageName").writeBytes(byteArrayOf(1, 4, 9, 16))
        val zip = File(root, "backup.zip")
        val metadata = BackupArchive().create(BackupSnapshot(dir, counts() + ("image_assets" to 1L), 1), zip, prefs, 42L)
        val restored = BackupArchive().validateAndExtract(zip, File(root, "unpacked"))
        assertEquals(42L, restored.metadata.createdAt)
        assertEquals(metadata, restored.metadata)
        assertEquals(prefs, restored.portablePrefs)
        assertArrayEquals(byteArrayOf(7, 9, 3), File(restored.root, "database.sqlite").readBytes())
        assertArrayEquals(byteArrayOf(1, 4, 9, 16), File(restored.root, "images/$imageName").readBytes())
    }

    @Test fun maliciousEntryNamesCannotEscapeOrAliasStaging() {
        listOf("../outside", "/outside", "images\\evil.jpg", "images/%2e%2e/x", "./metadata.json", "other.db").forEachIndexed { index, name ->
            val zip = zip("attack$index.zip", mapOf(name to "evil".toByteArray()))
            rejected { BackupArchive().validateAndExtract(zip, File(root, "stage$index")) }
        }
        assertFalse(File(root, "outside").exists())
    }

    @Test fun duplicateMetadataKeysAreRejectedBeforeLastWinsParsing() {
        val entries = validEntries()
        val metadata = entries.getValue("metadata.json").toString(Charsets.UTF_8)
        val duplicate = metadata.replaceFirst("{", "{\"appId\":\"com.guanyi.mirra\",")
        val zip = zip("duplicate.zip", entries + ("metadata.json" to duplicate.toByteArray()))
        rejected { BackupArchive().validateAndExtract(zip, File(root, "stage")) }
    }

    @Test fun actualExpandedBytesAreBoundedEvenForDeflatedEntry() {
        val zip = zip("large.zip", mapOf("database.sqlite" to ByteArray(2_000)))
        rejected { BackupArchive(BackupLimits(databaseBytes = 100)).validateAndExtract(zip, File(root, "stage")) }
    }

    @Test fun nonemptyStagingIsNeverOverwritten() {
        val stage = File(root, "stage").apply { mkdir() }
        File(stage, "precious").writeText("keep")
        rejected { BackupArchive().validateAndExtract(zip("x.zip", emptyMap()), stage) }
        assertEquals("keep", File(stage, "precious").readText())
    }

    @Test fun corruptedHashRejectsRestoringBytes() {
        val dir = File(root, "snapshot").apply { mkdir() }
        File(dir, "database.sqlite").writeText("original")
        File(dir, "images").mkdir()
        val source = File(root, "source.zip")
        BackupArchive().create(BackupSnapshot(dir, counts(), 0), source, prefs, 42)
        val entries = java.util.zip.ZipFile(source).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() } }
        val changed = zip("changed.zip", entries + ("database.sqlite" to "modified".toByteArray()))
        rejected { BackupArchive().validateAndExtract(changed, File(root, "stage")) }
    }

    @Test fun emptyBackupRetainsAllTwelveAuthoritativeCounts() {
        val dir = File(root, "snapshot").apply { mkdir() }
        File(dir, "database.sqlite").writeText("db")
        File(dir, "images").mkdir()
        val zip = File(root, "empty.zip")
        BackupArchive().create(BackupSnapshot(dir, counts(), 0), zip, prefs, 1)
        assertEquals(counts(), BackupArchive().validateAndExtract(zip, File(root, "stage")).metadata.tableCounts)
    }

    @Test fun unsupportedFormatSchemaAndOwnershipVersionFailClosed() {
        val entries = validEntries()
        listOf("backupFormatVersion" to 2, "roomSchemaVersion" to 3, "ownershipNormalizationVersion" to 0).forEachIndexed { index, (key, value) ->
            val original = entries.getValue("metadata.json").toString(Charsets.UTF_8)
            val changed = original.replace(Regex("\"$key\":\\d+"), "\"$key\":$value")
            rejected { BackupArchive().validateAndExtract(zip("v$index.zip", entries + ("metadata.json" to changed.toByteArray())), File(root, "v$index")) }
        }
    }

    @Test fun extraImagesAndMissingManifestFilesAreRejected() {
        val entries = validEntries()
        val image = "images/11223344-5566-7788-99aa-bbccddeeff00.jpg"
        rejected { BackupArchive().validateAndExtract(zip("extra.zip", entries + (image to byteArrayOf(1))), File(root, "extra")) }
        rejected { BackupArchive().validateAndExtract(zip("missing.zip", entries - "database.sqlite"), File(root, "missing")) }
    }

    @Test fun illegalAbsentEffectivePreferenceIsRejected() {
        val dbDir = File(root, "invalid-prefs").apply { mkdir() }
        File(dbDir, "database.sqlite").writeText("db")
        File(dbDir, "images").mkdir()
        rejected { BackupArchive().create(BackupSnapshot(dbDir, counts(), 0), File(root, "invalid.zip"), prefs.copy(themeId = PreferenceSnapshotValue(MirraThemeId.NIGHT, false)), 1) }
    }

    @Test fun falseCentralLocalOffsetIsRejectedInsteadOfTrustingStreamAlone() {
        val archive = zip("bad-offset.zip", validEntries())
        changeCentral(archive, "database.sqlite", 42, byteArrayOf(1, 0, 0, 0))
        rejected { BackupArchive().validateAndExtract(archive, File(root, "stage")) }
    }

    @Test fun centralUnixSymlinkModeIsRejectedBeforeExtraction() {
        val archive = zip("symlink.zip", validEntries())
        changeCentral(archive, "database.sqlite", 38, byteArrayOf(0, 0, 0, 0xa0.toByte()))
        rejected { BackupArchive().validateAndExtract(archive, File(root, "stage")) }
        assertFalse(File(root, "stage/database.sqlite").exists())
    }

    @Test fun malformedUtf8IsRejectedInsteadOfReplacingMetadataCharacters() {
        val entries = validEntries()
        val bytes = entries.getValue("metadata.json").copyOf()
        val needle = "0.1.0".toByteArray()
        val position = (0..bytes.size - needle.size).first { bytes.copyOfRange(it, it + needle.size).contentEquals(needle) }
        bytes[position] = 0xff.toByte()
        rejected { BackupArchive().validateAndExtract(zip("utf8.zip", entries + ("metadata.json" to bytes)), File(root, "stage")) }
    }

    private fun changeCentral(file: File, name: String, fieldOffset: Int, value: ByteArray) {
        val bytes = file.readBytes()
        val expected = name.toByteArray()
        val offset = (0..bytes.size - 46 - expected.size).first { start ->
            bytes[start] == 0x50.toByte() && bytes[start + 1] == 0x4b.toByte() && bytes[start + 2] == 1.toByte() && bytes[start + 3] == 2.toByte() &&
                bytes.copyOfRange(start + 46, start + 46 + expected.size).contentEquals(expected)
        }
        value.copyInto(bytes, offset + fieldOffset)
        file.writeBytes(bytes)
    }

    private fun validEntries(): Map<String, ByteArray> {
        val dir = File(root, "valid-${System.nanoTime()}").apply { mkdir() }
        File(dir, "database.sqlite").writeText("db")
        File(dir, "images").mkdir()
        val file = File(root, "valid-${System.nanoTime()}.zip")
        BackupArchive().create(BackupSnapshot(dir, counts(), 0), file, prefs, 1)
        return java.util.zip.ZipFile(file).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() } }
    }

    private fun counts() = listOf("learning_items", "study_intents", "study_sessions", "notes", "image_assets", "topics", "note_topic_cross_refs", "risk_apps", "session_focus_contexts", "session_risk_app_snapshots", "session_segments", "focus_events").associateWith { 0L }
    private fun zip(name: String, entries: Map<String, ByteArray>): File = File(root, name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() } }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Unsafe archive must be rejected") } catch (_: BackupValidationException) { }
    }
}
