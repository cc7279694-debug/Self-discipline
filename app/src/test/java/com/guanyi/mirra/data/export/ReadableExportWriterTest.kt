package com.guanyi.mirra.data.export

import com.guanyi.mirra.domain.backup.DataExportFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReadableExportWriterTest {
    private val tableNames = listOf(
        "learning_items", "study_intents", "study_sessions", "notes", "image_assets", "topics",
        "note_topic_cross_refs", "risk_apps", "session_focus_contexts", "session_risk_app_snapshots",
        "session_segments", "focus_events",
    )

    @Test fun emptyJsonStillContainsEveryAuthoritativeTableAndReadableMetadata() {
        val document = Json.parseToJsonElement(export(DataExportFormat.JSON)).jsonObject
        assertEquals(tableNames.toSet(), document.getValue("tables").jsonObject.keys)
        assertTrue(document.getValue("tables").jsonObject.values.all { it.jsonArray.isEmpty() })
        val metadata = document.getValue("metadata").jsonObject
        assertEquals("1", metadata.getValue("exportFormatVersion").jsonPrimitive.content)
        assertEquals("4", metadata.getValue("roomSchemaVersion").jsonPrimitive.content)
        assertEquals("1700000000000", metadata.getValue("createdAt").jsonPrimitive.content)
        assertEquals("false", metadata.getValue("isBackup").jsonPrimitive.content)
        assertEquals("false", metadata.getValue("includesImageBytes").jsonPrimitive.content)
        assertEquals("authoritative_business_tables", metadata.getValue("scope").jsonPrimitive.content)
        val units = metadata.getValue("units").jsonObject
        assertEquals("unix_epoch_milliseconds", units.getValue("timestamps").jsonPrimitive.content)
        assertEquals("milliseconds", units.getValue("durations").jsonPrimitive.content)
    }

    @Test fun whitelistExportsSafeHistoryButOmitsDerivedAndRuntimeOwnershipFields() {
        val expected = mapOf(
            "learning_items" to "id,name,status,totalPages,currentPage,mainlineSlot,firstAction,createdAt,updatedAt,completedAt",
            "study_intents" to "id,learningItemId,createdAt,transitionedAt,convertedAt,endedAt,outcome",
            "study_sessions" to "id,learningItemId,intentId,startedAt,stableStartedAt,endedAt,startPage,currentPage,endPage,endType,generatedSummary",
            "notes" to "id,learningItemId,sessionId,semanticType,content,pageNumber,createdAt,updatedAt",
            "image_assets" to "id,noteId,caption,width,height,fileSize,createdAt",
            "topics" to "id,name,createdAt",
            "note_topic_cross_refs" to "noteId,topicId",
            "risk_apps" to "packageName,labelSnapshot,createdAt,updatedAt",
            "session_focus_contexts" to "sessionId,snapshotVersion,pollIntervalMillis,riskEventDedupeMillis,riskConfirmMillis,secondFrictionMillis,thirdPlusFrictionMillis,replyAllowanceMillis,researchAllowanceMillis,temporaryTaskAllowanceMillis,casualAllowanceMillis,allowanceExtensionMillis,maxAllowanceExtensions,shortBreakMillis,longBreakMillis,recoveryStableMillis,stableStartMillis,deepFocusMillis,deepFocusScreenOffMillis,heartbeatMillis,maxObservationGapMillis,usageAccessAtStart,dndAccessAtStart,overlayAccessAtStart,notificationAccessAtStart,monitoringStatus,monitoringLostAt,closeoutState,requestedEndPage,closeoutStartedAt,lastHeartbeatAt,createdAt,updatedAt",
            "session_risk_app_snapshots" to "sessionId,packageName,labelSnapshot",
            "session_segments" to "id,sessionId,type,startedAt,endedAt,packageName,reason,plannedEndAt,extensionCount,relatedSegmentId",
            "focus_events" to "id,sessionId,type,occurredAt,packageName,segmentId,deliveryChannel",
        )
        val entries = unzip(exportBytes(DataExportFormat.CSV_ZIP))
        assertEquals(expected.keys, entries.keys.filter { it.endsWith(".csv") }.map { it.removeSuffix(".csv") }.toSet())
        expected.forEach { (table, columns) ->
            assertEquals(columns + "\r\n", entries.getValue("$table.csv"))
        }
        val schema = Json.parseToJsonElement(entries.getValue("schema.json")).jsonObject
        expected.forEach { (table, columns) ->
            assertEquals(columns.split(','), schema.getValue(table).jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        }
        assertFalse(entries.keys.any { it.endsWith(".jpg") || it.endsWith(".sqlite") || it.contains("search") })
        val keys = schema.values.flatMap { it.jsonArray.map { field -> field.jsonObject.getValue("name").jsonPrimitive.content } }
        assertFalse(keys.any { it in setOf("activeSlot", "localPath", "priorDndInterruptionFilter", "dndRuleId", "dndLifecycle") })
    }

    @Test fun schemaDeclaresTypesNullabilityAndUnitsForEveryColumn() {
        assertEquals(tableNames, ReadableExportSchema.tables.map { it.name })
        val schema = Json.parseToJsonElement(unzip(exportBytes(DataExportFormat.CSV_ZIP)).getValue("schema.json")).jsonObject
        schema.values.flatMap { it.jsonArray }.forEach { field ->
            val column = field.jsonObject
            assertTrue(column.getValue("type").jsonPrimitive.content in setOf("text", "integer"))
            assertTrue(column.getValue("nullable").jsonPrimitive.content in setOf("true", "false"))
            assertTrue(column.containsKey("unit"))
        }
        fun field(table: String, name: String) = schema.getValue(table).jsonArray.first {
            it.jsonObject.getValue("name").jsonPrimitive.content == name
        }.jsonObject
        assertEquals("unix_epoch_milliseconds", field("study_sessions", "stableStartedAt").getValue("unit").jsonPrimitive.content)
        assertEquals("true", field("study_sessions", "stableStartedAt").getValue("nullable").jsonPrimitive.content)
        assertEquals("milliseconds", field("session_focus_contexts", "riskConfirmMillis").getValue("unit").jsonPrimitive.content)
        assertEquals("bytes", field("image_assets", "fileSize").getValue("unit").jsonPrimitive.content)
        assertEquals("pixels", field("image_assets", "width").getValue("unit").jsonPrimitive.content)
        assertEquals("boolean_0_1", field("session_focus_contexts", "dndAccessAtStart").getValue("unit").jsonPrimitive.content)
    }

    @Test fun projectedColumnTypesAndNullabilityMatchFrozenRoomSchemaFour() {
        val relative = "schemas/com.guanyi.mirra.data.local.MirraDatabase/4.json"
        val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("Frozen Room v4 test fixture is missing")
        val entities = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
            .getValue("entities").jsonArray.associate { it.jsonObject.getValue("tableName").jsonPrimitive.content to it.jsonObject }
        assertEquals(tableNames, ReadableExportSchema.tables.map { it.name })
        ReadableExportSchema.tables.forEach { table ->
            val frozen = entities.getValue(table.name).getValue("fields").jsonArray.associate {
                it.jsonObject.getValue("columnName").jsonPrimitive.content to it.jsonObject
            }
            table.columns.forEach { column ->
                val field = frozen.getValue(column.name)
                assertEquals(field.getValue("affinity").jsonPrimitive.content, column.type.name)
                // Room omits its default false value for nullable fields in exported schemas.
                val notNull = field["notNull"]?.jsonPrimitive?.content == "true"
                assertEquals(!notNull, column.nullable)
            }
        }
    }

    @Test fun jsonPreservesNullZeroOriginalIdsAndUnicodeWithoutFormulaRewriting() {
        val content = "  =中文,\"引号\"\r\n下一行 😀\t\u0000"
        val rows = mapOf("notes" to listOf(noteRow("original-note-id", content)))
        val note = Json.parseToJsonElement(export(DataExportFormat.JSON, rows)).jsonObject
            .getValue("tables").jsonObject.getValue("notes").jsonArray.single().jsonObject
        assertEquals("original-note-id", note.getValue("id").jsonPrimitive.content)
        assertEquals(content, note.getValue("content").jsonPrimitive.content)
        assertEquals(JsonNull, note.getValue("sessionId"))
        assertEquals(JsonNull, note.getValue("pageNumber"))
        assertEquals("0", note.getValue("createdAt").jsonPrimitive.content)
        assertFalse(note.getValue("createdAt").jsonPrimitive.isString)
    }

    @Test fun jsonIncludesRiskPackagesLabelsAndAbnormalHistoryWithoutRecalculatingFacts() {
        val rows = mapOf(
            "risk_apps" to listOf(listOf("com.example.risk", "个人风险应用", 1L, 2L)),
            "session_risk_app_snapshots" to listOf(listOf("session-original", "com.example.risk", "当时的名称")),
            "study_sessions" to listOf(listOf("session-original", "book-original", "intent-original", 1L, null, 2L, 3L, 4L, null, "ABNORMAL", "原始总结")),
        )
        val tables = Json.parseToJsonElement(export(DataExportFormat.JSON, rows)).jsonObject.getValue("tables").jsonObject
        assertEquals("com.example.risk", tables.getValue("risk_apps").jsonArray.single().jsonObject.getValue("packageName").jsonPrimitive.content)
        assertEquals("当时的名称", tables.getValue("session_risk_app_snapshots").jsonArray.single().jsonObject.getValue("labelSnapshot").jsonPrimitive.content)
        val session = tables.getValue("study_sessions").jsonArray.single().jsonObject
        assertEquals("ABNORMAL", session.getValue("endType").jsonPrimitive.content)
        assertEquals("原始总结", session.getValue("generatedSummary").jsonPrimitive.content)
        assertEquals(JsonNull, session.getValue("stableStartedAt"))
    }

    @Test fun csvZipContainsTwelveTablesMetadataSchemaAndDocumentedNullAndSafetyRules() {
        val entries = unzip(exportBytes(DataExportFormat.CSV_ZIP))
        assertEquals(tableNames.map { "$it.csv" }.toSet() + setOf("metadata.json", "schema.json", "README.txt"), entries.keys)
        val readme = entries.getValue("README.txt")
        assertTrue(readme.contains("UTF-8"))
        assertTrue(readme.contains("not a backup"))
        assertTrue(readme.contains("NULL"))
        assertTrue(readme.contains("single quote"))
        assertTrue(readme.contains("JPEG"))
        assertTrue(readme.contains("risk", ignoreCase = true))
    }

    @Test fun csvEscapesCommasQuotesNewlinesAndDistinguishesNullFromEmptyText() {
        val rows = mapOf("notes" to listOf(noteRow("n-中文", "中文,\"引用\"\r\n下一行 😀"), noteRow("empty", "")))
        val csv = unzip(exportBytes(DataExportFormat.CSV_ZIP, rows)).getValue("notes.csv")
        assertTrue(csv.contains("\"n-中文\",\"book-id\",,\"THOUGHT\",\"中文,\"\"引用\"\"\r\n下一行 😀\",,0,1\r\n"))
        assertTrue(csv.contains("\"empty\",\"book-id\",,\"THOUGHT\",\"\",,0,1\r\n"))
    }

    @Test fun csvNeutralizesAllFormulaStartersAfterWhitespaceControlsAndInvisiblePrefixes() {
        val inputs = listOf(
            "=SUM(1,2)", "+cmd", "-5", "@A1", " \t=HYPERLINK(\"bad\")", "\r\n+CMD",
            "\uFEFF\u200B-1", "\u0000\u000B@A1", "\u00A0\u202F=1", "\u200E=1",
            "＝1", " ＋CMD", "\uFEFF－1", "\u200B＠A1",
        )
        inputs.forEach { content ->
            val csv = unzip(exportBytes(DataExportFormat.CSV_ZIP, mapOf("notes" to listOf(noteRow("id", content))))).getValue("notes.csv")
            assertTrue("Formula must be neutralized: $content", csv.contains("\"'" + content.replace("\"", "\"\"") + "\""))
        }
    }

    @Test fun csvConservativelyPrefixesLeadingControlsEvenBeforeOrdinaryText() {
        listOf("\tplain", "\r\nplain", "\u0000plain", "\uFEFFplain", " \u200Bplain").forEach { content ->
            val csv = unzip(exportBytes(DataExportFormat.CSV_ZIP, mapOf("notes" to listOf(noteRow("id", content))))).getValue("notes.csv")
            assertTrue(csv.contains("\"'$content\""))
        }
    }

    @Test fun csvPreservesSafeTextAndNumericNegativesWithoutTruncation() {
        val content = "普通文字 -5\n" + "界".repeat(5_000)
        val rows = mapOf("notes" to listOf(noteRow("id", content).toMutableList().apply { this[5] = -5L }))
        val csv = unzip(exportBytes(DataExportFormat.CSV_ZIP, rows)).getValue("notes.csv")
        assertTrue(csv.contains("\"$content\",-5,0,1\r\n"))
        assertFalse(csv.contains("\"'$content"))
    }

    @Test fun jsonStreamsRowsBeforeRequestingTheNextBatch() {
        val output = ByteArrayOutputStream()
        var emitted = 0
        val source = ExportRowSource { table, consume ->
            if (table.name == "notes") repeat(20_000) { index ->
                if (index == 1_000) assertTrue("Rows must be emitted before source completes", output.size() > 50_000)
                consume(noteRow("n-$index", "streamed $index"))
                emitted++
            }
        }
        ReadableExportWriter().write(DataExportFormat.JSON, source, output, 1L, counts(mapOf("notes" to 20_000L)))
        assertEquals(20_000, emitted)
        val notes = Json.parseToJsonElement(output.toString("UTF-8")).jsonObject.getValue("tables").jsonObject.getValue("notes").jsonArray
        assertEquals(20_000, notes.size)
        assertEquals("n-19999", notes.last().jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test fun sourceCanReuseItsRowBufferBecauseWriterConsumesItImmediately() {
        val source = ExportRowSource { table, consume ->
            if (table.name == "notes") {
                val row = noteRow("first", "first content").toMutableList()
                consume(row)
                row[0] = "second"
                row[4] = "second content"
                consume(row)
                row[4] = "must not appear"
            }
        }
        val output = ByteArrayOutputStream()
        ReadableExportWriter().write(DataExportFormat.JSON, source, output, 1L, counts(mapOf("notes" to 2L)))
        val notes = Json.parseToJsonElement(output.toString("UTF-8")).jsonObject.getValue("tables").jsonObject.getValue("notes").jsonArray
        assertEquals("first content", notes[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("second content", notes[1].jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test fun writerLeavesCallerOutputOpenAfterBothFormats() {
        DataExportFormat.entries.forEach { format ->
            val output = object : ByteArrayOutputStream() { var closed = false; override fun close() { closed = true } }
            ReadableExportWriter().write(format, source(emptyMap()), output, 1L, counts())
            assertFalse(output.closed)
            output.write(123)
        }
    }

    @Test fun invalidRowWidthFailsInsteadOfDroppingOrInventingColumns() {
        rejected { exportBytes(DataExportFormat.JSON, mapOf("notes" to listOf(noteRow("id", "text").dropLast(1)))) }
        rejected { exportBytes(DataExportFormat.CSV_ZIP, mapOf("notes" to listOf(noteRow("id", "text") + "extra"))) }
    }

    @Test fun invalidNullAndWrongValueTypesFailRatherThanCoercingFacts() {
        val valid = noteRow("id", "content")
        listOf(0 to null, 4 to 7L, 5 to "42", 5 to 4.5, 6 to false).forEach { (index, value) ->
            rejected { exportBytes(DataExportFormat.JSON, mapOf("notes" to listOf(valid.toMutableList().apply { this[index] = value }))) }
        }
    }

    @Test fun malformedUnicodeFailsRatherThanSilentlyReplacingUserText() {
        rejected { exportBytes(DataExportFormat.JSON, mapOf("notes" to listOf(noteRow("id", "broken \uD800")))) }
        rejected { exportBytes(DataExportFormat.CSV_ZIP, mapOf("notes" to listOf(noteRow("id", "broken \uDC00")))) }
    }

    @Test fun countManifestMustMatchExactWhitelistAndNonnegativeCounts() {
        rejected { writeWithCounts(counts() - "notes") }
        rejected { writeWithCounts(counts() + ("search_fts" to 1L)) }
        rejected { writeWithCounts(counts(mapOf("notes" to -1L))) }
    }

    @Test fun actualRowCountMustMatchManifest() {
        rejected { writeWithCounts(counts(), mapOf("notes" to listOf(noteRow("id", "extra")))) }
        rejected { writeWithCounts(counts(mapOf("notes" to 1L))) }
    }

    @Test fun declaredRowsPerTableAndTotalRowsAreBoundedBeforeSourceIsRead() {
        val source = ExportRowSource { _, _ -> fail("Oversized manifest must fail before source access") }
        rejected {
            ReadableExportWriter(ReadableExportLimits(rowsPerTable = 1)).write(DataExportFormat.JSON, source, ByteArrayOutputStream(), 1L, counts(mapOf("notes" to 2L)))
        }
        rejected {
            ReadableExportWriter(ReadableExportLimits(totalRows = 1)).write(DataExportFormat.JSON, source, ByteArrayOutputStream(), 1L, counts(mapOf("notes" to 1L, "topics" to 1L)))
        }
    }

    @Test fun oneHugeUtf8OrEscapedRowFailsWithoutTruncatingIt() {
        DataExportFormat.entries.forEach { format ->
            rejected { exportBytes(format, mapOf("notes" to listOf(noteRow("id", "界".repeat(100)))), ReadableExportLimits(rowBytes = 256)) }
            rejected { exportBytes(format, mapOf("notes" to listOf(noteRow("id", "\"".repeat(200)))), ReadableExportLimits(rowBytes = 256)) }
        }
    }

    @Test fun totalOutputBytesAreBoundedForJsonAndCsvZipIncludingMetadata() {
        DataExportFormat.entries.forEach { format ->
            rejected { exportBytes(format, limits = ReadableExportLimits(totalBytes = 100)) }
        }
    }

    @Test fun csvExpandedBytesAreBoundedEvenWhenCompressionMakesArchiveSmall() {
        val rows = mapOf("notes" to listOf(noteRow("id", "a".repeat(50_000))))
        rejected { exportBytes(DataExportFormat.CSV_ZIP, rows, ReadableExportLimits(totalBytes = 20_000)) }
    }

    @Test fun monotonicTimeBudgetStopsBeforeAnotherRowIsRead() {
        var now = 0L
        var reachedSecondRow = false
        val source = ExportRowSource { table, consume ->
            if (table.name == "notes") {
                now = 11L
                consume(noteRow("first", "first"))
                reachedSecondRow = true
            }
        }
        rejected {
            ReadableExportWriter(ReadableExportLimits(elapsedMillis = 10), { now }).write(DataExportFormat.JSON, source, ByteArrayOutputStream(), 1L, counts(mapOf("notes" to 1L)))
        }
        assertFalse(reachedSecondRow)
    }

    @Test fun cancellationBeforeAndDuringRowsPropagatesOriginalException() {
        val cancellation = CancellationException("cancelled")
        try {
            ReadableExportWriter().write(DataExportFormat.JSON, source(emptyMap()), ByteArrayOutputStream(), 1L, counts()) { throw cancellation }
            fail("Cancellation must abort")
        } catch (actual: CancellationException) { assertTrue(actual === cancellation) }
        var cancel = false
        val source = ExportRowSource { table, consume ->
            if (table.name == "notes") {
                consume(noteRow("first", "first"))
                cancel = true
                consume(noteRow("second", "second"))
                fail("Cancellation must stop the source callback")
            }
        }
        try {
            ReadableExportWriter().write(DataExportFormat.CSV_ZIP, source, ByteArrayOutputStream(), 1L, counts(mapOf("notes" to 2L))) { if (cancel) throw cancellation }
            fail("Cancellation must abort")
        } catch (actual: CancellationException) { assertTrue(actual === cancellation) }
    }

    @Test fun ioFailureAndSourceFailurePropagateInsteadOfProducingSuccess() {
        val io = IOException("disk full")
        val output = object : OutputStream() { override fun write(value: Int) { throw io } }
        try {
            ReadableExportWriter().write(DataExportFormat.JSON, source(emptyMap()), output, 1L, counts())
            fail("Output failure must abort")
        } catch (actual: IOException) { assertTrue(actual === io) }
        val error = IllegalStateException("source failed")
        try {
            ReadableExportWriter().write(DataExportFormat.CSV_ZIP, ExportRowSource { _, _ -> throw error }, ByteArrayOutputStream(), 1L, counts())
            fail("Source failure must abort")
        } catch (actual: IllegalStateException) { assertTrue(actual === error) }
    }

    private fun noteRow(id: String, content: String): List<Any?> = listOf(id, "book-id", null, "THOUGHT", content, null, 0L, 1L)
    private fun counts(overrides: Map<String, Long> = emptyMap()) = tableNames.associateWith { overrides[it] ?: 0L }
    private fun source(rows: Map<String, List<List<Any?>>>) = ExportRowSource { table, consume -> rows[table.name].orEmpty().forEach(consume) }
    private fun export(format: DataExportFormat, rows: Map<String, List<List<Any?>>> = emptyMap()) = exportBytes(format, rows).toString(Charsets.UTF_8)
    private fun exportBytes(format: DataExportFormat, rows: Map<String, List<List<Any?>>> = emptyMap(), limits: ReadableExportLimits = ReadableExportLimits()): ByteArray =
        ByteArrayOutputStream().also { output ->
            ReadableExportWriter(limits).write(format, source(rows), output, 1_700_000_000_000L, counts(rows.mapValues { it.value.size.toLong() }))
        }.toByteArray()
    private fun writeWithCounts(counts: Map<String, Long>, rows: Map<String, List<List<Any?>>> = emptyMap()) {
        ReadableExportWriter().write(DataExportFormat.JSON, source(rows), ByteArrayOutputStream(), 1L, counts)
    }
    private fun unzip(bytes: ByteArray): Map<String, String> = ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
        buildMap { while (true) { val entry = input.nextEntry ?: break; put(entry.name, input.readBytes().toString(Charsets.UTF_8)); input.closeEntry() } }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid or unbounded export must fail closed") } catch (_: ReadableExportException) { }
    }
}
