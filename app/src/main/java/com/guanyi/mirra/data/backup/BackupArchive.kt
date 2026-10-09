package com.guanyi.mirra.data.backup

import com.guanyi.mirra.data.preferences.PortableAppPreferencesSnapshot
import com.guanyi.mirra.data.preferences.PreferenceSnapshotValue
import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.data.storage.ManagedImagePath
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*

class BackupArchive(private val limits: BackupLimits = BackupLimits()) {
    fun create(snapshot: BackupSnapshot, outputFile: File, portablePrefs: PortableAppPreferencesSnapshot, createdAt: Long): BackupMetadata = checked {
        val started = System.nanoTime()
        requireBackup(createdAt >= 0, "Invalid creation time")
        validateCounts(snapshot.tableCounts)
        requireBackup(snapshot.imageCount.toLong() == snapshot.tableCounts.getValue("image_assets"), "Snapshot image count disagreement")
        val preferences = encodePreferences(portablePrefs).toByteArray(Charsets.UTF_8)
        requireBackup(preferences.size <= limits.preferencesBytes, "Preferences exceed limit")
        val root = snapshot.root.canonicalFile
        val db = snapshot.databaseFile
        requireBackup(db.isFile && db.canonicalFile.parentFile == root && db.name == db.canonicalFile.name, "Missing snapshot database")
        val images = snapshot.imagesDirectory.listFiles().orEmpty().sortedBy { it.name }
        requireBackup(images.size == snapshot.imageCount && images.size + 3 <= limits.entries, "Image count exceeds limit or disagrees with snapshot")
        images.forEach { file ->
            requireBackup(ManagedImagePath.isValid("images/${file.name}") && file.isFile && file.canonicalFile.parentFile == snapshot.imagesDirectory.canonicalFile && file.canonicalFile.name == file.name, "Unsafe snapshot image")
        }
        val paths = listOf("database.sqlite") + images.map { "images/${it.name}" }
        requireBackup(paths.map { it.lowercase(Locale.ROOT) }.distinct().size == paths.size, "Duplicate snapshot paths")
        val payloads = paths.associateWith { File(root, it) }
        val records = payloads.map { (path, file) ->
            val maximum = entryLimit(path)
            requireBackup(file.length() in 1..maximum, "Snapshot file exceeds limit: $path")
            BackupFileRecord(path, file.length(), sha256(file, maximum, started))
        } + BackupFileRecord("preferences.json", preferences.size.toLong(), sha256(preferences))
        requireBackup(records.sumOf { it.bytes } <= limits.expandedBytes, "Snapshot exceeds total limit")
        val metadata = BackupMetadata(APP_ID, 1, 4, 1, createdAt, LinkedHashMap(snapshot.tableCounts), snapshot.imageCount, records)
        val metadataBytes = encodeMetadata(metadata).toByteArray(Charsets.UTF_8)
        requireBackup(metadataBytes.size <= limits.metadataBytes, "Metadata exceeds limit")
        requireBackup(!outputFile.exists() && outputFile.parentFile?.isDirectory == true, "Output must be a new file")
        FileOutputStream(outputFile).use { stream ->
            ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                writeBytes(zip, "metadata.json", metadataBytes)
                writeBytes(zip, "preferences.json", preferences)
                payloads.forEach { (path, file) ->
                    checkDeadline(started)
                    val crc = CRC32()
                    file.inputStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) { val count = input.read(buffer); if (count < 0) break; crc.update(buffer, 0, count); checkDeadline(started) }
                    }
                    zip.putNextEntry(storedEntry(path, file.length(), crc.value))
                    file.inputStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            checkDeadline(started); zip.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
                zip.finish()
                zip.flush()
                stream.fd.sync()
            }
        }
        requireBackup(outputFile.length() <= limits.archiveBytes, "Archive exceeds compressed limit")
        metadata
    }

    fun validateAndExtract(inputFile: File, newEmptyDir: File): ValidatedBackup = checked {
        val started = System.nanoTime()
        requireBackup(inputFile.isFile && inputFile.length() in 22..limits.archiveBytes, "Invalid archive size")
        requireBackup(!newEmptyDir.exists() || (newEmptyDir.isDirectory && newEmptyDir.listFiles()?.isEmpty() == true), "Staging must be empty")
        requireBackup(newEmptyDir.exists() || newEmptyDir.mkdirs(), "Cannot create staging")
        val root = newEmptyDir.canonicalFile
        val central = readCentralDirectory(inputFile, started)
        val names = HashSet<String>()
        var total = 0L
        ZipInputStream(inputFile.inputStream().buffered()).use { zip ->
            while (true) {
                checkDeadline(started)
                val entry = zip.nextEntry ?: break
                val name = entry.name
                requireBackup(isPayloadPath(name) || name == "metadata.json", "Non-whitelisted ZIP entry")
                requireBackup(!entry.isDirectory && names.add(name.lowercase(Locale.ROOT)), "Duplicate or directory ZIP entry")
                requireBackup(names.size <= limits.entries, "Too many ZIP entries")
                val declaration = central[name] ?: failBackup("ZIP local/central entry disagreement")
                requireBackup(entry.method == declaration.method, "ZIP method disagreement")
                val target = File(root, name)
                requireBackup(target.canonicalFile.path.startsWith(root.path + File.separator) && target.canonicalFile.name == target.name, "ZIP path escaped staging")
                val parent = target.parentFile ?: failBackup("Missing entry parent")
                requireBackup(parent.isDirectory || parent.mkdirs(), "Cannot create entry parent")
                requireBackup(target.createNewFile(), "ZIP target already exists")
                var length = 0L
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        length += count; total += count
                        requireBackup(length <= entryLimit(name) && total <= limits.expandedBytes, "ZIP expanded bytes exceed limit")
                        requireBackup(length <= maxOf(1, declaration.compressedBytes) * limits.compressionRatio, "ZIP compression ratio exceeds limit")
                        checkDeadline(started)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
                requireBackup(length == declaration.bytes && length > 0 && entry.crc == declaration.crc && entry.compressedSize == declaration.compressedBytes, "ZIP size/CRC disagreement")
                zip.closeEntry()
            }
        }
        requireBackup(names.size == central.size, "ZIP entries missing from stream")
        val metadataFile = File(root, "metadata.json")
        requireBackup(metadataFile.isFile, "Missing metadata")
        val metadata = decodeMetadata(strictJson(readUtf8(metadataFile)))
        val expected = metadata.files.map { it.path }.toSet() + "metadata.json"
        requireBackup(expected == central.keys && metadata.files.size + 1 == central.size, "Manifest and archive differ")
        metadata.files.forEach { record ->
            val file = File(root, record.path)
            requireBackup(file.length() == record.bytes && sha256(file, entryLimit(record.path), started) == record.sha256, "Backup hash or length mismatch: ${record.path}")
        }
        val preferences = decodePreferences(strictJson(readUtf8(File(root, "preferences.json"))))
        ValidatedBackup(root, metadata, preferences)
    }

    private fun validateCounts(counts: Map<String, Long>) {
        requireBackup(counts.keys == AUTHORITATIVE_TABLES.toSet(), "Wrong authoritative table inventory")
        requireBackup(counts.values.all { it in 0..limits.rowsPerTable } && counts.values.sum() <= limits.totalRows, "Table rows exceed limit")
    }

    private fun encodeMetadata(metadata: BackupMetadata): String = buildJsonObject {
        put("appId", metadata.appId); put("appVersion", metadata.appVersion)
        put("backupFormatVersion", metadata.backupFormatVersion); put("roomSchemaVersion", metadata.roomSchemaVersion)
        put("ownershipNormalizationVersion", metadata.ownershipNormalizationVersion); put("createdAt", metadata.createdAt)
        put("imageCount", metadata.imageCount)
        put("tableCounts", buildJsonObject { metadata.tableCounts.forEach { (key, value) -> put(key, value) } })
        put("files", buildJsonArray { metadata.files.forEach { file -> add(buildJsonObject { put("path", file.path); put("bytes", file.bytes); put("sha256", file.sha256) }) } })
    }.toString()

    private fun decodeMetadata(element: JsonElement): BackupMetadata {
        val value = objectFields(element, setOf("appId", "appVersion", "backupFormatVersion", "roomSchemaVersion", "ownershipNormalizationVersion", "createdAt", "imageCount", "tableCounts", "files"))
        requireBackup(string(value, "appId") == APP_ID && number(value, "backupFormatVersion") == 1L && number(value, "roomSchemaVersion") == 4L && number(value, "ownershipNormalizationVersion") == 1L, "Unsupported Mirra backup format/schema")
        val version = string(value, "appVersion")
        requireBackup(version.length in 1..128, "Invalid app version")
        val createdAt = number(value, "createdAt")
        requireBackup(createdAt >= 0, "Invalid creation time")
        val counts = (value["tableCounts"] as? JsonObject)?.mapValues { (_, elementValue) -> integer(elementValue) } ?: failBackup("Invalid table counts")
        validateCounts(counts)
        val fileArray = value["files"] as? JsonArray ?: failBackup("Invalid files manifest")
        requireBackup(fileArray.size <= limits.entries - 1, "Too many manifest files")
        val files = fileArray.map { fileValue ->
            val file = objectFields(fileValue, setOf("path", "bytes", "sha256"))
            val path = string(file, "path"); val bytes = number(file, "bytes"); val hash = string(file, "sha256")
            requireBackup(isPayloadPath(path) && bytes in 1..entryLimit(path) && HASH.matches(hash), "Invalid manifest file")
            BackupFileRecord(path, bytes, hash)
        }
        requireBackup(files.map { it.path.lowercase(Locale.ROOT) }.distinct().size == files.size && files.any { it.path == "database.sqlite" } && files.any { it.path == "preferences.json" }, "Duplicate or missing manifest file")
        val imageCount = number(value, "imageCount")
        requireBackup(imageCount in 0..(limits.entries - 3).toLong() && imageCount == files.count { it.path.startsWith("images/") }.toLong() && imageCount == counts.getValue("image_assets"), "Image count disagreement")
        requireBackup(files.sumOf { it.bytes } <= limits.expandedBytes, "Manifest exceeds total limit")
        return BackupMetadata(APP_ID, 1, 4, 1, createdAt, counts, imageCount.toInt(), files, version)
    }

    private fun encodePreferences(preferences: PortableAppPreferencesSnapshot): String {
        validatePreferences(preferences)
        return buildJsonObject {
            put("lastDestination", preference(preferences.lastDestination.value.storageValue, preferences.lastDestination.present))
            put("themeId", preference(preferences.themeId.value.storageValue, preferences.themeId.present))
            put("dndEnabled", preference(preferences.dndEnabled.value, preferences.dndEnabled.present))
            put("crossAppInterventionEnabled", preference(preferences.crossAppInterventionEnabled.value, preferences.crossAppInterventionEnabled.present))
        }.toString()
    }

    private fun preference(value: String, present: Boolean) = buildJsonObject { put("value", value); put("present", present) }
    private fun preference(value: Boolean, present: Boolean) = buildJsonObject { put("value", value); put("present", present) }

    private fun decodePreferences(element: JsonElement): PortableAppPreferencesSnapshot {
        val root = objectFields(element, setOf("lastDestination", "themeId", "dndEnabled", "crossAppInterventionEnabled"))
        fun field(name: String) = objectFields(root.getValue(name), setOf("value", "present"))
        val destination = field("lastDestination"); val theme = field("themeId")
        val dnd = field("dndEnabled"); val cross = field("crossAppInterventionEnabled")
        val value = PortableAppPreferencesSnapshot(
            PreferenceSnapshotValue(TopLevelDestination.entries.firstOrNull { it.storageValue == string(destination, "value") } ?: failBackup("Invalid destination"), boolean(destination, "present")),
            PreferenceSnapshotValue(MirraThemeId.entries.firstOrNull { it.storageValue == string(theme, "value") } ?: failBackup("Invalid theme"), boolean(theme, "present")),
            PreferenceSnapshotValue(boolean(dnd, "value"), boolean(dnd, "present")),
            PreferenceSnapshotValue(boolean(cross, "value"), boolean(cross, "present")),
        )
        validatePreferences(value)
        return value
    }

    private fun validatePreferences(value: PortableAppPreferencesSnapshot) {
        requireBackup(value.lastDestination.present || value.lastDestination.value == TopLevelDestination.Start, "Absent destination must retain default")
        requireBackup(value.themeId.present || value.themeId.value == MirraThemeId.BLUE, "Absent theme must retain default")
        requireBackup(value.dndEnabled.present || !value.dndEnabled.value, "Absent DND setting must retain default")
        requireBackup(value.crossAppInterventionEnabled.present || !value.crossAppInterventionEnabled.value, "Absent cross-app setting must retain default")
    }

    private data class ZipDeclaration(val method: Int, val bytes: Long, val compressedBytes: Long, val crc: Long)

    /** Java's ZIP stream does not expose Unix file mode; inspect central declarations before extraction. */
    private fun readCentralDirectory(file: File, started: Long): Map<String, ZipDeclaration> = RandomAccessFile(file, "r").use { input ->
        val tailSize = minOf(file.length(), 65_557L).toInt()
        val tail = ByteArray(tailSize)
        input.seek(file.length() - tailSize); input.readFully(tail)
        val offset = (tail.size - 22 downTo 0).firstOrNull { index -> unsigned32(tail, index) == 0x06054b50L && index + 22 + unsigned16(tail, index + 20) == tail.size } ?: failBackup("Missing ZIP end record")
        requireBackup(unsigned16(tail, offset + 4) == 0 && unsigned16(tail, offset + 6) == 0, "Multi-disk ZIP not supported")
        val count = unsigned16(tail, offset + 10)
        requireBackup(count == unsigned16(tail, offset + 8) && count in 3..limits.entries && count != 65_535, "Invalid ZIP entry count")
        val size = unsigned32(tail, offset + 12); val directoryOffset = unsigned32(tail, offset + 16)
        val endOffset = file.length() - tailSize + offset
        requireBackup(directoryOffset + size == endOffset && size <= limits.metadataBytes * 8 && directoryOffset > 0, "Invalid ZIP central directory")
        input.seek(directoryOffset)
        val entries = LinkedHashMap<String, ZipDeclaration>()
        val aliases = HashSet<String>()
        val localOffsets = HashSet<Long>()
        val localRanges = mutableListOf<Pair<Long, Long>>()
        var expanded = 0L
        repeat(count) {
            checkDeadline(started)
            val header = ByteArray(46); input.readFully(header)
            requireBackup(unsigned32(header, 0) == 0x02014b50L, "Invalid ZIP directory entry")
            val flags = unsigned16(header, 8); val method = unsigned16(header, 10)
            requireBackup(flags and 1 == 0 && flags and 0x40 == 0 && method in setOf(0, 8), "Encrypted/unsupported ZIP entry")
            val compressed = unsigned32(header, 20); val bytes = unsigned32(header, 24)
            val crc = unsigned32(header, 16)
            val nameLength = unsigned16(header, 28); val extraLength = unsigned16(header, 30); val commentLength = unsigned16(header, 32)
            val external = unsigned32(header, 38); val unixType = ((external ushr 16).toInt() and 0xf000)
            requireBackup(unixType == 0 || unixType == 0x8000, "Non-regular ZIP entry")
            requireBackup(external and 0x10 == 0L && unsigned16(header, 34) == 0, "Directory/multi-disk entry")
            requireBackup(nameLength in 1..200 && bytes != 0xffffffffL && compressed != 0xffffffffL, "ZIP64 not supported")
            val nameBytes = ByteArray(nameLength); input.readFully(nameBytes)
            requireBackup(nameBytes.all { (it.toInt() and 255) in 32..126 }, "Noncanonical ZIP entry encoding")
            val name = nameBytes.toString(Charsets.US_ASCII)
            requireBackup((isPayloadPath(name) || name == "metadata.json") && aliases.add(name.lowercase(Locale.ROOT)), "Unknown/duplicate ZIP entry")
            requireBackup(bytes in 1..entryLimit(name) && bytes <= maxOf(1, compressed) * limits.compressionRatio, "ZIP declared bytes exceed limit")
            val localOffset = unsigned32(header, 42)
            requireBackup(localOffset < directoryOffset && localOffsets.add(localOffset), "Overlapping ZIP local entries")
            requireBackup(input.filePointer + extraLength + commentLength <= endOffset, "Truncated ZIP directory")
            input.seek(input.filePointer + extraLength + commentLength)
            val resumeDirectory = input.filePointer
            input.seek(localOffset)
            val local = ByteArray(30); input.readFully(local)
            requireBackup(unsigned32(local, 0) == 0x04034b50L && unsigned16(local, 6) == flags && unsigned16(local, 8) == method, "ZIP local header disagreement")
            val localNameLength = unsigned16(local, 26); val localExtraLength = unsigned16(local, 28)
            requireBackup(localNameLength == nameLength, "ZIP local name disagreement")
            val localName = ByteArray(localNameLength); input.readFully(localName)
            requireBackup(localName.contentEquals(nameBytes), "ZIP local name disagreement")
            if (flags and 8 == 0) {
                requireBackup(unsigned32(local, 14) == crc && unsigned32(local, 18) == compressed && unsigned32(local, 22) == bytes, "ZIP local size/CRC disagreement")
            }
            val localEnd = input.filePointer + localExtraLength + compressed
            requireBackup(localEnd <= directoryOffset, "ZIP local data escaped payload")
            localRanges += localOffset to localEnd
            input.seek(resumeDirectory)
            entries[name] = ZipDeclaration(method, bytes, compressed, crc)
            expanded += bytes
            requireBackup(expanded <= limits.expandedBytes, "ZIP declared total exceeds limit")
        }
        requireBackup(input.filePointer == endOffset && localOffsets.minOrNull() == 0L, "Unexpected ZIP prefix or directory bytes")
        val sorted = localRanges.sortedBy { it.first }
        requireBackup(sorted.zipWithNext().all { (left, right) -> left.second <= right.first }, "Overlapping ZIP payloads")
        entries
    }

    private fun entryLimit(path: String): Long = when (path) {
        "metadata.json" -> limits.metadataBytes
        "preferences.json" -> limits.preferencesBytes
        "database.sqlite" -> limits.databaseBytes
        else -> limits.imageBytes
    }
    private fun isPayloadPath(path: String) = path == "database.sqlite" || path == "preferences.json" || ManagedImagePath.isValid(path)
    private fun checkDeadline(started: Long) = requireBackup((System.nanoTime() - started) / 1_000_000 <= limits.elapsedMillis, "Backup operation time limit exceeded")
    private fun sha256(file: File, limit: Long, started: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().use { input -> val buffer = ByteArray(BUFFER_BYTES); while (true) { val count = input.read(buffer); if (count < 0) break; total += count; requireBackup(total <= limit, "File exceeds limit"); checkDeadline(started); digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun writeBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) { val crc = CRC32().apply { update(bytes) }; zip.putNextEntry(storedEntry(name, bytes.size.toLong(), crc.value)); zip.write(bytes); zip.closeEntry() }
    private fun storedEntry(name: String, length: Long, checksum: Long) = ZipEntry(name).apply { method = ZipEntry.STORED; size = length; compressedSize = length; crc = checksum; time = 0 }
    private fun unsigned16(bytes: ByteArray, index: Int): Int = (bytes[index].toInt() and 255) or ((bytes[index + 1].toInt() and 255) shl 8)
    private fun unsigned32(bytes: ByteArray, index: Int): Long = (0..3).fold(0L) { value, byte -> value or ((bytes[index + byte].toLong() and 255) shl (8 * byte)) }
    private fun objectFields(value: JsonElement, fields: Set<String>): JsonObject { val obj = value as? JsonObject ?: failBackup("Expected JSON object"); requireBackup(obj.keys == fields, "Unknown or missing JSON fields"); return obj }
    private fun string(obj: JsonObject, key: String): String { val value = obj[key] as? JsonPrimitive ?: failBackup("Expected string"); requireBackup(value.isString, "Expected string"); return value.content }
    private fun integer(value: JsonElement): Long { val primitive = value as? JsonPrimitive ?: failBackup("Expected integer"); requireBackup(!primitive.isString, "Expected integer"); return primitive.longOrNull ?: failBackup("Invalid integer") }
    private fun number(obj: JsonObject, key: String) = integer(obj.getValue(key))
    private fun boolean(obj: JsonObject, key: String): Boolean { val value = obj[key] as? JsonPrimitive ?: failBackup("Expected boolean"); requireBackup(!value.isString, "Expected boolean"); return value.booleanOrNull ?: failBackup("Invalid boolean") }
    private fun strictJson(text: String): JsonElement { DuplicateKeyGuard(text).check(); return Json.parseToJsonElement(text) }
    private fun readUtf8(file: File): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(file.readBytes())).toString()
    private inline fun <T> checked(block: () -> T): T = try { block() } catch (failure: BackupValidationException) { throw failure } catch (failure: Exception) { throw BackupValidationException("Invalid or unreadable backup", failure) }
    private fun requireBackup(condition: Boolean, message: String) { if (!condition) failBackup(message) }
    private fun failBackup(message: String): Nothing = throw BackupValidationException(message)

    private companion object {
        const val APP_ID = "com.guanyi.mirra"
        const val BUFFER_BYTES = 65_536
        val HASH = Regex("[a-f0-9]{64}")
    }
}

