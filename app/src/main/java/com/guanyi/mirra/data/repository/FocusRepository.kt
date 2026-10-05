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
import com.guanyi.mirra.domain.monitoring.StableEvidence
import com.guanyi.mirra.domain.monitoring.EvidenceMilestone
import com.guanyi.mirra.domain.monitoring.AllowanceReason

enum class BehaviorAction { START_BREAK, FINISH_BREAK, GRANT_ALLOWANCE, EXTEND_ALLOWANCE, FINISH_ALLOWANCE, EXPIRE }
data class BehaviorCommand(val sessionId: String, val expectedSegmentId: String, val action: BehaviorAction,
    val at: Long, val actionToken: String, val packageName: String? = null, val reason: String? = null,
    val durationMillis: Long? = null, val expectedRiskEventId: String? = null)

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

data class RuntimeFocusFacts(
    val sessionStartedAt: Long,
    val activeSegment: SessionSegmentEntity,
    val coverage: MonitoringCoverage,
    val lastHeartbeatAt: Long,
    val riskPackages: Set<String>,
    val context: SessionFocusContextEntity? = null,
    val latestRiskEvent: FocusEventEntity? = null,
    val riskConfirmationCounts: Map<String, Int> = emptyMap(),
    val stableStartedAt: Long? = null,
    val interventionEligible: Boolean = false,
)

