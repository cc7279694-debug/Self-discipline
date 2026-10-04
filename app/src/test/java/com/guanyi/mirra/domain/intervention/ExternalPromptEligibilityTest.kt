package com.guanyi.mirra.domain.intervention

import com.guanyi.mirra.domain.monitoring.ForegroundObservation
import com.guanyi.mirra.domain.monitoring.InterventionUiModel
import com.guanyi.mirra.platform.focus.MonitorSnapshot
import com.guanyi.mirra.platform.intervention.externalPromptEligible
import org.junit.Assert.*
import org.junit.Test

class ExternalPromptEligibilityTest {
    private val prompt = InterventionUiModel("session", "token", "token", "segment", "risk", 0, 0)
    private val healthy = MonitorSnapshot(running = true, lastSuccessfulQueryElapsed = 1_000,
        observation = ForegroundObservation.Package("risk", 1_000, 1_000))
    @Test fun confirmedRiskPromptIsEligible() {
        assertTrue(externalPromptEligible(prompt, healthy, 1_001, true, false))
    }
    @Test fun notificationDrawerCannotInvalidateStillValidPrompt() {
        val systemUi = healthy.copy(observation = ForegroundObservation.Unknown("system intermediary"))
        assertTrue(externalPromptEligible(prompt, systemUi, 1_001, true, false))
    }
    @Test fun riskExitDoesNotInventPromptInvalidation() {
        val launcher = healthy.copy(observation = ForegroundObservation.Package("launcher", 1_000, 1_000))
        assertTrue(externalPromptEligible(prompt, launcher, 1_001, true, false))
    }
    @Test fun absentDismissedOrUnhealthyPromptCannotDeliver() {
        assertFalse(externalPromptEligible(null, healthy, 1_001, true, false))
        assertFalse(externalPromptEligible(prompt.copy(dismissed = true), healthy, 1_001, true, false))
        assertFalse(externalPromptEligible(prompt, healthy.copy(running = false), 1_001, true, false))
        assertFalse(externalPromptEligible(prompt, healthy.copy(lastSuccessfulQueryElapsed = null), 1_001, true, false))
        assertFalse(externalPromptEligible(prompt, healthy, 7_000, true, false))
        assertFalse(externalPromptEligible(prompt, healthy, 999, true, false))
    }
    @Test fun screenOffOrLockedCannotDeliver() {
        assertFalse(externalPromptEligible(prompt, healthy, 1_001, false, false))
        assertFalse(externalPromptEligible(prompt, healthy, 1_001, true, true))
    }
}
