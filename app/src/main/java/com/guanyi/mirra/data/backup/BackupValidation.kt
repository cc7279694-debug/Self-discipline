package com.guanyi.mirra.data.backup

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase

/** Reads untrusted SQLite through the caller's bounded, cancellable query boundary. */
internal class BackupValidation(private val limits: BackupLimits) {
    fun validateSchema(incomingQuery: (String) -> Cursor, trusted: SupportSQLiteDatabase) {
        requireValid(scalar(incomingQuery, "PRAGMA user_version") == 4L, "Unsupported Room schema version")
        val expected = schemaObjects { sql -> trusted.query(sql) }
        requireValid(expected.values.none { it.type == "trigger" || it.type == "view" }, "Unexpected trusted schema")
        requireValid(expected.values.filter { it.type == "table" }.all { it.name in SCHEMA_TABLES }, "Unexpected trusted table")

        val seen = mutableSetOf<String>()
        incomingQuery(SCHEMA_QUERY).use { rows ->
            while (rows.moveToNext()) {
                requireValid(seen.size < expected.size, "Unknown SQLite schema object")
                val actual = schemaObject(rows)
                requireValid(actual.type != "trigger" && actual.type != "view", "SQLite triggers and views are not supported")
                requireValid(seen.add(actual.name), "Duplicate SQLite schema object")
                requireValid(expected[actual.name] == actual, "SQLite schema does not match Room v4")
            }
        }
        requireValid(seen == expected.keys, "Missing SQLite schema object")
        // Only locally declared names are used as identifiers, never SQL or names from the archive.
        SCHEMA_TABLES.filter { expected[it]?.type == "table" }.forEach { table ->
            val sql = "PRAGMA table_info(`$table`)"
            requireValid(tableInfo(incomingQuery, sql) == tableInfo({ trusted.query(it) }, sql), "SQLite columns do not match Room v4")
        }
        val expectedIdentity = roomIdentity { trusted.query(it) }
        requireValid(roomIdentity(incomingQuery) == expectedIdentity, "Room identity does not match")
    }

    fun validateBusiness(query: (String) -> Cursor, requirePortableOwnership: Boolean): Map<String, Long> {
        requireValid(limits.rowsPerTable >= 0L && limits.totalRows >= 0L && limits.rowTextBytes > 0, "Invalid backup row limits")
        val counts = linkedMapOf<String, Long>()
        var total = 0L
        AUTHORITATIVE_TABLES.forEach { table ->
            val count = scalar(query, "SELECT COUNT(*) FROM `$table`")
            requireValid(count in 0L..limits.rowsPerTable, "Backup table row limit exceeded")
            requireValid(count <= limits.totalRows - total, "Backup total row limit exceeded")
            total += count
            counts[table] = count
        }
        query("PRAGMA integrity_check(1)").use { rows ->
            requireValid(rows.moveToFirst() && rows.getString(0) == "ok" && !rows.moveToNext(), "SQLite integrity check failed")
        }
        query("PRAGMA foreign_key_check").use { rows ->
            requireValid(!rows.moveToFirst(), "SQLite foreign key check failed")
        }
        validateStorageTypes(query)
        validateEnums(query)
        validateWorkflow(query)
        validateFocus(query, requirePortableOwnership)
        return counts
    }

