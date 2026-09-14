package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentType

data class SegmentMachineState(
    val sessionStartedAt: Long,
    val sessionEndedAt: Long?,
    val activeSegmentStartedAt: Long,
    val activeSegmentType: SessionSegmentType,
    val coverage: MonitoringCoverage,
    val stableStartedAt: Long?,
    val stableStartMillis: Long,
    val recoveryStableMillis: Long,
    val now: Long,
)

data class SegmentTransitionDecision(
    val closeCurrentAt: Long,
    val openNextAt: Long,
    val nextType: SessionSegmentType,
    val coverage: MonitoringCoverage,
    val recordRecoverySucceeded: Boolean = false,
)

class SessionSegmentStateMachine {
    fun transition(
        state: SegmentMachineState,
        nextType: SessionSegmentType,
        at: Long,
    ): SegmentTransitionDecision {
        validateBoundary(state, at)
        check(state.activeSegmentType != nextType) { "Segment 已处于目标状态" }
        check(nextType in allowedTransitions.getValue(state.activeSegmentType)) { "非法 Segment 转换" }
        return SegmentTransitionDecision(at, at, nextType, state.coverage)
    }

    fun monitoringGap(
        state: SegmentMachineState,
        lastTrustedAt: Long,
        detectedAt: Long,
    ): SegmentTransitionDecision {
        require(detectedAt <= state.now) { "缺口结束时间不能晚于当前时间" }
        require(lastTrustedAt <= detectedAt) { "监测缺口时间倒序" }
        validateBoundary(state, lastTrustedAt)
        return SegmentTransitionDecision(
            closeCurrentAt = lastTrustedAt,
            openNextAt = lastTrustedAt,
            nextType = SessionSegmentType.UNMONITORED,
            coverage = if (state.coverage == MonitoringCoverage.FULL) {
                MonitoringCoverage.PARTIAL
            } else {
                state.coverage
            },
        )
    }

    fun degradeCoverage(
        current: MonitoringCoverage,
        requested: MonitoringCoverage,
    ): MonitoringCoverage {
        val allowed = when (current) {
            MonitoringCoverage.FULL -> setOf(MonitoringCoverage.FULL, MonitoringCoverage.PARTIAL, MonitoringCoverage.NONE)
            MonitoringCoverage.PARTIAL -> setOf(MonitoringCoverage.PARTIAL, MonitoringCoverage.NONE)
            MonitoringCoverage.NONE -> setOf(MonitoringCoverage.NONE, MonitoringCoverage.PARTIAL)
        }
        check(requested in allowed) { "PARTIAL/NONE 不能升级为 FULL" }
        return requested
    }

    fun completeRecovery(state: SegmentMachineState, at: Long): SegmentTransitionDecision {
        check(state.activeSegmentType == SessionSegmentType.RECOVERY) { "当前不是 Recovery" }
        require(at - state.activeSegmentStartedAt >= state.recoveryStableMillis) { "Recovery 尚未稳定" }
        return transition(state, SessionSegmentType.FOCUS, at).copy(recordRecoverySucceeded = true)
    }

    fun canMarkStableStart(state: SegmentMachineState, at: Long): Boolean =
        state.stableStartedAt == null &&
            state.coverage == MonitoringCoverage.FULL &&
            state.activeSegmentType.countsAsFocus &&
            at - state.activeSegmentStartedAt >= state.stableStartMillis &&
            runCatching { validateBoundary(state, at) }.isSuccess

    private fun validateBoundary(state: SegmentMachineState, at: Long) {
        check(state.sessionEndedAt == null) { "Session 已结束" }
        require(at > state.activeSegmentStartedAt) { "Segment 必须具有正时长" }
        require(at >= state.sessionStartedAt) { "Segment 早于 Session" }
        require(at <= state.now) { "Segment 晚于当前时间" }
    }

    private companion object {
        val allowedTransitions = mapOf(
            SessionSegmentType.FOCUS to setOf(
                SessionSegmentType.DEEP_FOCUS, SessionSegmentType.BREAK,
                SessionSegmentType.TEMPORARY_ALLOWANCE, SessionSegmentType.DISTRACTION,
                SessionSegmentType.UNMONITORED,
            ),
            SessionSegmentType.DEEP_FOCUS to setOf(
                SessionSegmentType.FOCUS, SessionSegmentType.BREAK,
                SessionSegmentType.TEMPORARY_ALLOWANCE, SessionSegmentType.DISTRACTION,
                SessionSegmentType.UNMONITORED,
            ),
            SessionSegmentType.BREAK to setOf(
                SessionSegmentType.FOCUS, SessionSegmentType.DISTRACTION, SessionSegmentType.UNMONITORED,
            ),
            SessionSegmentType.TEMPORARY_ALLOWANCE to setOf(
                SessionSegmentType.RECOVERY, SessionSegmentType.DISTRACTION, SessionSegmentType.UNMONITORED,
            ),
            SessionSegmentType.DISTRACTION to setOf(SessionSegmentType.RECOVERY, SessionSegmentType.UNMONITORED),
            SessionSegmentType.RECOVERY to setOf(
                SessionSegmentType.FOCUS, SessionSegmentType.DISTRACTION, SessionSegmentType.UNMONITORED,
            ),
            SessionSegmentType.UNMONITORED to setOf(SessionSegmentType.FOCUS, SessionSegmentType.BREAK),
        )
    }
}
