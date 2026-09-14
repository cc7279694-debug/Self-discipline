package com.guanyi.mirra.data.repository

import androidx.room.withTransaction
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.FocusEventEntity
import com.guanyi.mirra.data.local.entity.FocusEventType
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.RiskAppEntity
import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.InterventionDeliveryChannel
import com.guanyi.mirra.domain.SegmentMachineState
import com.guanyi.mirra.domain.SessionSegmentStateMachine
import java.util.UUID
import kotlinx.coroutines.flow.Flow

data class SegmentTransitionCommand(
    val sessionId: String,
    val type: SessionSegmentType,
    val at: Long,
    val packageName: String? = null,
    val reason: String? = null,
    val plannedEndAt: Long? = null,
    val relatedSegmentId: String? = null,
)

data class FocusEventInput(
    val sessionId: String,
    val type: FocusEventType,
    val occurredAt: Long,
    val packageName: String? = null,
    val segmentId: String? = null,
    val deliveryChannel: InterventionDeliveryChannel? = null,
)

interface FocusRepository {
    fun observeContext(sessionId: String): Flow<SessionFocusContextEntity?>
    fun observeActiveSegment(sessionId: String): Flow<SessionSegmentEntity?>
    fun observeSegments(sessionId: String): Flow<List<SessionSegmentEntity>>
    fun observeRiskApps(): Flow<List<RiskAppEntity>>
    suspend fun replaceRiskApp(packageName: String, label: String)
    suspend fun removeRiskApp(packageName: String)
    suspend fun transition(command: SegmentTransitionCommand): SessionSegmentEntity
    suspend fun recordEvent(event: FocusEventInput)
    suspend fun updateHeartbeat(sessionId: String, at: Long)
    suspend fun markMonitoringLost(sessionId: String, lastTrustedAt: Long, detectedAt: Long)
    suspend fun markStableStarted(sessionId: String, at: Long)
    suspend fun completeRecovery(sessionId: String, at: Long): SessionSegmentEntity
}