    private fun validateStorageTypes(query: (String) -> Cursor) {
        COLUMNS.forEach { (table, columns) ->
            val predicates = columns.map { column ->
                val allowed = if (column.nullable) "('${column.storageType}','null')" else "('${column.storageType}')"
                "typeof(`${column.name}`) NOT IN $allowed"
            } + columns.filter { it.intValue }.map {
                "`${it.name}` < -2147483648 OR `${it.name}` > 2147483647"
            } + columns.filter { it.booleanValue }.map { "`${it.name}` NOT IN (0,1)" }
            rejectRows(query, "SELECT 1 FROM `$table` WHERE ${predicates.joinToString(" OR ")} LIMIT 1", "Invalid SQLite value type")
            // Evaluate bytes in SQLite before any whole business row reaches CursorWindow/getString.
            val textBytes = columns.filter { it.storageType == "text" }.joinToString(" + ") {
                "COALESCE(LENGTH(CAST(`${it.name}` AS BLOB)),0)"
            }
            if (textBytes.isNotEmpty()) rejectRows(query,
                "SELECT 1 FROM `$table` WHERE ($textBytes) > ${limits.rowTextBytes} LIMIT 1", "SQLite row text exceeds byte limit")
        }
        IDENTIFIERS.forEach { (table, names) ->
            rejectRows(query, "SELECT 1 FROM `$table` WHERE ${names.joinToString(" OR ") { "LENGTH(TRIM(`$it`)) = 0" }} LIMIT 1", "Empty business identifier")
        }
        rejectRows(query, "SELECT 1 FROM learning_items WHERE LENGTH(TRIM(name)) = 0 LIMIT 1", "Empty learning item name")
        rejectRows(query, "SELECT 1 FROM notes WHERE LENGTH(TRIM(content)) = 0 LIMIT 1", "Empty note content")
        rejectRows(query, "SELECT 1 FROM topics WHERE LENGTH(TRIM(name)) = 0 LIMIT 1", "Empty topic name")
        rejectRows(query, "SELECT 1 FROM image_assets WHERE width <= 0 OR height <= 0 OR fileSize <= 0 OR fileSize > ${limits.imageBytes} LIMIT 1", "Invalid image metadata")
    }

    private fun validateEnums(query: (String) -> Cursor) {
        ENUMS.forEach { enum ->
            rejectRows(query, "SELECT 1 FROM `${enum.table}` WHERE `${enum.column}` IS NOT NULL AND `${enum.column}` NOT IN (${enum.values}) LIMIT 1", "Unknown business enum value")
        }
    }