data class RiskConfirmation(
    val sessionId: String,
    val sourceSegmentId: String,
    val packageName: String,
    val candidateStartedAt: Long,
    val confirmedAt: Long,
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
    suspend fun markStableStarted(sessionId: String, at: Long, evidence: StableEvidence? = null)
    suspend fun completeRecovery(sessionId: String, at: Long, evidence: StableEvidence? = null): SessionSegmentEntity
    suspend fun applyBehavior(command: BehaviorCommand): SessionSegmentEntity?
    suspend fun promoteDeepFocus(sessionId: String, at: Long, evidence: StableEvidence): SessionSegmentEntity
    suspend fun runtimeFacts(sessionId: String): RuntimeFocusFacts?
    suspend fun confirmRisk(candidate: RiskConfirmation): Boolean
    suspend fun recordBriefRiskVisit(sessionId: String, packageName: String, exitedAt: Long): Boolean
    suspend fun exitRisk(sessionId: String, packageName: String, at: Long): Boolean
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

    override suspend fun runtimeFacts(sessionId: String): RuntimeFocusFacts? = database.withTransaction {
        val session = sessions.get(sessionId)?.takeIf { it.activeSlot == 1 && it.endedAt == null } ?: return@withTransaction null
        val context = dao.getContext(sessionId) ?: return@withTransaction null
        if (!isLearningFactWritable(session, context)) return@withTransaction null
        val active = dao.getActiveSegment(sessionId) ?: return@withTransaction null
        val risks = dao.listSessionRiskPackages(sessionId).toSet()
        val latest = dao.latestRiskConfirmation(sessionId)
        val linkedRisk = active.relatedSegmentId?.let { related ->
            dao.getSegment(sessionId, related)?.takeIf { it.type == SessionSegmentType.DISTRACTION }
        }
        val eligible = latest != null && (active.type == SessionSegmentType.DISTRACTION &&
            active.packageName == latest.packageName && latest.occurredAt >= active.startedAt ||
            active.type == SessionSegmentType.RECOVERY && linkedRisk?.packageName == latest.packageName &&
            latest.occurredAt >= (linkedRisk?.startedAt ?: Long.MAX_VALUE))
        RuntimeFocusFacts(session.startedAt, active, context.monitoringStatus, context.lastHeartbeatAt,
            risks, context, latest, dao.riskConfirmationCounts(sessionId).associate { it.packageName to it.confirmations },
            session.stableStartedAt, eligible)
    }

    override suspend fun confirmRisk(candidate: RiskConfirmation): Boolean = database.withTransaction {
        val session = sessions.get(candidate.sessionId)?.takeIf { it.activeSlot == 1 && it.endedAt == null }
            ?: return@withTransaction false
        val context = dao.getContext(candidate.sessionId) ?: return@withTransaction false
        if (!isLearningFactWritable(session, context)) return@withTransaction false
        val active = dao.getActiveSegment(candidate.sessionId) ?: return@withTransaction false
        if (context.monitoringStatus == MonitoringCoverage.NONE || active.id != candidate.sourceSegmentId ||
            active.type !in setOf(SessionSegmentType.FOCUS, SessionSegmentType.DEEP_FOCUS,
                SessionSegmentType.RECOVERY, SessionSegmentType.TEMPORARY_ALLOWANCE, SessionSegmentType.DISTRACTION) ||
            (active.type == SessionSegmentType.TEMPORARY_ALLOWANCE &&
                (active.packageName == candidate.packageName || candidate.confirmedAt >= (active.plannedEndAt ?: 0))) ||
            (active.type == SessionSegmentType.DISTRACTION && active.packageName == candidate.packageName) ||
            candidate.packageName !in dao.listSessionRiskPackages(candidate.sessionId) ||
            candidate.candidateStartedAt < active.startedAt || candidate.candidateStartedAt < session.startedAt ||
            candidate.confirmedAt - candidate.candidateStartedAt < context.riskConfirmMillis ||
            candidate.confirmedAt > clock() ||
            dao.eventExists(candidate.sessionId, riskEventId(candidate))
        ) return@withTransaction false
        if (candidate.candidateStartedAt == active.startedAt) {
            check(dao.changeActiveSegmentToRisk(active.id, candidate.sessionId,
                SessionSegmentType.DISTRACTION, candidate.packageName) == 1)
        } else {
            check(dao.closeActiveSegment(active.id, candidate.sessionId, candidate.candidateStartedAt) == 1)
            dao.insertSegment(segment(candidate.sessionId, SessionSegmentType.DISTRACTION,
                candidate.candidateStartedAt, packageName = candidate.packageName, active = true))
        }
        if (active.type == SessionSegmentType.RECOVERY) {
            dao.insertEvent(FocusEventEntity("interrupted:${riskEventId(candidate)}", candidate.sessionId,
                FocusEventType.RECOVERY_INTERRUPTED, candidate.confirmedAt, candidate.packageName, active.id, null))
        }
        dao.insertEvent(FocusEventEntity(riskEventId(candidate), candidate.sessionId, FocusEventType.RISK_APP_CONFIRMED,
            candidate.confirmedAt, candidate.packageName, active.id, null))
        true
    }

    override suspend fun recordBriefRiskVisit(sessionId: String, packageName: String, exitedAt: Long): Boolean =
        database.withTransaction {
            val session = sessions.get(sessionId)?.takeIf { it.activeSlot == 1 && it.endedAt == null }
                ?: return@withTransaction false
            if (!isLearningFactWritable(session, dao.getContext(sessionId))) return@withTransaction false
            if (exitedAt !in session.startedAt..clock() ||
                packageName !in dao.listSessionRiskPackages(sessionId) ||
                dao.countMatchingEvent(sessionId, FocusEventType.RISK_APP_BRIEF_VISIT, packageName, exitedAt) > 0
            ) return@withTransaction false
            dao.insertEvent(FocusEventEntity(newId(), sessionId, FocusEventType.RISK_APP_BRIEF_VISIT,
                exitedAt, packageName, null, null))
            true
        }

    override suspend fun exitRisk(sessionId: String, packageName: String, at: Long): Boolean = database.withTransaction {
        val session = sessions.get(sessionId)?.takeIf { it.activeSlot == 1 && it.endedAt == null }
            ?: return@withTransaction false
        if (!isLearningFactWritable(session, dao.getContext(sessionId))) return@withTransaction false
        val active = dao.getActiveSegment(sessionId) ?: return@withTransaction false
        if (active.type != SessionSegmentType.DISTRACTION || active.packageName != packageName ||
            at <= active.startedAt || at > clock() || at < session.startedAt) return@withTransaction false
        check(dao.closeActiveSegment(active.id, sessionId, at) == 1)
        dao.insertSegment(segment(sessionId, SessionSegmentType.RECOVERY, at,
            relatedSegmentId = active.id, active = true))
        true
    }

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
            check(command.type != SessionSegmentType.DEEP_FOCUS &&
                !(state.activeSegmentType == SessionSegmentType.RECOVERY && command.type == SessionSegmentType.FOCUS)) {
                "自动里程碑必须提交连续证据"
            }
            val decision = machine.transition(state, command.type, command.at)
            closeAndOpen(command, decision.closeCurrentAt, decision.nextType)
        }

    override suspend fun recordEvent(event: FocusEventInput) {
        database.withTransaction {
            val session = checkNotNull(sessions.get(event.sessionId)) { "Session 不存在" }
            check(isLearningFactWritable(session, dao.getContext(event.sessionId))) { "Session 已结束" }
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
            check(isLearningFactWritable(session, dao.getContext(sessionId))) { "Session 已结束" }
            require(at in session.startedAt..clock()) { "Heartbeat 越过 Session 边界" }
            check(dao.updateHeartbeat(sessionId, at) == 1) { "Heartbeat 倒序或 Context 不存在" }
        }
    }

    override suspend fun markMonitoringLost(sessionId: String, lastTrustedAt: Long, detectedAt: Long) {
        database.withTransaction {
            val session = checkNotNull(sessions.get(sessionId)) { "Session 不存在" }
            check(isLearningFactWritable(session, dao.getContext(sessionId))) { "Session 已结束" }
            val current = checkNotNull(dao.getActiveSegment(sessionId))
            val context = checkNotNull(dao.getContext(sessionId))
            val targetCoverage = if (context.monitoringStatus == MonitoringCoverage.FULL) {
                machine.degradeCoverage(context.monitoringStatus, MonitoringCoverage.PARTIAL)
            } else {
                context.monitoringStatus
            }
            // After a backward wall-clock jump, the last trusted persisted boundary may be
            // ahead of the new wall time. Preserve that boundary; never write time backwards.
            val observedNow = clock()
            require(detectedAt <= maxOf(observedNow, lastTrustedAt) && lastTrustedAt <= detectedAt) { "监测缺口时间非法" }
            require(lastTrustedAt >= current.startedAt) { "监测缺口越过当前 Segment" }
            if (current.type == SessionSegmentType.UNMONITORED) {
                // Repeated loss signals retain the same open unknown interval.
            } else if (lastTrustedAt == current.startedAt) {
                check(dao.changeActiveSegmentType(current.id, sessionId, SessionSegmentType.UNMONITORED) == 1)
            } else {
                // This upper bound belongs only to loss validation, not ordinary transitions.
                val lossValidationUpperBound = maxOf(observedNow, detectedAt, lastTrustedAt)
                machine.monitoringGap(loadState(sessionId).copy(now = lossValidationUpperBound), lastTrustedAt, detectedAt)
                check(dao.closeActiveSegment(current.id, sessionId, lastTrustedAt) == 1)
                dao.insertSegment(segment(sessionId, SessionSegmentType.UNMONITORED, lastTrustedAt, active = true))
            }
            check(dao.setCoverage(sessionId, targetCoverage, lastTrustedAt, detectedAt) == 1)
        }
    }

    override suspend fun markStableStarted(sessionId: String, at: Long, evidence: StableEvidence?) {
        database.withTransaction {
            val state = loadState(sessionId)
            validateEvidence(sessionId, evidence, EvidenceMilestone.STABLE, state.stableStartMillis)
            check(machine.canMarkStableStart(state, at)) { "当前事实不足以确认 Stable Start" }
            check(dao.markStableStarted(sessionId, at) == 1) { "Stable Start 已记录或 Session 已结束" }
        }
    }

    override suspend fun completeRecovery(sessionId: String, at: Long, evidence: StableEvidence?): SessionSegmentEntity =
        database.withTransaction {
            val state = loadState(sessionId)
            check(state.coverage != MonitoringCoverage.NONE)
            validateEvidence(sessionId, evidence, EvidenceMilestone.RECOVERY, state.recoveryStableMillis)
            val decision = machine.completeRecovery(state, at)
            val opened = closeAndOpen(
                SegmentTransitionCommand(sessionId, decision.nextType, at),
                decision.closeCurrentAt,
                decision.nextType,
            )
            dao.insertEvent(FocusEventEntity(newId(), sessionId, FocusEventType.RECOVERY_SUCCEEDED, at, null, opened.id, null))
            opened
        }

    override suspend fun promoteDeepFocus(sessionId: String, at: Long, evidence: StableEvidence): SessionSegmentEntity =
        database.withTransaction {
            val state = loadState(sessionId)
            val context = checkNotNull(dao.getContext(sessionId))
            check(state.coverage == MonitoringCoverage.FULL && state.activeSegmentType == SessionSegmentType.FOCUS)
            validateEvidence(sessionId, evidence, EvidenceMilestone.DEEP, context.deepFocusMillis)
            check(evidence.screenOffMillis >= context.deepFocusScreenOffMillis)
            machine.transition(state, SessionSegmentType.DEEP_FOCUS, at)
            closeAndOpen(SegmentTransitionCommand(sessionId, SessionSegmentType.DEEP_FOCUS, at), at, SessionSegmentType.DEEP_FOCUS)
        }

    private suspend fun validateEvidence(sessionId: String, evidence: StableEvidence?, milestone: EvidenceMilestone, duration: Long) {
        checkNotNull(evidence) { "必须提供连续正向证据" }
        check(dao.getContext(sessionId)?.monitoringLostAt == null) { "监测已中断" }
        check(evidence.milestone == milestone && evidence.segmentId == dao.getActiveSegment(sessionId)?.id &&
            evidence.continuousMillis >= duration) { "连续证据已过期或不足" }
    }

    private fun riskEventId(candidate: RiskConfirmation) =
        "risk:${candidate.sessionId}:${candidate.sourceSegmentId}:${candidate.packageName}:${candidate.candidateStartedAt}"

    override suspend fun applyBehavior(command: BehaviorCommand): SessionSegmentEntity? = database.withTransaction {
        val session = sessions.get(command.sessionId)?.takeIf { it.activeSlot == 1 && it.endedAt == null }
            ?: return@withTransaction null
        val current = dao.getActiveSegment(command.sessionId) ?: return@withTransaction null
        val context = dao.getContext(command.sessionId) ?: return@withTransaction null
        if (!isLearningFactWritable(session, context)) return@withTransaction null
        if (current.id != command.expectedSegmentId || command.actionToken.isBlank() ||
            command.at < session.startedAt || command.at > clock()) return@withTransaction null
        // Token consumption is runtime-local; expected segment + conditional SQL protect durable facts.
        if (command.action == BehaviorAction.EXTEND_ALLOWANCE) {
            if (context.monitoringStatus == MonitoringCoverage.NONE || context.monitoringLostAt != null ||
                command.at < current.startedAt || current.extensionCount >= context.maxAllowanceExtensions)
                return@withTransaction null
            return@withTransaction if (dao.extendAllowance(current.id, command.sessionId, command.at,
                context.allowanceExtensionMillis) == 1) dao.getActiveSegment(command.sessionId) else null
        }
        if (command.at <= current.startedAt) return@withTransaction null // reject same-time, no invented milliseconds
        val monitored = context.monitoringStatus != MonitoringCoverage.NONE && context.monitoringLostAt == null
        val next = when (command.action) {
            BehaviorAction.START_BREAK -> {
                if (command.durationMillis !in setOf(context.shortBreakMillis, context.longBreakMillis)) return@withTransaction null
                SessionSegmentType.BREAK
            }
            BehaviorAction.GRANT_ALLOWANCE -> {
                if (!monitored || current.type !in setOf(SessionSegmentType.DISTRACTION, SessionSegmentType.RECOVERY) ||
                    command.packageName !in dao.listSessionRiskPackages(command.sessionId) ||
                    AllowanceReason.entries.none { it.name == command.reason } ||
                    command.durationMillis == null || command.durationMillis !in 60_000L..900_000L) return@withTransaction null
                val event = dao.latestRiskConfirmation(command.sessionId) ?: return@withTransaction null
                if (command.expectedRiskEventId == null || event.id != command.expectedRiskEventId ||
                    event.packageName != command.packageName ||
                    (current.type == SessionSegmentType.DISTRACTION && current.packageName != command.packageName) ||
                    (current.type == SessionSegmentType.RECOVERY &&
                        current.relatedSegmentId?.let { dao.getSegment(command.sessionId, it) }
                            ?.takeIf { it.type == SessionSegmentType.DISTRACTION && it.packageName == command.packageName } == null))
                    return@withTransaction null
                SessionSegmentType.TEMPORARY_ALLOWANCE
            }
            BehaviorAction.FINISH_BREAK, BehaviorAction.FINISH_ALLOWANCE, BehaviorAction.EXPIRE -> {
                if (current.type !in setOf(SessionSegmentType.BREAK, SessionSegmentType.TEMPORARY_ALLOWANCE)) return@withTransaction null
                if (command.action == BehaviorAction.FINISH_BREAK && current.type != SessionSegmentType.BREAK ||
                    command.action == BehaviorAction.FINISH_ALLOWANCE && current.type != SessionSegmentType.TEMPORARY_ALLOWANCE)
                    return@withTransaction null
                if (command.action == BehaviorAction.EXPIRE && command.at != current.plannedEndAt) return@withTransaction null
                if (monitored) SessionSegmentType.RECOVERY else SessionSegmentType.UNMONITORED
            }
            BehaviorAction.EXTEND_ALLOWANCE -> error("handled above")
        }
        machine.transition(loadState(command.sessionId), next, command.at)
        closeAndOpen(SegmentTransitionCommand(command.sessionId, next, command.at,
            packageName = command.packageName.takeIf { next == SessionSegmentType.TEMPORARY_ALLOWANCE },
            reason = command.reason.takeIf { next == SessionSegmentType.TEMPORARY_ALLOWANCE },
            plannedEndAt = command.durationMillis?.let { Math.addExact(command.at, it) }
                .takeIf { next == SessionSegmentType.BREAK || next == SessionSegmentType.TEMPORARY_ALLOWANCE }),
            command.at, next)
    }

    private suspend fun loadState(sessionId: String): SegmentMachineState {
        val session = checkNotNull(sessions.get(sessionId)) { "Session 不存在" }
        check(session.activeSlot == 1 && session.endedAt == null) { "Session 已结束" }
        val context = checkNotNull(dao.getContext(sessionId)) { "Focus Context 不存在" }
        check(isLearningFactWritable(session, context)) { "Session 已结束" }
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
