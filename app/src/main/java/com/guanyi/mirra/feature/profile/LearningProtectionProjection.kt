package com.guanyi.mirra.feature.profile

import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.platform.focus.CapabilityStatus
import com.guanyi.mirra.platform.focus.MonitoringBinding
import com.guanyi.mirra.platform.focus.MonitoringCapabilities
import com.guanyi.mirra.platform.focus.ServicePhase

// Display-only cutoff matches the frozen Service/controller 6-second deadline, not a new fact policy.
private const val CURRENT_QUERY_DISPLAY_MAX_AGE_MILLIS = 6_000L

enum class LearningProtectionServiceStatus { INACTIVE, STOPPED, PREPARING, READY, UNAVAILABLE, NOT_CONFIRMED }

data class LearningProtectionMonitoringUiState(
    val usageStatus: CapabilityStatus? = null,
    val activeSessionId: String? = null,
    val coverage: MonitoringCoverage? = null,
    val serviceStatus: LearningProtectionServiceStatus = LearningProtectionServiceStatus.INACTIVE,
)

/** Permission grants, process readiness and persisted Session coverage remain separate facts. */
fun projectLearningProtection(
    capabilities: MonitoringCapabilities?,
    binding: MonitoringBinding?,
    session: StudySessionEntity?,
    focusContext: SessionFocusContextEntity?,
    elapsedNowMillis: Long? = null,
): LearningProtectionMonitoringUiState {
    val current = session?.takeIf {
        it.activeSlot == 1 && it.endedAt == null && focusContext != null && focusContext.sessionId == it.id &&
            focusContext.closeoutState == FocusCloseoutState.ACTIVE
    }
    if (current == null) return LearningProtectionMonitoringUiState(usageStatus = capabilities?.usage)
    val matchingBinding = binding != null && binding.sessionId == current.id &&
        binding.generation == capabilities?.service?.generation
    val lastQueryElapsed = capabilities?.monitor?.lastSuccessfulQueryElapsed
    val queryAge = if (elapsedNowMillis != null && lastQueryElapsed != null) {
        try { Math.subtractExact(elapsedNowMillis, lastQueryElapsed) }
        catch (_: ArithmeticException) { null }
    } else null
    val freshQuery = queryAge != null && queryAge >= 0 && queryAge < CURRENT_QUERY_DISPLAY_MAX_AGE_MILLIS
    val serviceStatus = when {
        capabilities == null -> LearningProtectionServiceStatus.NOT_CONFIRMED
        capabilities.service.phase == ServicePhase.STOPPED -> LearningProtectionServiceStatus.STOPPED
        !matchingBinding -> LearningProtectionServiceStatus.NOT_CONFIRMED
        capabilities.monitoringReady && freshQuery -> LearningProtectionServiceStatus.READY
        capabilities.service.phase == ServicePhase.MONITOR_READY -> LearningProtectionServiceStatus.NOT_CONFIRMED
        capabilities.service.phase == ServicePhase.FAILED -> LearningProtectionServiceStatus.UNAVAILABLE
        else -> LearningProtectionServiceStatus.PREPARING
    }
    return LearningProtectionMonitoringUiState(
        usageStatus = capabilities?.usage,
        activeSessionId = current.id,
        coverage = focusContext?.monitoringStatus,
        serviceStatus = serviceStatus,
    )
}

/** Runtime notification authorization does not imply either notification channel is usable. */
data class LearningProtectionPermissionsUiState(
    val usage: CapabilityStatus? = null,
    val dndGranted: Boolean? = null,
    val overlayGranted: Boolean? = null,
    val overlaySupported: Boolean = true,
    val notificationGranted: Boolean? = null,
    val monitoringNotificationUsable: Boolean? = null,
    val interventionNotificationUsable: Boolean? = null,
)