    private fun validateWorkflow(query: (String) -> Cursor) {
        rejectRows(query, "SELECT 1 FROM study_intents WHERE activeSlot IS NOT NULL OR outcome IS NULL OR endedAt IS NULL LIMIT 1", "An Intent is still active or inconsistent")
        rejectRows(query, "SELECT 1 FROM study_sessions WHERE activeSlot IS NOT NULL OR endedAt IS NULL OR endType IS NULL OR endPage IS NULL LIMIT 1", "A Session is still active or inconsistent")
        rejectRows(query, "SELECT 1 FROM session_segments WHERE activeSlot IS NOT NULL OR endedAt IS NULL LIMIT 1", "A Segment is still active or inconsistent")
        rejectRows(query, "SELECT 1 FROM session_focus_contexts WHERE closeoutState = 'PENDING' LIMIT 1", "A closeout is still pending")
        rejectRows(query, "SELECT 1 FROM learning_items WHERE totalPages < 1 OR currentPage < 1 OR currentPage > totalPages OR (mainlineSlot IS NOT NULL AND (mainlineSlot <> 1 OR status <> 'IN_PROGRESS')) OR (status = 'COMPLETED' AND completedAt IS NULL) OR (status <> 'COMPLETED' AND completedAt IS NOT NULL) LIMIT 1", "Invalid learning item state")
        requireValid(scalar(query, "SELECT COUNT(*) FROM learning_items WHERE mainlineSlot IS NOT NULL") <= 1L, "More than one mainline item")
        rejectRows(query, """
            SELECT 1 FROM study_intents i LEFT JOIN study_sessions s ON s.intentId = i.id
            WHERE (i.outcome = 'CONVERTED' AND (i.convertedAt IS NULL OR i.convertedAt <> i.endedAt OR s.id IS NULL OR s.startedAt <> i.convertedAt))
                OR (i.outcome <> 'CONVERTED' AND (i.convertedAt IS NOT NULL OR s.id IS NOT NULL)) LIMIT 1
        """.trimIndent(), "Invalid Intent conversion")
        rejectRows(query, """
            SELECT 1 FROM study_sessions s JOIN study_intents i ON i.id = s.intentId
            JOIN learning_items l ON l.id = s.learningItemId
            WHERE s.learningItemId <> i.learningItemId OR s.startPage < 1 OR s.startPage > l.totalPages
                OR s.currentPage < s.startPage OR s.currentPage > l.totalPages
                OR s.endPage < s.currentPage OR s.endPage > l.totalPages OR s.endedAt < s.startedAt LIMIT 1
        """.trimIndent(), "Invalid Session item, page or end state")
        rejectRows(query, """
            SELECT 1 FROM notes n JOIN learning_items l ON l.id = n.learningItemId
            LEFT JOIN study_sessions s ON s.id = n.sessionId
            WHERE (n.pageNumber IS NOT NULL AND (n.pageNumber < 1 OR n.pageNumber > l.totalPages))
                OR (n.sessionId IS NOT NULL AND n.learningItemId <> s.learningItemId) LIMIT 1
        """.trimIndent(), "Invalid Note item or page")
        // Pre-3D ended NORMAL history retains ACTIVE/null closeout fields; do not backfill it.
        rejectRows(query, """
            SELECT 1 FROM session_focus_contexts c JOIN study_sessions s ON s.id = c.sessionId
            JOIN learning_items l ON l.id = s.learningItemId
            WHERE (c.requestedEndPage IS NOT NULL AND (c.requestedEndPage < 1 OR c.requestedEndPage > l.totalPages))
                OR (c.requestedEndPage IS NULL AND c.closeoutStartedAt IS NOT NULL)
                OR (c.requestedEndPage IS NOT NULL AND c.closeoutStartedAt IS NULL)
                OR (c.closeoutState = 'ACTIVE' AND c.requestedEndPage IS NOT NULL)
                OR (c.closeoutState = 'COMPLETED' AND (s.endType <> 'NORMAL'
                    OR c.closeoutStartedAt IS NULL OR c.requestedEndPage IS NULL
                    OR c.closeoutStartedAt <> s.endedAt OR c.requestedEndPage <> s.endPage
                    OR s.currentPage <> s.endPage)) LIMIT 1
        """.trimIndent(), "Invalid closeout state")
    }

