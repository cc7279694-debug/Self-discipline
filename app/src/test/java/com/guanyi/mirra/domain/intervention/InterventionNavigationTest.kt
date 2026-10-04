package com.guanyi.mirra.domain.intervention

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class InterventionNavigationTest {
    @Test fun failedValidationIsRejectedWithoutPublishingRequest() = runTest {
        val controller = InterventionNavigationController { throw IllegalStateException("storage temporarily unavailable") }
        assertFalse(controller.submit(InterventionNavigationRequest("s", "t", InterventionNavigationAction.VIEW)))
        assertNull(controller.requests.value)
    }
    private val request = InterventionNavigationRequest("s", "risk", InterventionNavigationAction.VIEW)
    @Test fun validRequestIsConsumedOnlyOnce() = runTest {
        val c = InterventionNavigationController { it.sessionId == "s" && it.promptToken == "risk" }
        assertTrue(c.submit(request)); assertEquals(request, c.consume(request.id)); assertNull(c.consume(request.id))
        assertFalse(c.submit(request))
    }
    @Test fun staleTokenNeverPublishesNavigation() = runTest {
        val c = InterventionNavigationController { it.promptToken == "risk" }
        assertFalse(c.submit(request.copy(promptToken = "old"))); assertNull(c.requests.value)
    }
    @Test fun sessionEndingAfterSubmitRejectsConsumption() = runTest {
        var active = true; val c = InterventionNavigationController { active }
        assertTrue(c.submit(request)); active = false
        assertNull(c.consume(request.id)); assertNull(c.requests.value)
    }
    @Test fun processRestartHasNoValidPrompt() = runTest {
        val c = InterventionNavigationController { false }
        assertFalse(c.submit(request)); assertNull(c.consume(request.id))
    }
    @Test fun wrongConsumerCannotRemovePendingAction() = runTest {
        val c = InterventionNavigationController { true }; c.submit(request)
        assertNull(c.consume("wrong")); assertEquals(request, c.requests.value)
    }
    @Test fun actionsRemainNavigationOnlyAndKeepExactIdentity() = runTest {
        for (action in InterventionNavigationAction.entries) {
            val c = InterventionNavigationController { it.sessionId == "s" && it.promptToken == "risk" }
            val r = request.copy(action = action)
            assertTrue(c.submit(r)); assertEquals(action, c.consume(r.id)?.action)
        }
    }
    @Test fun preferenceChangeCannotChangeCapturedActiveSession() {
        val s = SessionInterventionSnapshot(); s.capture("s", true); s.capture("s", false)
        assertTrue(s.enabledFor("s")); assertFalse(s.enabledFor("other"))
        s.clear("old"); assertTrue(s.enabledFor("s")); s.clear("s"); assertFalse(s.enabledFor("s"))
    }
    @Test fun nextSessionUsesNewPreferenceAndDefaultsOff() {
        val s = SessionInterventionSnapshot(); assertFalse(s.enabledFor("s"))
        s.capture("s", false); assertFalse(s.enabledFor("s")); s.clear(); s.capture("new", true)
        assertTrue(s.enabledFor("new")); assertFalse(s.enabledFor("s"))
    }
}
