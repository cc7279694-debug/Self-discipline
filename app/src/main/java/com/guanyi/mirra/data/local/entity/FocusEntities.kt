package com.guanyi.mirra.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class SessionSegmentType(val countsAsFocus: Boolean) {
    FOCUS(true),
    DEEP_FOCUS(true),
    BREAK(false),
    TEMPORARY_ALLOWANCE(false),
    DISTRACTION(false),
    RECOVERY(false),
    UNMONITORED(false),
}

enum class MonitoringCoverage { FULL, PARTIAL, NONE }
enum class DndLifecycle { NOT_APPLIED, ACTIVE, RELEASE_PENDING, RELEASED, APPLY_FAILED, RELEASE_FAILED }
enum class FocusCloseoutState { ACTIVE, PENDING, COMPLETED, ABORTED }
enum class FocusEventType {
    USER_PRESENT,
    SCREEN_INTERACTIVE,
    SCREEN_NON_INTERACTIVE,
    RISK_APP_BRIEF_VISIT,
    RISK_APP_CONFIRMED,
    INTERVENTION_SHOWN,
    INTERVENTION_UNAVAILABLE,
    PERMISSION_LOST,
    RECOVERY_SUCCEEDED,
    RECOVERY_INTERRUPTED,
}
enum class InterventionDeliveryChannel { OVERLAY, NOTIFICATION, IN_APP }

@Entity(tableName = "risk_apps")
data class RiskAppEntity(
    @androidx.room.PrimaryKey val packageName: String,
    val labelSnapshot: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "session_focus_contexts",
    foreignKeys = [ForeignKey(
        entity = StudySessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class SessionFocusContextEntity(
    @androidx.room.PrimaryKey val sessionId: String,
    val snapshotVersion: Int = 1,
    val pollIntervalMillis: Long = 2_000,
    val riskEventDedupeMillis: Long = 10_000,
    val riskConfirmMillis: Long = 10_000,
    val secondFrictionMillis: Long = 5_000,
    val thirdPlusFrictionMillis: Long = 15_000,
    val replyAllowanceMillis: Long = 180_000,
    val researchAllowanceMillis: Long = 300_000,
    val temporaryTaskAllowanceMillis: Long = 300_000,
    val casualAllowanceMillis: Long = 180_000,
    val allowanceExtensionMillis: Long = 120_000,
    val maxAllowanceExtensions: Int = 1,
    val shortBreakMillis: Long = 300_000,
    val longBreakMillis: Long = 600_000,
    val recoveryStableMillis: Long = 90_000,
    val stableStartMillis: Long = 120_000,
    val deepFocusMillis: Long = 900_000,
    val deepFocusScreenOffMillis: Long = 600_000,
    val heartbeatMillis: Long = 15_000,
    val maxObservationGapMillis: Long = 6_000,
    val usageAccessAtStart: Boolean = false,
    val dndAccessAtStart: Boolean = false,
    val overlayAccessAtStart: Boolean = false,
    val notificationAccessAtStart: Boolean = false,
    val monitoringStatus: MonitoringCoverage,
    val monitoringLostAt: Long?,
    val priorDndInterruptionFilter: Int?,
    val dndRuleId: String?,
    val dndLifecycle: DndLifecycle = DndLifecycle.NOT_APPLIED,
    val closeoutState: FocusCloseoutState = FocusCloseoutState.ACTIVE,
    val requestedEndPage: Int?,
    val closeoutStartedAt: Long?,
    val lastHeartbeatAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "session_risk_app_snapshots",
    primaryKeys = ["sessionId", "packageName"],
    foreignKeys = [ForeignKey(
        entity = StudySessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class SessionRiskAppSnapshotEntity(
    val sessionId: String,
    val packageName: String,
    val labelSnapshot: String,
)

@Entity(
    tableName = "session_segments",
    foreignKeys = [ForeignKey(
        entity = StudySessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "startedAt"]),
        Index(value = ["activeSlot"], unique = true),
    ],
)
data class SessionSegmentEntity(
    @androidx.room.PrimaryKey val id: String,
    val sessionId: String,
    val type: SessionSegmentType,
    val startedAt: Long,
    val endedAt: Long?,
    val packageName: String?,
    val reason: String?,
    val plannedEndAt: Long?,
    val extensionCount: Int = 0,
    val relatedSegmentId: String?,
    val activeSlot: Int?,
)

@Entity(
    tableName = "focus_events",
    foreignKeys = [ForeignKey(
        entity = StudySessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["sessionId", "occurredAt"])],
)
data class FocusEventEntity(
    @androidx.room.PrimaryKey val id: String,
    val sessionId: String,
    val type: FocusEventType,
    val occurredAt: Long,
    val packageName: String?,
    val segmentId: String?,
    val deliveryChannel: InterventionDeliveryChannel?,
)
