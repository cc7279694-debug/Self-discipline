package com.guanyi.mirra.domain.wearable

import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.domain.*
import com.guanyi.mirra.domain.monitoring.FocusActionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WearableCommandExecutorTest {
    private val focus = WearableSnapshot(WearablePhase.ACTIVE, sessionId = "session", learningItemId = "book",
        currentPage = 1, totalPages = 10, firstAction = "读第一段", segmentType = "FOCUS", coverage = "FULL",
        monitorAvailability = MonitorAvailability.AVAILABLE, activeSegmentId = "segment")
    private val prep = focus.copy(phase = WearablePhase.PREPARING, intentId = "intent", sessionId = null, segmentType = null, activeSegmentId = null, preparationConfirmable = true)
    private fun command(action: String = "START_BREAK", minutes: Int? = 5) = WearableRequest(2, "COMMAND", "client", "req", "stream",
        controlGrant = "grant", action = action, expectedSessionId = "session", expectedSegmentId = "segment", breakMinutes = minutes)
    private fun confirmation() = command("CONFIRM_PREPARATION_AND_START", null).copy(expectedIntentId = "intent",
        expectedSessionId = null, expectedSegmentId = null, expectedBookId = "book", expectedPage = 1, expectedFirstAction = "读第一段")
    private val session = StudySessionEntity("created", "book", "intent", 100, null, null, 1, 1, null, null, null, 1)
    private class Effects { var breaks = 0; var finishes = 0; var starts = 0; var duration = 0L }
    private fun executor(body: () -> WearableSnapshot, effects: Effects,
        startResult: suspend (ConfirmedSessionStart, () -> Boolean) -> SessionStartOutcome = { _, _ -> error("Unexpected start") }) =
        DefaultWearableCommandExecutor(WearableSnapshotSource { body() },
            start = { expected, allowed -> effects.starts++; startResult(expected, allowed) },
            startBreak = { session, segment, duration, authorized ->
                assertEquals("session", session); assertEquals("segment", segment)
                assertTrue(authorized())
                effects.breaks++; effects.duration = duration; FocusActionResult.SUCCESS },
            finishBreak = { session, segment, authorized ->
                assertEquals("session", session); assertEquals("segment", segment)
                assertTrue(authorized())
                effects.finishes++; FocusActionResult.SUCCESS }, admission = { it() })

    @Test fun `allowed 5 and 10 minute breaks call existing business once with exact identities`() = runTest {
        for ((minutes, duration) in listOf(5 to 300_000L, 10 to 600_000L)) {
            val effects = Effects(); val result = executor({ focus }, effects).execute(command(minutes = minutes)) { true }
            assertEquals(WearableCommandOutcome("APPLIED", "session"), result)
            assertEquals(1, effects.breaks); assertEquals(duration, effects.duration); assertEquals(0, effects.finishes)
        }
    }
    @Test fun `finish break uses existing finish instead of a focus shortcut`() = runTest {
        val effects = Effects(); val result = executor({ focus.copy(segmentType = "BREAK") }, effects)
            .execute(command("FINISH_BREAK", null)) { true }
        assertEquals("APPLIED", result.code); assertEquals(1, effects.finishes); assertEquals(0, effects.breaks)
    }
    @Test fun `stale segment and unavailable monitoring reject before business action`() = runTest {
        for (body in listOf(focus.copy(activeSegmentId = "later"), focus.copy(monitorAvailability = MonitorAvailability.UNAVAILABLE))) {
            val effects = Effects(); assertEquals("STALE_STATE", executor({ body }, effects).execute(command()) { true }.code)
            assertEquals(0, effects.breaks)
        }
    }
    @Test fun `saving ended recovery and unmonitored never expose quick controls`() {
        val executor = executor({ focus }, Effects())
        for (body in listOf(focus.copy(phase = WearablePhase.SAVING), focus.copy(phase = WearablePhase.ENDED),
            focus.copy(segmentType = "RECOVERY"), focus.copy(segmentType = "UNMONITORED"), WearableSnapshot(WearablePhase.IDLE)))
            assertTrue(executor.allowedActions(body).isEmpty())
    }
    @Test fun `revocation during state read prevents a write`() = runTest {
        var allowed = true; val effects = Effects()
        val result = executor({ allowed = false; focus }, effects).execute(command()) { allowed }
        assertEquals("DISABLED", result.code); assertEquals(0, effects.breaks)
    }
    @Test fun `edited action page and book reject old confirmed preparation`() = runTest {
        for (body in listOf(prep.copy(firstAction = "新动作"), prep.copy(currentPage = 2), prep.copy(learningItemId = "other"))) {
            val effects = Effects(); val result = executor({ body }, effects).execute(confirmation()) { true }
            assertEquals("STALE_STATE", result.code); assertEquals(0, effects.starts)
        }
    }
    @Test fun `truncated or overlong first action has no confirm control`() {
        val executor = executor({ prep }, Effects())
        assertTrue(executor.allowedActions(prep.copy(firstActionTruncated = true)).isEmpty())
        assertTrue(executor.allowedActions(prep.copy(firstAction = "中".repeat(181))).isEmpty())
    }
    @Test fun `invalid preparation does not issue grant or invoke monitored start`() = runTest {
        val body = prep.copy(preparationConfirmable = false)
        val effects = Effects()
        val executor = executor({ body }, effects) { _, _ -> SessionStartOutcome(session, StartMonitoringResult.MONITORED) }
        assertTrue(executor.allowedActions(body).isEmpty())
        assertEquals("STALE_STATE", executor.execute(confirmation()) { true }.code)
        assertEquals(0, effects.starts)
    }
    @Test fun `strict start forwards confirmed fact and reports real degradation after commit`() = runTest {
        val effects = Effects()
        val result = executor({ prep }, effects) { expected, allowed ->
            assertEquals(ConfirmedSessionStart("intent", "book", 1, "读第一段"), expected); assertTrue(allowed())
            SessionStartOutcome(session, StartMonitoringResult.DEGRADED)
        }.execute(confirmation()) { true }
        assertEquals(WearableCommandOutcome("APPLIED", "created", true), result); assertEquals(1, effects.starts)
    }
    @Test fun `precommit monitoring rejection requires phone while uncertain persistence requires sync`() = runTest {
        for ((failure, expected) in listOf(SessionStartRequiresPhoneException("not ready") to "REQUIRES_PHONE",
            StalePreparationException("changed") to "STALE_STATE", IllegalStateException("commit uncertain") to "RESULT_UNKNOWN")) {
            val result = executor({ prep }, Effects()) { _, _ -> throw failure }.execute(confirmation()) { true }
            assertEquals(expected, result.code); assertNull(result.sessionId)
        }
    }
}
