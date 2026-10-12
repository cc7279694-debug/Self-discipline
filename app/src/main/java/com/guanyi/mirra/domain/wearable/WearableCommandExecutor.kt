package com.guanyi.mirra.domain.wearable

import com.guanyi.mirra.domain.*
import com.guanyi.mirra.domain.monitoring.FocusActionResult
import kotlinx.coroutines.CancellationException

/** Captured storage owner. No transport or long-lived application container is consulted here. */
interface WearableCommandExecutor {
    fun allowedActions(snapshot: WearableSnapshot): List<String>
    suspend fun execute(request: WearableRequest, stillAuthorized: () -> Boolean): WearableCommandOutcome
}

class DefaultWearableCommandExecutor(
    private val source: WearableSnapshotSource,
    private val start: suspend (ConfirmedSessionStart, () -> Boolean) -> SessionStartOutcome,
    private val startBreak: suspend (String, String, Long, () -> Boolean) -> FocusActionResult,
    private val finishBreak: suspend (String, String, () -> Boolean) -> FocusActionResult,
    private val admission: suspend (suspend () -> WearableCommandOutcome) -> WearableCommandOutcome,
) : WearableCommandExecutor {
    override fun allowedActions(snapshot: WearableSnapshot): List<String> = when {
        snapshot.phase == WearablePhase.PREPARING && snapshot.preparationConfirmable && snapshot.intentId != null &&
            snapshot.learningItemId != null && snapshot.currentPage != null &&
            !snapshot.firstAction.isNullOrBlank() && !snapshot.firstActionTruncated &&
            snapshot.firstAction.codePointCount(0, snapshot.firstAction.length) <= 180 ->
                listOf("CONFIRM_PREPARATION_AND_START")
        snapshot.phase == WearablePhase.ACTIVE && snapshot.activeSegmentId != null &&
            snapshot.monitorAvailability == MonitorAvailability.AVAILABLE &&
            snapshot.segmentType in listOf("FOCUS", "DEEP_FOCUS") -> listOf("START_BREAK")
        snapshot.phase == WearablePhase.ACTIVE && snapshot.activeSegmentId != null &&
            snapshot.segmentType == "BREAK" -> listOf("FINISH_BREAK")
        else -> emptyList()
    }

    override suspend fun execute(request: WearableRequest, stillAuthorized: () -> Boolean): WearableCommandOutcome = try {
        admission {
            if (!stillAuthorized()) return@admission WearableCommandOutcome("DISABLED")
            val current = source.read(null)
            if (request.action !in allowedActions(current) || !matches(request, current))
                return@admission WearableCommandOutcome("STALE_STATE")
            if (!stillAuthorized()) return@admission WearableCommandOutcome("DISABLED")
            when (request.action) {
                "START_BREAK" -> {
                    val minutes = request.breakMinutes
                    if (minutes != 5 && minutes != 10) return@admission WearableCommandOutcome("FAILED")
                    result(startBreak(checkNotNull(request.expectedSessionId), checkNotNull(request.expectedSegmentId), minutes * 60_000L, stillAuthorized), current.sessionId)
                }
                "FINISH_BREAK" -> result(finishBreak(checkNotNull(request.expectedSessionId), checkNotNull(request.expectedSegmentId), stillAuthorized), current.sessionId)
                "CONFIRM_PREPARATION_AND_START" -> {
                    val outcome = start(ConfirmedSessionStart(checkNotNull(request.expectedIntentId),
                        checkNotNull(request.expectedBookId), checkNotNull(request.expectedPage),
                        checkNotNull(request.expectedFirstAction)), stillAuthorized)
                    WearableCommandOutcome(if (outcome.monitoring == StartMonitoringResult.EXISTING) "ALREADY_APPLIED" else "APPLIED",
                        outcome.session.id, outcome.monitoring == StartMonitoringResult.DEGRADED)
                }
                else -> WearableCommandOutcome("FAILED")
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: SessionStartRequiresPhoneException) { WearableCommandOutcome("REQUIRES_PHONE") }
    catch (_: StalePreparationException) { WearableCommandOutcome("STALE_STATE") }
    catch (_: com.guanyi.mirra.domain.maintenance.MaintenanceUnavailableException) { WearableCommandOutcome("BUSY") }
    catch (_: com.guanyi.mirra.domain.maintenance.RetiredStorageEpochException) { WearableCommandOutcome("DISABLED") }
    // A persistence failure can occur after commit. Do not tell the wrist that nothing happened.
    catch (_: Exception) { WearableCommandOutcome("RESULT_UNKNOWN") }

    private fun result(value: FocusActionResult, sessionId: String?) = when (value) {
        FocusActionResult.SUCCESS -> WearableCommandOutcome("APPLIED", sessionId)
        FocusActionResult.EXPIRED, FocusActionResult.CONFLICT -> WearableCommandOutcome("STALE_STATE")
        FocusActionResult.SAVE_FAILED -> WearableCommandOutcome("RESULT_UNKNOWN")
    }

    private fun matches(request: WearableRequest, snapshot: WearableSnapshot): Boolean = when (request.action) {
        "START_BREAK", "FINISH_BREAK" -> request.expectedSessionId == snapshot.sessionId &&
            request.expectedSegmentId == snapshot.activeSegmentId
        "CONFIRM_PREPARATION_AND_START" -> request.expectedIntentId == snapshot.intentId &&
            request.expectedBookId == snapshot.learningItemId && request.expectedPage == snapshot.currentPage &&
            request.expectedFirstAction == snapshot.firstAction
        else -> false
    }
}

/** Short, opaque grants and result cache are scoped to a single authorized handshake/owner. */
class WearableControlAuthority(private val newId: () -> String) {
    private data class Grant(val node: String, val client: String, val stream: String, val owner: WearableCommandExecutor,
        val body: WearableSnapshot, val actions: List<String>, val issuedAt: Long, val expiresAt: Long)
    private data class Completed(val request: WearableRequest, val outcome: WearableCommandOutcome)
    private val grants = linkedMapOf<String, Grant>()
    private val completed = linkedMapOf<String, Completed>()

    @Synchronized fun clear() { grants.clear(); completed.clear() }
    @Synchronized fun revokeGrants() { grants.clear() }
    @Synchronized fun issue(node: String, client: String, stream: String, owner: WearableCommandExecutor,
        body: WearableSnapshot, now: Long, enabled: Boolean): WearableControlState {
        val actions = if (enabled) owner.allowedActions(body) else emptyList()
        val previous = grants.values.firstOrNull()
        if (previous != null && (previous.node != node || previous.client != client || previous.stream != stream ||
            previous.owner !== owner || binding(previous.body) != binding(body) || previous.actions != actions)) grants.clear()
        if (actions.isEmpty()) return WearableControlState(activeSegmentId = body.activeSegmentId)
        val token = newId()
        val expires = now + 20_000
        grants[token] = Grant(node, client, stream, owner, body, actions, now, expires)
        while (grants.size > 4) grants.remove(grants.keys.first())
        return WearableControlState(body.activeSegmentId, actions, token, expires,
            body.firstAction.takeIf { "CONFIRM_PREPARATION_AND_START" in actions })
    }
    @Synchronized fun valid(request: WearableRequest, node: String, owner: WearableCommandExecutor, now: Long): Boolean {
        val grant = grants[request.controlGrant] ?: return false
        if (node != grant.node || request.clientInstanceId != grant.client || request.streamId != grant.stream ||
            owner !== grant.owner || now < grant.issuedAt || now >= grant.expiresAt || request.action !in grant.actions) return false
        val body = grant.body
        return when (request.action) {
            "START_BREAK", "FINISH_BREAK" -> request.expectedSessionId == body.sessionId && request.expectedSegmentId == body.activeSegmentId
            "CONFIRM_PREPARATION_AND_START" -> request.expectedIntentId == body.intentId && request.expectedBookId == body.learningItemId &&
                request.expectedPage == body.currentPage && request.expectedFirstAction == body.firstAction
            else -> false
        }
    }
    @Synchronized fun cached(request: WearableRequest): WearableCommandOutcome? = completed[request.requestId]?.let {
        if (it.request != request) WearableCommandOutcome("STALE_STATE")
        else if (it.outcome.code == "APPLIED") it.outcome.copy(code = "ALREADY_APPLIED") else it.outcome
    }
    @Synchronized fun remember(request: WearableRequest, outcome: WearableCommandOutcome) {
        // A colliding payload never replaces proof associated with the original request.
        if (completed[request.requestId]?.request?.let { it != request } == true) return
        completed[request.requestId] = Completed(request, outcome)
        while (completed.size > 32) completed.remove(completed.keys.first())
    }
    private fun binding(body: WearableSnapshot) = listOf(body.phase, body.intentId, body.sessionId,
        body.activeSegmentId, body.learningItemId, body.currentPage, body.firstAction, body.segmentType,
        body.coverage, body.monitorAvailability)
}