/** Reject decoded duplicate keys, including escaped spellings, before Json's object map collapses them. */
private class DuplicateKeyGuard(private val source: String) {
    private var index = 0
    fun check() { value(0); whitespace(); require(index == source.length) { "Trailing JSON" } }
    private fun value(depth: Int) {
        require(depth <= 32) { "JSON nesting limit" }; whitespace()
        when (source.getOrNull(index)) {
            '{' -> {
                index++; whitespace(); val keys = HashSet<String>()
                if (take('}')) return
                while (true) {
                    whitespace(); val encoded = string()
                    val key = Json.parseToJsonElement(encoded).jsonPrimitive.content
                    require(keys.add(key)) { "Duplicate JSON key" }
                    whitespace(); require(take(':')) { "Missing JSON colon" }; value(depth + 1); whitespace()
                    if (take('}')) return
                    require(take(',')) { "Missing JSON comma" }
                }
            }
            '[' -> { index++; whitespace(); if (take(']')) return; while (true) { value(depth + 1); whitespace(); if (take(']')) return; require(take(',')) { "Missing array comma" } } }
            '"' -> string()
            else -> { val start = index; while (index < source.length && source[index] !in ",]} \t\r\n") index++; require(index > start) { "Missing JSON value" } }
        }
    }
    private fun string(): String {
        val start = index; require(take('"')) { "Missing JSON string" }
        while (index < source.length) {
            val character = source[index++]
            if (character == '"') return source.substring(start, index)
            require(character >= ' ') { "Control character in JSON" }
            if (character == '\\') { require(index < source.length); index++; }
        }
        error("Unterminated JSON string")
    }
    private fun whitespace() { while (source.getOrNull(index) in listOf(' ', '\t', '\r', '\n')) index++ }
    private fun take(character: Char): Boolean = if (source.getOrNull(index) == character) { index++; true } else false
}

internal val AUTHORITATIVE_TABLES = listOf("learning_items", "study_intents", "study_sessions", "notes", "image_assets", "topics", "note_topic_cross_refs", "risk_apps", "session_focus_contexts", "session_risk_app_snapshots", "session_segments", "focus_events")