class DefaultFocusRepository(
    private val database: MirraDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val machine: SessionSegmentStateMachine = SessionSegmentStateMachine(),
) : FocusRepository {
    private val dao = database.focusDao()
    private val sessions = database.sessionDao()

    override fun observeContext(sessionId: String) = dao.observeContext(sessionId)
    override fun observeActiveSegment(sessionId: String) = dao.observeActiveSegment(sessionId)
    override fun observeSegments(sessionId: String) = dao.observeSegments(sessionId)
    override fun observeRiskApps() = dao.observeRiskApps()

    override suspend fun replaceRiskApp(packageName: String, label: String) {
        require(packageName.isNotBlank()) { "Package name 不能为空" }
        require(label.isNotBlank()) { "App 名称不能为空" }
        val now = clock()
        val existing = dao.listRiskApps().firstOrNull { it.packageName == packageName }
        dao.upsertRiskApp(RiskAppEntity(packageName, label.trim(), existing?.createdAt ?: now, now))
    }

    override suspend fun removeRiskApp(packageName: String) {
        dao.deleteRiskApp(packageName)
    }

    override suspend fun transition(command: SegmentTransitionCommand): SessionSegmentEntity =
        database.withTransaction {
            validateCommand(command)
            val state = loadState(command.sessionId)
            val decision = machine.transition(state, command.type, command.at)
            closeAndOpen(command, decision.closeCurrentAt, decision.nextType)
        }

    override suspend fun recordEvent(event: FocusEventInput) {
        database.withTransaction {
            val session = checkNotNull(sessions.get(event.sessionId)) { "Session 不存在" }
            val upperBound = session.endedAt ?: clock()
            require(event.occurredAt in session.startedAt..upperBound) { "Focus Event 越过 Session 边界" }
            dao.insertEvent(
                FocusEventEntity(
                    id = newId(), sessionId = event.sessionId, type = event.type,
                    occurredAt = event.occurredAt, packageName = event.packageName,
                    segmentId = event.segmentId, deliveryChannel = event.deliveryChannel,
                ),
            )
        }
    }

    override suspend fun updateHeartbeat(sessionId: String, at: Long) {
        database.withTransaction {
            val session = checkNotNull(sessions.get(sessionId)) { "Session 不存在" }
            check(session.activeSlot == 1 && session.endedAt == null) { "Session 已结束" }
            require(at in session.startedAt..clock()) { "Heartbeat 越过 Session 边界" }
            check(dao.updateHeartbeat(sessionId, at) == 1) { "Heartbeat 倒序或 Context 不存在" }
        }
    }

    override suspend fun markMonitoringLost(sessionId: String, lastTrustedAt: Long, detectedAt: Long) {
        database.withTransaction {
            val current = checkNotNull(dao.getActiveSegment(sessionId))
            val context = checkNotNull(dao.getContext(sessionId))
            val targetCoverage = if (context.monitoringStatus == MonitoringCoverage.FULL) {
                machine.degradeCoverage(context.monitoringStatus, MonitoringCoverage.PARTIAL)
            } else {
                context.monitoringStatus
            }
            require(detectedAt <= clock() && lastTrustedAt <= detectedAt) { "监测缺口时间非法" }
            require(lastTrustedAt >= current.startedAt) { "监测缺口越过当前 Segment" }
            if (current.type == SessionSegmentType.UNMONITORED) {
                // Repeated loss signals retain the same open unknown interval.
            } else if (lastTrustedAt == current.startedAt) {
                check(dao.changeActiveSegmentType(current.id, sessionId, SessionSegmentType.UNMONITORED) == 1)
            } else {
                machine.monitoringGap(loadState(sessionId), lastTrustedAt, detectedAt)
                check(dao.closeActiveSegment(current.id, sessionId, lastTrustedAt) == 1)
                dao.insertSegment(segment(sessionId, SessionSegmentType.UNMONITORED, lastTrustedAt, active = true))
            }
            check(dao.setCoverage(sessionId, targetCoverage, lastTrustedAt, detectedAt) == 1)
        }
    }

    override suspend fun markStableStarted(sessionId: String, at: Long) {
        database.withTransaction {
            val state = loadState(sessionId)
            check(machine.canMarkStableStart(state, at)) { "当前事实不足以确认 Stable Start" }
            check(dao.markStableStarted(sessionId, at) == 1) { "Stable Start 已记录或 Session 已结束" }
        }
    }

    override suspend fun completeRecovery(sessionId: String, at: Long): SessionSegmentEntity =
        database.withTransaction {
            val state = loadState(sessionId)
            val decision = machine.completeRecovery(state, at)
            val opened = closeAndOpen(
                SegmentTransitionCommand(sessionId, decision.nextType, at),
                decision.closeCurrentAt,
                decision.nextType,
            )
            dao.insertEvent(FocusEventEntity(newId(), sessionId, FocusEventType.RECOVERY_SUCCEEDED, at, null, opened.id, null))
            opened
        }

    private suspend fun loadState(sessionId: String): SegmentMachineState {
        val session = checkNotNull(sessions.get(sessionId)) { "Session 不存在" }
        check(session.activeSlot == 1 && session.endedAt == null) { "Session 已结束" }
        val context = checkNotNull(dao.getContext(sessionId)) { "Focus Context 不存在" }
        val active = checkNotNull(dao.getActiveSegment(sessionId)) { "Active Segment 不存在" }
        return SegmentMachineState(
            sessionStartedAt = session.startedAt,
            sessionEndedAt = session.endedAt,
            activeSegmentStartedAt = active.startedAt,
            activeSegmentType = active.type,
            coverage = context.monitoringStatus,
            stableStartedAt = session.stableStartedAt,
            stableStartMillis = context.stableStartMillis,
            recoveryStableMillis = context.recoveryStableMillis,
            now = clock(),
        )
    }

    private suspend fun closeAndOpen(
        command: SegmentTransitionCommand,
        boundary: Long,
        nextType: SessionSegmentType,
    ): SessionSegmentEntity {
        val current = checkNotNull(dao.getActiveSegment(command.sessionId))
        check(dao.closeActiveSegment(current.id, command.sessionId, boundary) == 1) { "Segment 已关闭" }
        val opened = segment(
            sessionId = command.sessionId,
            type = nextType,
            startedAt = boundary,
            packageName = command.packageName,
            reason = command.reason,
            plannedEndAt = command.plannedEndAt,
            relatedSegmentId = command.relatedSegmentId
                ?: current.id.takeIf { nextType == SessionSegmentType.RECOVERY },
            active = true,
        )
        dao.insertSegment(opened)
        return opened
    }

    private fun validateCommand(command: SegmentTransitionCommand) {
        when (command.type) {
            SessionSegmentType.BREAK -> require(command.plannedEndAt != null && command.plannedEndAt > command.at) {
                "Break 必须具有未来结束时间"
            }
            SessionSegmentType.TEMPORARY_ALLOWANCE -> {
                require(!command.packageName.isNullOrBlank()) { "Temporary Allowance 必须指定 App" }
                require(!command.reason.isNullOrBlank()) { "Temporary Allowance 必须记录理由" }
                require(command.plannedEndAt != null && command.plannedEndAt > command.at) { "Allowance 结束时间非法" }
            }
            SessionSegmentType.DISTRACTION -> require(!command.packageName.isNullOrBlank()) {
                "Distraction 必须具有已确认的 App 证据"
            }
            else -> Unit
        }
    }

    private fun segment(
        sessionId: String,
        type: SessionSegmentType,
        startedAt: Long,
        endedAt: Long? = null,
        packageName: String? = null,
        reason: String? = null,
        plannedEndAt: Long? = null,
        relatedSegmentId: String? = null,
        active: Boolean = false,
    ) = SessionSegmentEntity(
        id = newId(), sessionId = sessionId, type = type, startedAt = startedAt,
        endedAt = endedAt, packageName = packageName, reason = reason,
        plannedEndAt = plannedEndAt, extensionCount = 0, relatedSegmentId = relatedSegmentId,
        activeSlot = if (active) 1 else null,
    )
}
