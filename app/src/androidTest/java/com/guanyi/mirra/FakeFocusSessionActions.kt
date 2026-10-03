package com.guanyi.mirra

import com.guanyi.mirra.domain.monitoring.*
import kotlinx.coroutines.flow.MutableStateFlow

/** UI fixture only: tests explicitly publish state, never pretend to run the domain kernel. */
class FakeFocusSessionActions : FocusSessionActions {
    override val intervention = MutableStateFlow<InterventionUiModel?>(null)
    override val focusStatus = MutableStateFlow(FocusStatusUiModel())
    val calls = mutableListOf<String>()
    val evidenceSamples = mutableListOf<ClockSample>()
    var result = FocusActionResult.SUCCESS
    private fun call(name: String): FocusActionResult { calls += name; return result }
    override suspend fun refresh(sessionId: String, sample: ClockSample) { calls += "refresh" }
    override suspend fun startBreak(sessionId: String, expectedSegmentId: String, durationMillis: Long, sample: ClockSample) = call("break:$durationMillis")
    override suspend fun finishBreak(sessionId: String, expectedSegmentId: String, sample: ClockSample) = call("finishBreak")
    override suspend fun selectAllowanceReason(sessionId: String, promptToken: String, reason: AllowanceReason, visible: Boolean, sample: ClockSample): FocusActionResult {
        val value = call("reason:$reason")
        if (value == FocusActionResult.SUCCESS) intervention.value = intervention.value?.copy(reason = reason)
        return value
    }
    override suspend fun setPromptVisible(sessionId: String, promptToken: String, visible: Boolean, sample: ClockSample) = call("visible:$visible")
    override suspend fun grantAllowance(sessionId: String, expectedSegmentId: String, promptToken: String, packageName: String, reason: AllowanceReason, durationMillis: Long?, sample: ClockSample) = call("grant:$durationMillis")
    override suspend fun extendAllowance(sessionId: String, expectedSegmentId: String, actionToken: String, sample: ClockSample) = call("extend")
    override suspend fun finishAllowance(sessionId: String, expectedSegmentId: String, sample: ClockSample) = call("finishAllowance")
    override suspend fun dismissPrompt(sessionId: String, promptToken: String, sample: ClockSample): FocusActionResult {
        val value = call("dismiss")
        if (value == FocusActionResult.SUCCESS) intervention.value = intervention.value?.copy(dismissed = true)
        return value
    }
    override suspend fun returnToStudy(sessionId: String, promptToken: String, sample: ClockSample) = dismissPrompt(sessionId, promptToken, sample)
    override suspend fun observeEvidence(sessionId: String, sample: ClockSample, screenNonInteractive: Boolean, sessionPageVisibleAndFocused: Boolean): FocusActionResult {
        evidenceSamples += sample
        return call("evidence:$screenNonInteractive:$sessionPageVisibleAndFocused")
    }
}
