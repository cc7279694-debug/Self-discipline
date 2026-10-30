package com.guanyi.mirra.feature.session

import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.local.model.CloseoutSnapshot
import com.guanyi.mirra.feature.profile.LearningProtectionMonitoringUiState
import com.guanyi.mirra.feature.profile.LearningProtectionServiceStatus

/** Display only: reuse the Session clock; never sample a new Closeout boundary or persist a tick. */
internal fun readingElapsedMillis(
    session: StudySessionEntity?,
    closeout: CloseoutSnapshot?,
    nowMillis: Long,
    logicallyEnded: Boolean,
): Long? {
    session ?: return null
    val frozen = closeout?.takeIf { it.sessionId == session.id }?.closeoutStartedAt
    if (frozen != null && session.endedAt != null && frozen != session.endedAt) return null
    val end = session.endedAt ?: frozen ?: nowMillis.takeUnless { logicallyEnded } ?: return null
    return try { Math.subtractExact(end, session.startedAt).takeIf { it >= 0 } }
    catch (_: ArithmeticException) { null }
}

internal fun formatReadingElapsed(millis: Long?): String {
    if (millis == null || millis < 0) return "—"
    val seconds = millis / 1_000
    val minutes = seconds / 60
    val tail = (seconds % 60).toString().padStart(2, '0')
    return if (minutes < 60) "${minutes.toString().padStart(2, '0')}:$tail"
    else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$tail"
}

/** An already-qualified display projection, not another permission/readiness/coverage algorithm. */
internal fun sessionMonitoringHeadline(state: LearningProtectionMonitoringUiState): String = when {
    state.activeSessionId == null -> "正在确认监测状态"
    state.coverage == MonitoringCoverage.PARTIAL -> "监测已中断"
    state.coverage == MonitoringCoverage.NONE -> "本次未开启监测"
    state.coverage == MonitoringCoverage.FULL && state.serviceStatus == LearningProtectionServiceStatus.READY -> "监测正常"
    state.coverage == MonitoringCoverage.FULL && state.serviceStatus in setOf(
        LearningProtectionServiceStatus.STOPPED, LearningProtectionServiceStatus.UNAVAILABLE) -> "监测已中断"
    else -> "正在确认监测状态"
}
