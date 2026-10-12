package com.guanyi.mirra.domain.wearable

import org.junit.Assert.*
import org.junit.Test

class WearableControlAuthorityTest {
    private val focus = WearableSnapshot(WearablePhase.ACTIVE, sessionId = "session", learningItemId = "book",
        currentPage = 1, totalPages = 300, segmentType = "FOCUS", coverage = "FULL",
        monitorAvailability = MonitorAvailability.AVAILABLE, activeSegmentId = "segment-one")
    private fun newOwner() = object : WearableCommandExecutor {
        override fun allowedActions(snapshot: WearableSnapshot) = when (snapshot.segmentType) {
            "FOCUS" -> listOf("START_BREAK")
            "BREAK" -> listOf("FINISH_BREAK")
            else -> if (snapshot.phase == WearablePhase.PREPARING) listOf("CONFIRM_PREPARATION_AND_START") else emptyList()
        }
        override suspend fun execute(request: WearableRequest, stillAuthorized: () -> Boolean): WearableCommandOutcome = error("grant validation must not execute business")
    }
    private fun command(grant: WearableControlState, id: String = "command") = WearableRequest(2, "COMMAND", "client", id,
        streamId = "stream", controlGrant = grant.controlGrant, action = "START_BREAK",
        expectedSessionId = "session", expectedSegmentId = "segment-one", breakMinutes = 5)
    @Test fun `grants bind authorized node client stream and the exact storage owner`() {
        var next = 0
        val authority = WearableControlAuthority { "grant-${++next}" }
        val owner = newOwner()
        val grant = authority.issue("node", "client", "stream", owner, focus, 1_000, true)
        assertEquals(listOf("START_BREAK"), grant.allowedActions)
        val command = command(grant)
        assertTrue(authority.valid(command, "node", owner, 1_001))
        assertFalse(authority.valid(command, "another-node", owner, 1_001))
        assertFalse(authority.valid(command.copy(clientInstanceId = "another-client"), "node", owner, 1_001))
        assertFalse(authority.valid(command.copy(streamId = "another-stream"), "node", owner, 1_001))
        assertFalse(authority.valid(command, "node", newOwner(), 1_001))
        assertFalse(authority.valid(command.copy(expectedSessionId = "another-session"), "node", owner, 1_001))
        assertFalse(authority.valid(command.copy(expectedSegmentId = "older-segment"), "node", owner, 1_001))
        assertFalse(authority.valid(command.copy(action = "FINISH_BREAK", breakMinutes = null), "node", owner, 1_001))
    }
    @Test fun `grants expire at the deadline and reject backward phone clocks`() {
        val authority = WearableControlAuthority { "grant" }; val owner = newOwner()
        val grant = authority.issue("node", "client", "stream", owner, focus, 1_000, true)
        val command = command(grant)
        assertTrue(authority.valid(command, "node", owner, checkNotNull(grant.grantExpiresAtUnixMs) - 1))
        assertFalse(authority.valid(command, "node", owner, checkNotNull(grant.grantExpiresAtUnixMs)))
        assertFalse(authority.valid(command, "node", owner, 999))
    }
    @Test fun `changed business identity revokes an earlier state authorization`() {
        var next = 0
        val authority = WearableControlAuthority { "grant-${++next}" }; val owner = newOwner()
        val old = authority.issue("node", "client", "stream", owner, focus, 1_000, true)
        authority.issue("node", "client", "stream", owner, focus.copy(segmentType = "BREAK", activeSegmentId = "segment-two"), 1_001, true)
        assertFalse(authority.valid(command(old), "node", owner, 1_002))
    }
    @Test fun `preparation grants bind displayed action page book and active intent`() {
        val authority = WearableControlAuthority { "grant" }; val owner = newOwner()
        val body = WearableSnapshot(WearablePhase.PREPARING, intentId = "intent", learningItemId = "book",
            firstAction = "翻到第 1 页，读第一段", currentPage = 1, totalPages = 300)
        val grant = authority.issue("node", "client", "stream", owner, body, 1_000, true)
        val request = WearableRequest(2, "COMMAND", "client", "start", "stream", controlGrant = grant.controlGrant,
            action = "CONFIRM_PREPARATION_AND_START", expectedIntentId = "intent", expectedBookId = "book",
            expectedPage = 1, expectedFirstAction = "翻到第 1 页，读第一段")
        assertTrue(authority.valid(request, "node", owner, 1_001))
        listOf(request.copy(expectedIntentId = "another-intent"), request.copy(expectedBookId = "another-book"),
            request.copy(expectedPage = 2), request.copy(expectedFirstAction = "另一段")).forEach {
            assertFalse(authority.valid(it, "node", owner, 1_001))
        }
    }
    @Test fun `disabled controls issue no grant and revocation preserves provable results`() {
        val authority = WearableControlAuthority { "grant" }; val owner = newOwner()
        val disabled = authority.issue("node", "client", "stream", owner, focus, 1_000, false)
        assertNull(disabled.controlGrant); assertNull(disabled.grantExpiresAtUnixMs); assertTrue(disabled.allowedActions.isEmpty())
        val request = command(authority.issue("node", "client", "stream", owner, focus, 1_000, true))
        authority.remember(request, WearableCommandOutcome("APPLIED", "session"))
        authority.revokeGrants()
        assertFalse(authority.valid(request, "node", owner, 1_001))
        assertEquals("ALREADY_APPLIED", authority.cached(request)?.code)
        authority.clear()
        assertNull(authority.cached(request))
    }
    @Test fun `result cache is bounded and a reused request id with altered payload is stale`() {
        val authority = WearableControlAuthority { "grant" }; val owner = newOwner()
        val base = command(authority.issue("node", "client", "stream", owner, focus, 1_000, true))
        for (index in 0..32) authority.remember(base.copy(requestId = "command-$index"), WearableCommandOutcome("APPLIED", "session"))
        assertNull(authority.cached(base.copy(requestId = "command-0")))
        assertEquals("ALREADY_APPLIED", authority.cached(base.copy(requestId = "command-1"))?.code)
        assertEquals("STALE_STATE", authority.cached(base.copy(requestId = "command-32", breakMinutes = 10))?.code)
        authority.clear()
        assertNull(authority.cached(base.copy(requestId = "command-32")))
    }
    @Test fun `unconfirmed outcomes remain unconfirmed when identical requests are repeated`() {
        val authority = WearableControlAuthority { "grant" }; val owner = newOwner()
        val request = command(authority.issue("node", "client", "stream", owner, focus, 1_000, true))
        for (code in listOf("RESULT_UNKNOWN", "FAILED", "REQUIRES_PHONE", "DISABLED", "STALE_STATE")) {
            authority.remember(request, WearableCommandOutcome(code))
            assertEquals(code, authority.cached(request)?.code)
        }
    }
}
