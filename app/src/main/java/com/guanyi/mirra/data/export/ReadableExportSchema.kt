package com.guanyi.mirra.data.export

enum class ExportValueType { TEXT, INTEGER }

data class ExportColumn(
    val name: String,
    val type: ExportValueType,
    val nullable: Boolean = false,
    val unit: String? = null,
)

data class ExportTable(val name: String, val columns: List<ExportColumn>)

/** Explicit business-data whitelist; never inferred from runtime database tables. */
object ReadableExportSchema {
    const val EXPORT_FORMAT_VERSION = 1
    const val ROOM_SCHEMA_VERSION = 4
    private const val TIMESTAMP = "unix_epoch_milliseconds"
    private const val DURATION = "milliseconds"

    val tables: List<ExportTable> = listOf(
        ExportTable("learning_items", listOf(
            text("id"), text("name"), text("status"), integer("totalPages", "pages"),
            integer("currentPage", "page_number"), integer("mainlineSlot", "mainline_slot", nullable = true),
            text("firstAction"), timestamp("createdAt"), timestamp("updatedAt"), timestamp("completedAt", nullable = true),
        )),
        ExportTable("study_intents", listOf(
            text("id"), text("learningItemId"), timestamp("createdAt"), timestamp("transitionedAt", nullable = true),
            timestamp("convertedAt", nullable = true), timestamp("endedAt", nullable = true), text("outcome", nullable = true),
        )),
        ExportTable("study_sessions", listOf(
            text("id"), text("learningItemId"), text("intentId"), timestamp("startedAt"),
            timestamp("stableStartedAt", nullable = true), timestamp("endedAt", nullable = true),
            integer("startPage", "page_number"), integer("currentPage", "page_number"),
            integer("endPage", "page_number", nullable = true), text("endType", nullable = true),
            text("generatedSummary", nullable = true),
        )),
        ExportTable("notes", listOf(
            text("id"), text("learningItemId"), text("sessionId", nullable = true), text("semanticType"),
            text("content"), integer("pageNumber", "page_number", nullable = true), timestamp("createdAt"), timestamp("updatedAt"),
        )),
        ExportTable("image_assets", listOf(
            text("id"), text("noteId"), text("caption", nullable = true), integer("width", "pixels"),
            integer("height", "pixels"), integer("fileSize", "bytes"), timestamp("createdAt"),
        )),
        ExportTable("topics", listOf(text("id"), text("name"), timestamp("createdAt"))),
        ExportTable("note_topic_cross_refs", listOf(text("noteId"), text("topicId"))),
        ExportTable("risk_apps", listOf(text("packageName"), text("labelSnapshot"), timestamp("createdAt"), timestamp("updatedAt"))),
        ExportTable("session_focus_contexts", listOf(
            text("sessionId"), integer("snapshotVersion", "version"), duration("pollIntervalMillis"),
            duration("riskEventDedupeMillis"), duration("riskConfirmMillis"), duration("secondFrictionMillis"),
            duration("thirdPlusFrictionMillis"), duration("replyAllowanceMillis"), duration("researchAllowanceMillis"),
            duration("temporaryTaskAllowanceMillis"), duration("casualAllowanceMillis"), duration("allowanceExtensionMillis"),
            integer("maxAllowanceExtensions", "count"), duration("shortBreakMillis"), duration("longBreakMillis"),
            duration("recoveryStableMillis"), duration("stableStartMillis"), duration("deepFocusMillis"),
            duration("deepFocusScreenOffMillis"), duration("heartbeatMillis"), duration("maxObservationGapMillis"),
            integer("usageAccessAtStart", "boolean_0_1"), integer("dndAccessAtStart", "boolean_0_1"),
            integer("overlayAccessAtStart", "boolean_0_1"), integer("notificationAccessAtStart", "boolean_0_1"),
            text("monitoringStatus"), timestamp("monitoringLostAt", nullable = true), text("closeoutState"),
            integer("requestedEndPage", "page_number", nullable = true), timestamp("closeoutStartedAt", nullable = true),
            timestamp("lastHeartbeatAt"), timestamp("createdAt"), timestamp("updatedAt"),
        )),
        ExportTable("session_risk_app_snapshots", listOf(text("sessionId"), text("packageName"), text("labelSnapshot"))),
        ExportTable("session_segments", listOf(
            text("id"), text("sessionId"), text("type"), timestamp("startedAt"), timestamp("endedAt", nullable = true),
            text("packageName", nullable = true), text("reason", nullable = true), timestamp("plannedEndAt", nullable = true),
            integer("extensionCount", "count"), text("relatedSegmentId", nullable = true),
        )),
        ExportTable("focus_events", listOf(
            text("id"), text("sessionId"), text("type"), timestamp("occurredAt"), text("packageName", nullable = true),
            text("segmentId", nullable = true), text("deliveryChannel", nullable = true),
        )),
    )

    private fun text(name: String, nullable: Boolean = false) = ExportColumn(name, ExportValueType.TEXT, nullable)
    private fun integer(name: String, unit: String, nullable: Boolean = false) = ExportColumn(name, ExportValueType.INTEGER, nullable, unit)
    private fun timestamp(name: String, nullable: Boolean = false) = integer(name, TIMESTAMP, nullable)
    private fun duration(name: String) = integer(name, DURATION)
}

/** The caller owns the consistent read transaction and emits one projected row at a time. */
fun interface ExportRowSource {
    fun forEachRow(table: ExportTable, consume: (List<Any?>) -> Unit)
}
