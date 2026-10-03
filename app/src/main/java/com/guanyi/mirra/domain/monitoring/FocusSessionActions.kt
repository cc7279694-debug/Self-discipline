package com.guanyi.mirra.domain.monitoring

import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import kotlinx.coroutines.flow.StateFlow

enum class FocusActionResult { SUCCESS, EXPIRED, CONFLICT, SAVE_FAILED }
data class AllowanceOption(val reason: AllowanceReason, val durationMillis: Long)
data class InterventionUiModel(val sessionId: String, val promptToken: String, val eventId: String,
    val segmentId: String, val packageName: String, val waitMillis: Long,
    val remainingWaitMillis: Long, val reason: AllowanceReason? = null, val dismissed: Boolean = false,
    val allowanceOptions: List<AllowanceOption> = emptyList())
data class FocusStatusUiModel(val sessionId: String? = null, val segmentId: String? = null,
    val type: SessionSegmentType? = null, val coverage: MonitoringCoverage? = null,
    val remainingMillis: Long? = null, val canExtend: Boolean = false)

/** Thin consumers submit actions here; they never own timers or Room writes. */
interface FocusSessionActions {
    val intervention: StateFlow<InterventionUiModel?>
    val focusStatus: StateFlow<FocusStatusUiModel>
    suspend fun refresh(sessionId: String, sample: ClockSample)
    suspend fun startBreak(sessionId: String, expectedSegmentId: String, durationMillis: Long, sample: ClockSample): FocusActionResult
    suspend fun finishBreak(sessionId: String, expectedSegmentId: String, sample: ClockSample): FocusActionResult
    suspend fun selectAllowanceReason(sessionId: String, promptToken: String, reason: AllowanceReason,
        visible: Boolean, sample: ClockSample): FocusActionResult
    suspend fun setPromptVisible(sessionId: String, promptToken: String, visible: Boolean, sample: ClockSample): FocusActionResult
    suspend fun grantAllowance(sessionId: String, expectedSegmentId: String, promptToken: String,
        packageName: String, reason: AllowanceReason, durationMillis: Long?, sample: ClockSample): FocusActionResult
    suspend fun extendAllowance(sessionId: String, expectedSegmentId: String, actionToken: String, sample: ClockSample): FocusActionResult
    suspend fun finishAllowance(sessionId: String, expectedSegmentId: String, sample: ClockSample): FocusActionResult
    suspend fun dismissPrompt(sessionId: String, promptToken: String, sample: ClockSample): FocusActionResult
    suspend fun returnToStudy(sessionId: String, promptToken: String, sample: ClockSample): FocusActionResult
    /** Fresh device state or visible + window-focused Session page; never cached foreground package. */
    suspend fun observeEvidence(sessionId: String, sample: ClockSample, screenNonInteractive: Boolean,
        sessionPageVisibleAndFocused: Boolean): FocusActionResult
}