    private fun validateFocus(query: (String) -> Cursor, portable: Boolean) {
        rejectRows(query, """
            SELECT 1 FROM session_focus_contexts WHERE snapshotVersion <> 1
                OR maxAllowanceExtensions < 0 OR (priorDndInterruptionFilter IS NOT NULL AND priorDndInterruptionFilter NOT IN (1,2,3,4))
                OR dndLifecycle IN ('ACTIVE','RELEASE_PENDING','RELEASE_FAILED')
                OR dndRuleId = 'mirra:rule-creation-pending'
                OR (dndLifecycle IN ('NOT_APPLIED','APPLY_FAILED') AND (dndRuleId IS NOT NULL OR priorDndInterruptionFilter IS NOT NULL)) LIMIT 1
        """.trimIndent(), "Unresolved or invalid DND ownership")
        if (portable) {
            rejectRows(query, "SELECT 1 FROM session_focus_contexts WHERE dndLifecycle <> 'NOT_APPLIED' OR dndRuleId IS NOT NULL OR priorDndInterruptionFilter IS NOT NULL LIMIT 1", "Portable backup contains device ownership")
        }
        rejectRows(query, "SELECT 1 FROM session_focus_contexts WHERE ${RULE_DURATIONS.joinToString(" OR ") { "`$it` <= 0" }} LIMIT 1", "Invalid Focus rule duration")
        rejectRows(query, """
            SELECT 1 FROM session_segments g LEFT JOIN session_focus_contexts c ON c.sessionId = g.sessionId
            WHERE c.sessionId IS NULL OR g.extensionCount < 0 OR g.extensionCount > c.maxAllowanceExtensions LIMIT 1
        """.trimIndent(), "Invalid Segment context or extension count")
        rejectRows(query, """
            SELECT 1 FROM session_risk_app_snapshots r LEFT JOIN session_focus_contexts c ON c.sessionId = r.sessionId
            WHERE c.sessionId IS NULL LIMIT 1
        """.trimIndent(), "Risk snapshot has no Focus context")
        rejectRows(query, """
            SELECT 1 FROM focus_events e LEFT JOIN session_focus_contexts c ON c.sessionId = e.sessionId
            WHERE c.sessionId IS NULL LIMIT 1
        """.trimIndent(), "Focus event has no Focus context")
        rejectRows(query, """
            SELECT 1 FROM session_segments g LEFT JOIN session_segments related ON related.id = g.relatedSegmentId
            WHERE g.relatedSegmentId IS NOT NULL AND (related.id IS NULL OR related.sessionId <> g.sessionId) LIMIT 1
        """.trimIndent(), "Segment relation belongs to another Session or is missing")
        // Frozen closeout can delete a zero-length final segment without deleting its same-time event.
        // Preserve that narrow boundary fact; a missing reference elsewhere remains invalid.
        rejectRows(query, """
            SELECT 1 FROM focus_events e JOIN study_sessions s ON s.id = e.sessionId
            LEFT JOIN session_segments g ON g.id = e.segmentId
            LEFT JOIN session_focus_contexts c ON c.sessionId = e.sessionId
            WHERE e.segmentId IS NOT NULL AND (
                (g.id IS NOT NULL AND g.sessionId <> e.sessionId)
                OR (g.id IS NULL AND (c.closeoutState IS NULL OR c.closeoutState <> 'COMPLETED'
                    OR c.closeoutStartedAt IS NULL OR c.closeoutStartedAt <> s.endedAt OR e.occurredAt <> s.endedAt))
            ) LIMIT 1
        """.trimIndent(), "Event relation belongs to another Session or is missing")
        // Coverage, Stable Start, gaps and incomplete timelines are history, not admission criteria.
    }

    private fun schemaObjects(query: (String) -> Cursor): Map<String, SchemaObject> {
        val result = linkedMapOf<String, SchemaObject>()
        query(SCHEMA_QUERY).use { rows ->
            while (rows.moveToNext()) {
                requireValid(result.size < 128, "Trusted SQLite inventory is unexpectedly large")
                val value = schemaObject(rows)
                requireValid(result.put(value.name, value) == null, "Duplicate SQLite object")
            }
        }
        return result
    }

    private fun schemaObject(rows: Cursor) = SchemaObject(
        type = rows.getString(0), name = rows.getString(1), table = rows.getString(2),
        sql = if (rows.isNull(3)) null else rows.getString(3).trim().trimEnd(';').replace(WHITESPACE, " "),
    )

    private fun tableInfo(query: (String) -> Cursor, sql: String): List<ColumnInfo> = query(sql).use { rows ->
        buildList {
            while (rows.moveToNext()) {
                requireValid(size < 64, "Too many SQLite columns")
                add(ColumnInfo(rows.getInt(0), rows.getString(1), rows.getString(2), rows.getInt(3),
                    if (rows.isNull(4)) null else rows.getString(4), rows.getInt(5)))
            }
        }
    }

    private fun roomIdentity(query: (String) -> Cursor): String = query("SELECT id, identity_hash FROM room_master_table").use { rows ->
        requireValid(rows.moveToFirst() && rows.getType(0) == Cursor.FIELD_TYPE_INTEGER && rows.getLong(0) == 42L &&
            rows.getType(1) == Cursor.FIELD_TYPE_STRING, "Invalid Room identity record")
        val identity = rows.getString(1)
        requireValid(!rows.moveToNext(), "Unexpected Room identity records")
        identity
    }

