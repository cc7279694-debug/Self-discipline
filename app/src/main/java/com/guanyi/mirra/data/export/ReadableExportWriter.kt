package com.guanyi.mirra.data.export

import com.guanyi.mirra.domain.backup.DataExportFormat
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ReadableExportLimits(
    val rowsPerTable: Long = 100_000,
    val totalRows: Long = 1_000_000,
    val rowBytes: Long = 1_048_576,
    val totalBytes: Long = 536_870_912,
    val elapsedMillis: Long = 600_000,
)

class ReadableExportException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** Does not close the caller-owned output; the service publishes only a completed file. */
class ReadableExportWriter(
    private val limits: ReadableExportLimits = ReadableExportLimits(),
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    init {
        require(limits.rowsPerTable >= 0 && limits.totalRows >= 0)
        require(limits.rowBytes > 0 && limits.totalBytes > 0 && limits.elapsedMillis >= 0)
    }

    fun write(
        format: DataExportFormat,
        source: ExportRowSource,
        output: OutputStream,
        createdAt: Long,
        tableCounts: Map<String, Long>,
        checkActive: () -> Unit = {},
    ) {
        val budget = ExportBudget(limits, monotonicMillis, checkActive)
        budget.check()
        validateCounts(tableCounts)
        val metadata = metadata(createdAt, tableCounts)
        val target = BudgetedOutputStream(output, budget, expanded = false)
        when (format) {
            DataExportFormat.JSON -> writeJson(source, target, metadata, tableCounts, budget)
            DataExportFormat.CSV_ZIP -> writeCsvZip(source, target, metadata, tableCounts, budget)
        }
        target.flush()
        budget.check()
    }

    private fun validateCounts(counts: Map<String, Long>) {
        if (counts.keys != ReadableExportSchema.tables.map { it.name }.toSet()) {
            throw ReadableExportException("Export count manifest must contain exactly the business table whitelist")
        }
        var total = 0L
        counts.values.forEach { count ->
            if (count < 0 || count > limits.rowsPerTable || count > limits.totalRows - total) {
                throw ReadableExportException("Export row count exceeds its limit")
            }
            total += count
        }
    }

    private fun metadata(createdAt: Long, counts: Map<String, Long>) = JsonObject(linkedMapOf(
        "appId" to JsonPrimitive("com.guanyi.mirra"),
        "appVersion" to JsonPrimitive("0.1.0"),
        "exportFormatVersion" to JsonPrimitive(ReadableExportSchema.EXPORT_FORMAT_VERSION),
        "roomSchemaVersion" to JsonPrimitive(ReadableExportSchema.ROOM_SCHEMA_VERSION),
        "createdAt" to JsonPrimitive(createdAt),
        "scope" to JsonPrimitive("authoritative_business_tables"),
        "isBackup" to JsonPrimitive(false),
        "includesImageBytes" to JsonPrimitive(false),
        "runtimeOwnershipIncluded" to JsonPrimitive(false),
        "units" to JsonObject(linkedMapOf(
            "timestamps" to JsonPrimitive("unix_epoch_milliseconds"),
            "durations" to JsonPrimitive("milliseconds"),
            "fileSizes" to JsonPrimitive("bytes"),
            "imageDimensions" to JsonPrimitive("pixels"),
        )),
        "tableCounts" to JsonObject(ReadableExportSchema.tables.associate { it.name to JsonPrimitive(counts.getValue(it.name)) }),
        "schema" to schema(),
    ))

    private fun schema() = JsonObject(ReadableExportSchema.tables.associate { table ->
        table.name to JsonArray(table.columns.map { column ->
            JsonObject(linkedMapOf(
                "name" to JsonPrimitive(column.name),
                "type" to JsonPrimitive(if (column.type == ExportValueType.TEXT) "text" else "integer"),
                "nullable" to JsonPrimitive(column.nullable),
                "unit" to (column.unit?.let { JsonPrimitive(it) } ?: JsonNull),
            ))
        })
    })

    private fun writeJson(
        source: ExportRowSource,
        output: OutputStream,
        metadata: JsonObject,
        counts: Map<String, Long>,
        budget: ExportBudget,
    ) {
        output.writeUtf8("{\"metadata\":")
        output.writeUtf8(metadata.toString())
        output.writeUtf8(",\"tables\":{")
        ReadableExportSchema.tables.forEachIndexed { index, table ->
            budget.check()
            if (index != 0) output.writeUtf8(",")
            output.writeUtf8(JsonPrimitive(table.name).toString() + ":[")
            var count = 0L
            source.forEachRow(table) { row ->
                budget.check()
                requireNextRow(table, count, counts, budget)
                validateRow(table, row, budget)
                val values = linkedMapOf<String, JsonElement>()
                table.columns.forEachIndexed { columnIndex, column ->
                    val value = row[columnIndex]
                    values[column.name] = when (value) {
                        null -> JsonNull
                        is String -> JsonPrimitive(value)
                        is Number -> JsonPrimitive(value.toLong())
                        else -> throw ReadableExportException("Unsupported export value")
                    }
                }
                val bytes = JsonObject(values).toString().toByteArray(Charsets.UTF_8)
                requireRowBytes(bytes.size)
                if (count != 0L) output.writeUtf8(",")
                output.write(bytes)
                count++
            }
            requireFinalCount(table, count, counts)
            output.writeUtf8("]")
        }
        output.writeUtf8("}}\n")
    }

    private fun writeCsvZip(
        source: ExportRowSource,
        output: OutputStream,
        metadata: JsonObject,
        counts: Map<String, Long>,
        budget: ExportBudget,
    ) {
        val zip = OwnedZipStream(output)
        val expanded = BudgetedOutputStream(zip, budget, expanded = true)
        try {
            fun entry(name: String, writeContent: () -> Unit) {
                budget.check()
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                writeContent()
                zip.closeEntry()
            }
            entry("metadata.json") { expanded.writeUtf8(metadata.toString()) }
            entry("schema.json") { expanded.writeUtf8(schema().toString()) }
            entry("README.txt") { expanded.writeUtf8(README) }
            ReadableExportSchema.tables.forEach { table ->
                entry("${table.name}.csv") {
                    expanded.writeUtf8(table.columns.joinToString(",") { it.name } + "\r\n")
                    var count = 0L
                    source.forEachRow(table) { row ->
                        budget.check()
                        requireNextRow(table, count, counts, budget)
                        validateRow(table, row, budget)
                        val bytes = buildString {
                            table.columns.forEachIndexed { index, column ->
                                if (index != 0) append(',')
                                val value = row[index]
                                if (value != null) {
                                    if (column.type == ExportValueType.TEXT) append(csvText(value as String))
                                    else append(value.toString())
                                }
                            }
                            append("\r\n")
                        }.toByteArray(Charsets.UTF_8)
                        requireRowBytes(bytes.size)
                        expanded.write(bytes)
                        count++
                    }
                    requireFinalCount(table, count, counts)
                }
            }
            budget.check()
            zip.finish()
            zip.flush()
        } finally {
            // Release our compressor without closing the caller's stream or finishing a failed export.
            zip.releaseCompression()
        }
    }

    private fun requireNextRow(table: ExportTable, count: Long, counts: Map<String, Long>, budget: ExportBudget) {
        if (count >= counts.getValue(table.name) || count >= limits.rowsPerTable) {
            throw ReadableExportException("Export row count changed for ${table.name}")
        }
        budget.admitRow()
    }

    private fun requireFinalCount(table: ExportTable, count: Long, counts: Map<String, Long>) {
        if (count != counts.getValue(table.name)) throw ReadableExportException("Export row count changed for ${table.name}")
    }

    private fun validateRow(table: ExportTable, row: List<Any?>, budget: ExportBudget) {
        if (row.size != table.columns.size) throw ReadableExportException("Export row width does not match ${table.name}")
        var rawBytes = 0L
        table.columns.forEachIndexed { index, column ->
            val value = row[index]
            if (value == null) {
                if (!column.nullable) throw ReadableExportException("Unexpected NULL in ${table.name}.${column.name}")
            } else when (column.type) {
                ExportValueType.TEXT -> {
                    if (value !is String) throw ReadableExportException("Expected text in ${table.name}.${column.name}")
                    rawBytes += validateUtf8Text(value, limits.rowBytes - rawBytes, budget)
                }
                ExportValueType.INTEGER -> {
                    if (value !is Byte && value !is Short && value !is Int && value !is Long) {
                        throw ReadableExportException("Expected integer in ${table.name}.${column.name}")
                    }
                    rawBytes += value.toString().length
                }
            }
            if (rawBytes > limits.rowBytes) throw ReadableExportException("Export row exceeds byte limit")
        }
    }

    private fun validateUtf8Text(value: String, remaining: Long, budget: ExportBudget): Long {
        if (value.length.toLong() > remaining) throw ReadableExportException("Export row exceeds byte limit")
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            if (index % 4096 == 0) budget.check()
            val char = value[index]
            when {
                Character.isHighSurrogate(char) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        throw ReadableExportException("Export text contains malformed Unicode")
                    }
                    bytes += 4
                    index++
                }
                Character.isLowSurrogate(char) -> throw ReadableExportException("Export text contains malformed Unicode")
                char.code <= 0x7f -> bytes++
                char.code <= 0x7ff -> bytes += 2
                else -> bytes += 3
            }
            if (bytes > remaining) throw ReadableExportException("Export row exceeds byte limit")
            index++
        }
        return bytes
    }

    private fun requireRowBytes(bytes: Int) {
        if (bytes.toLong() > limits.rowBytes) throw ReadableExportException("Encoded export row exceeds byte limit")
    }

    private fun csvText(value: String): String {
        val protected = if (requiresSpreadsheetPrefix(value)) "'$value" else value
        return "\"" + protected.replace("\"", "\"\"") + "\""
    }

    private fun requiresSpreadsheetPrefix(value: String): Boolean {
        var index = 0
        var leadingControl = false
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val control = Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT.toInt()
            val ignored = control || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
            if (!ignored) return leadingControl || codePoint in FORMULA_PREFIXES
            leadingControl = leadingControl || control
            index += Character.charCount(codePoint)
        }
        return leadingControl
    }

    private fun OutputStream.writeUtf8(value: String) = write(value.toByteArray(Charsets.UTF_8))

    private class OwnedZipStream(output: OutputStream) : ZipOutputStream(output) {
        fun releaseCompression() { def.end() }
    }

    private class ExportBudget(
        private val limits: ReadableExportLimits,
        private val clock: () -> Long,
        private val checkActive: () -> Unit,
    ) {
        private val startedAt = clock()
        private var rows = 0L
        private var outputBytes = 0L
        private var expandedBytes = 0L

        fun check() {
            checkActive()
            if (clock() - startedAt > limits.elapsedMillis) throw ReadableExportException("Export exceeds elapsed-time limit")
        }

        fun admitRow() {
            if (rows >= limits.totalRows) throw ReadableExportException("Export exceeds total-row limit")
            rows++
        }

        fun admitBytes(count: Int, expanded: Boolean) {
            check()
            val previous = if (expanded) expandedBytes else outputBytes
            if (count.toLong() > limits.totalBytes - previous) throw ReadableExportException("Export exceeds total-byte limit")
            if (expanded) expandedBytes += count else outputBytes += count
        }
    }

    private class BudgetedOutputStream(
        private val target: OutputStream,
        private val budget: ExportBudget,
        private val expanded: Boolean,
    ) : OutputStream() {
        override fun write(value: Int) {
            budget.admitBytes(1, expanded)
            target.write(value)
            budget.check()
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            budget.admitBytes(length, expanded)
            target.write(bytes, offset, length)
            budget.check()
        }

        override fun flush() {
            budget.check()
            target.flush()
            budget.check()
        }
    }

    private companion object {
        val FORMULA_PREFIXES = setOf('='.code, '+'.code, '-'.code, '@'.code, '＝'.code, '＋'.code, '－'.code, '＠'.code)
        val README = """
            Mirra readable CSV export, format version 1 / Room schema 4.
            UTF-8 CSV files use CRLF records; text cells are quoted and embedded quotes are doubled.
            An unquoted empty field means NULL; a quoted empty field means empty text. Spreadsheet import may collapse this distinction; use JSON when exact values are needed.
            Timestamps are Unix epoch milliseconds, durations are milliseconds, fileSize is bytes, dimensions are pixels; every column type, nullable flag and unit appears in schema.json.
            Original business IDs, relationships and historical facts are preserved. Abnormal Sessions remain history and are not recalculated as valid statistics.
            Risk app package names and label snapshots are included: these private records can identify applications used on the device.
            CSV text that starts with a formula marker after whitespace/control characters, or contains leading control/format characters, receives a single quote prefix. ASCII and fullwidth = + - @ markers are protected without normalization or truncation. Numeric cells, including negative integers, are unchanged.
            This protection is intended for initial spreadsheet import. Editing, re-saving or different spreadsheet software can alter it; it is not universal formula-injection immunity. JSON preserves original text without the single quote prefix.
            This is not a backup and cannot be used by Full Backup Restore. JPEG image bytes, image local paths, runtime ownership, derived search data, preferences and diagnostic logs are excluded. Only image metadata is present.
        """.trimIndent() + "\n"
    }
}