    private fun scalar(query: (String) -> Cursor, sql: String): Long = query(sql).use { rows ->
        requireValid(rows.moveToFirst() && rows.getType(0) == Cursor.FIELD_TYPE_INTEGER, "Invalid SQLite scalar")
        val result = rows.getLong(0)
        requireValid(!rows.moveToNext(), "Unexpected SQLite scalar rows")
        result
    }

    private fun rejectRows(query: (String) -> Cursor, sql: String, message: String) {
        query(sql).use { requireValid(!it.moveToFirst(), message) }
    }

    private fun requireValid(valid: Boolean, message: String) {
        if (!valid) throw BackupValidationException(message)
    }

    private data class SchemaObject(val type: String, val name: String, val table: String, val sql: String?)
    private data class ColumnInfo(val position: Int, val name: String, val type: String, val notNull: Int, val default: String?, val primaryKey: Int)
    private data class Column(val name: String, val storageType: String, val nullable: Boolean, val intValue: Boolean, val booleanValue: Boolean)
    private data class EnumColumn(val table: String, val column: String, val values: String)

    private companion object {
        const val SCHEMA_QUERY = "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY name"
        val WHITESPACE = Regex("\\s+")
        val SCHEMA_TABLES = AUTHORITATIVE_TABLES + setOf("room_master_table", "android_metadata", "search_fts",
            "search_fts_content", "search_fts_segments", "search_fts_segdir", "search_fts_docsize", "search_fts_stat")
        val RULE_DURATIONS = listOf("pollIntervalMillis", "riskEventDedupeMillis", "riskConfirmMillis",
            "secondFrictionMillis", "thirdPlusFrictionMillis", "replyAllowanceMillis", "researchAllowanceMillis",
            "temporaryTaskAllowanceMillis", "casualAllowanceMillis", "allowanceExtensionMillis", "shortBreakMillis",
            "longBreakMillis", "recoveryStableMillis", "stableStartMillis", "deepFocusMillis", "deepFocusScreenOffMillis",
            "heartbeatMillis", "maxObservationGapMillis")

        // Column names, affinities and Kotlin Int/Boolean bounds come only from frozen local v4 entities.
        val COLUMNS = linkedMapOf(
            "learning_items" to columns(text = "id name status firstAction", integer = "totalPages currentPage createdAt updatedAt", nullableInteger = "mainlineSlot completedAt", intValues = "totalPages currentPage mainlineSlot"),
            "study_intents" to columns(text = "id learningItemId", nullableText = "outcome", integer = "createdAt", nullableInteger = "transitionedAt convertedAt endedAt activeSlot", intValues = "activeSlot"),
            "study_sessions" to columns(text = "id learningItemId intentId", nullableText = "endType generatedSummary", integer = "startedAt startPage currentPage", nullableInteger = "stableStartedAt endedAt endPage activeSlot", intValues = "startPage currentPage endPage activeSlot"),
            "notes" to columns(text = "id learningItemId semanticType content", nullableText = "sessionId", integer = "createdAt updatedAt", nullableInteger = "pageNumber", intValues = "pageNumber"),
            "image_assets" to columns(text = "id noteId localPath", nullableText = "caption", integer = "width height fileSize createdAt", intValues = "width height"),
            "topics" to columns(text = "id name", integer = "createdAt"),
            "note_topic_cross_refs" to columns(text = "noteId topicId"),
            "risk_apps" to columns(text = "packageName labelSnapshot", integer = "createdAt updatedAt"),
            "session_focus_contexts" to columns(text = "sessionId monitoringStatus dndLifecycle closeoutState", nullableText = "dndRuleId",
                integer = "snapshotVersion ${RULE_DURATIONS.joinToString(" ")} maxAllowanceExtensions usageAccessAtStart dndAccessAtStart overlayAccessAtStart notificationAccessAtStart lastHeartbeatAt createdAt updatedAt",
                nullableInteger = "monitoringLostAt priorDndInterruptionFilter requestedEndPage closeoutStartedAt",
                intValues = "snapshotVersion maxAllowanceExtensions priorDndInterruptionFilter requestedEndPage",
                boolValues = "usageAccessAtStart dndAccessAtStart overlayAccessAtStart notificationAccessAtStart"),
            "session_risk_app_snapshots" to columns(text = "sessionId packageName labelSnapshot"),
            "session_segments" to columns(text = "id sessionId type", nullableText = "packageName reason relatedSegmentId", integer = "startedAt extensionCount", nullableInteger = "endedAt plannedEndAt activeSlot", intValues = "extensionCount activeSlot"),
            "focus_events" to columns(text = "id sessionId type", nullableText = "packageName segmentId deliveryChannel", integer = "occurredAt"),
        )
        val IDENTIFIERS = linkedMapOf(
            "learning_items" to listOf("id"), "study_intents" to listOf("id", "learningItemId"),
            "study_sessions" to listOf("id", "learningItemId", "intentId"), "notes" to listOf("id", "learningItemId", "sessionId"),
            "image_assets" to listOf("id", "noteId", "localPath"), "topics" to listOf("id"),
            "note_topic_cross_refs" to listOf("noteId", "topicId"), "risk_apps" to listOf("packageName"),
            "session_focus_contexts" to listOf("sessionId"), "session_risk_app_snapshots" to listOf("sessionId", "packageName"),
            "session_segments" to listOf("id", "sessionId", "relatedSegmentId"), "focus_events" to listOf("id", "sessionId", "segmentId"),
        )
        val ENUMS = listOf(
            EnumColumn("learning_items", "status", "'IN_PROGRESS','PAUSED','COMPLETED'"),
            EnumColumn("study_intents", "outcome", "'CONVERTED','ABANDONED','TIMEOUT'"),
            EnumColumn("study_sessions", "endType", "'NORMAL','EARLY','AUTO','START_INCOMPLETE','ABNORMAL'"),
            EnumColumn("notes", "semanticType", "'QUOTE','SUMMARY','UNDERSTANDING','QUESTION'"),
            EnumColumn("session_focus_contexts", "monitoringStatus", "'FULL','PARTIAL','NONE'"),
            EnumColumn("session_focus_contexts", "dndLifecycle", "'NOT_APPLIED','ACTIVE','RELEASE_PENDING','RELEASED','APPLY_FAILED','RELEASE_FAILED'"),
            EnumColumn("session_focus_contexts", "closeoutState", "'ACTIVE','PENDING','COMPLETED','ABORTED'"),
            EnumColumn("session_segments", "type", "'FOCUS','DEEP_FOCUS','BREAK','TEMPORARY_ALLOWANCE','DISTRACTION','RECOVERY','UNMONITORED'"),
            EnumColumn("focus_events", "type", "'USER_PRESENT','SCREEN_INTERACTIVE','SCREEN_NON_INTERACTIVE','RISK_APP_BRIEF_VISIT','RISK_APP_CONFIRMED','INTERVENTION_SHOWN','INTERVENTION_UNAVAILABLE','PERMISSION_LOST','RECOVERY_SUCCEEDED','RECOVERY_INTERRUPTED'"),
            EnumColumn("focus_events", "deliveryChannel", "'OVERLAY','NOTIFICATION','IN_APP'"),
        )

        fun columns(text: String = "", nullableText: String = "", integer: String = "", nullableInteger: String = "", intValues: String = "", boolValues: String = ""): List<Column> {
            val ints = words(intValues).toSet()
            val bools = words(boolValues).toSet()
            return words(text).map { Column(it, "text", false, false, false) } +
                words(nullableText).map { Column(it, "text", true, false, false) } +
                words(integer).map { Column(it, "integer", false, it in ints, it in bools) } +
                words(nullableInteger).map { Column(it, "integer", true, it in ints, it in bools) }
        }

        fun words(value: String) = value.split(' ').filter { it.isNotEmpty() }

    }
}
