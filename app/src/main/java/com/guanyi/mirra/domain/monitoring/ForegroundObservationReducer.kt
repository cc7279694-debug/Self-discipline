package com.guanyi.mirra.domain.monitoring

/** Pure reduction of bounded, overlapping UsageEvent-like query results. */
class ForegroundObservationReducer(
    private val maxForegroundAgeMillis: Long = 20_000,
    private val maxClockDriftMillis: Long = 2_000,
) {
    fun reduce(previous: ObservationState, result: QueryResult): ObservationReduction {
        val clock = result.clock
        val priorClock = previous.lastClock
        if (priorClock != null) {
            val wallDelta = clock.wallNowMillis - priorClock.wallNowMillis
            val elapsedDelta = clock.elapsedNowMillis - priorClock.elapsedNowMillis
            if (elapsedDelta < 0 || kotlin.math.abs(wallDelta - elapsedDelta) > maxClockDriftMillis) {
                return ObservationReduction(
                    previous.copy(
                        observation = ForegroundObservation.Unknown("wall clock changed"),
                        lastForegroundEvidenceElapsed = null,
                        cursorWallMillis = null,
                        lastClock = clock,
                        lastEventWallMillis = null,
                        lastEventKeys = emptySet(),
                        cursorNeedsRebaseline = true,
                        rebaselineAtWallMillis = clock.wallNowMillis,
                    ),
                    setOf(MonitoringSignal.WALL_CLOCK_JUMP, MonitoringSignal.RESET_WALL_CURSOR, MonitoringSignal.GAP),
                )
            }
        }

        if (result is QueryResult.Failure) {
            return ObservationReduction(
                previous.copy(
                    observation = ForegroundObservation.MonitoringUnavailable(result.reason.name),
                    lastForegroundEvidenceElapsed = null,
                    cursorWallMillis = null,
                    lastClock = clock,
                    lastEventWallMillis = null,
                    lastEventKeys = emptySet(),
                    cursorNeedsRebaseline = true,
                    rebaselineAtWallMillis = clock.wallNowMillis,
                ),
                setOf(MonitoringSignal.GAP, MonitoringSignal.RESET_WALL_CURSOR),
            )
        }
        result as QueryResult.Success
        val cursor = previous.cursorWallMillis
        // A delayed duplicate query adds no coverage and must not refresh the heartbeat.
        if (cursor != null && result.windowEndWallMillis <= cursor) return ObservationReduction(previous)
        val canRebaseline = previous.cursorNeedsRebaseline && result.windowStartWallMillis >= (previous.rebaselineAtWallMillis ?: Long.MAX_VALUE)
        if (result.windowEndWallMillis <= result.windowStartWallMillis ||
            (cursor != null && result.windowStartWallMillis > cursor) ||
            (previous.cursorNeedsRebaseline && !canRebaseline)
        ) {
            return ObservationReduction(
                previous.copy(
                    observation = ForegroundObservation.MonitoringUnavailable("query window cannot connect"),
                    lastForegroundEvidenceElapsed = null,
                    cursorWallMillis = null,
                    lastClock = clock,
                    lastEventWallMillis = null,
                    lastEventKeys = emptySet(),
                    cursorNeedsRebaseline = true,
                    rebaselineAtWallMillis = clock.wallNowMillis,
                ),
                setOf(MonitoringSignal.GAP, MonitoringSignal.RESET_WALL_CURSOR),
            )
        }

        var observation = if (previous.cursorNeedsRebaseline) ForegroundObservation.Unknown("rebaseline") else previous.observation
        var lastEvidence = if (previous.cursorNeedsRebaseline) null else previous.lastForegroundEvidenceElapsed
        var lastEventWall = if (previous.cursorNeedsRebaseline) null else previous.lastEventWallMillis
        var lastKeys = if (previous.cursorNeedsRebaseline) emptySet() else previous.lastEventKeys
        result.events.asSequence()
            .filter { it.eventWallMillis >= result.windowStartWallMillis && it.eventWallMillis < result.windowEndWallMillis }
            .sortedBy { it.eventWallMillis }
            .groupBy { it.eventWallMillis }
            .forEach { (at, group) ->
                if (lastEventWall != null && at < lastEventWall) {
                    val active = observation as? ForegroundObservation.Package
                    if (active != null && group.any {
                            it.packageName == active.name &&
                                (active.activityClassName == null || it.className == null ||
                                    it.className == active.activityClassName) &&
                                (it.kind == UsageEventKind.ACTIVITY_PAUSED || it.kind == UsageEventKind.ACTIVITY_STOPPED)
                        }
                    ) {
                        observation = ForegroundObservation.Unknown("late exit of current package")
                        lastEvidence = null
                    }
                    return@forEach
                }
                val distinct = group.toSet() - if (at == lastEventWall) lastKeys else emptySet()
                if (distinct.isEmpty()) return@forEach
                val screenSignal = distinct.map { it.kind }.toSet()
                if (UsageEventKind.DEVICE_LOCKED in screenSignal ||
                        UsageEventKind.SCREEN_OFF in screenSignal) {
                    observation = if (UsageEventKind.DEVICE_LOCKED in screenSignal)
                        ForegroundObservation.DeviceLocked(clock.elapsedNowMillis)
                    else ForegroundObservation.ScreenOff(clock.elapsedNowMillis)
                    lastEvidence = null
                } else if (at == lastEventWall || distinct.size > 1) {
                    observation = ForegroundObservation.Unknown("conflicting same-time events")
                    lastEvidence = null
                } else {
                    val event = distinct.single()
                    observation = when (event.kind) {
                        UsageEventKind.ACTIVITY_RESUMED -> {
                            if (event.packageName.isNullOrBlank()) ForegroundObservation.Unknown("missing package")
                            else ForegroundObservation.Package(event.packageName, clock.elapsedNowMillis, at,
                                event.className)
                        }
                        UsageEventKind.ACTIVITY_PAUSED, UsageEventKind.ACTIVITY_STOPPED -> {
                            if (observation is ForegroundObservation.Package &&
                                observation.name == event.packageName &&
                                (observation.activityClassName == null || event.className == null ||
                                    observation.activityClassName == event.className)
                            ) ForegroundObservation.Unknown("foreground exited") else observation
                        }
                        UsageEventKind.SCREEN_OFF -> ForegroundObservation.ScreenOff(clock.elapsedNowMillis)
                        UsageEventKind.DEVICE_LOCKED -> ForegroundObservation.DeviceLocked(clock.elapsedNowMillis)
                    }
                    lastEvidence = when {
                        event.kind == UsageEventKind.ACTIVITY_RESUMED && observation is ForegroundObservation.Package -> clock.elapsedNowMillis
                        observation !is ForegroundObservation.Package -> null
                        else -> lastEvidence
                    }
                }
                lastEventWall = at
                lastKeys = if (at == previous.lastEventWallMillis) previous.lastEventKeys + group else group.toSet()
            }
        if (observation is ForegroundObservation.Package &&
            (lastEvidence == null || clock.elapsedNowMillis - lastEvidence > maxForegroundAgeMillis)
        ) observation = ForegroundObservation.Unknown("foreground evidence expired")
        return ObservationReduction(previous.copy(
            observation = observation,
            lastSuccessfulQueryElapsed = clock.elapsedNowMillis,
            lastForegroundEvidenceElapsed = lastEvidence,
            cursorWallMillis = result.windowEndWallMillis,
            lastClock = clock,
            lastEventWallMillis = lastEventWall,
            lastEventKeys = lastKeys,
            cursorNeedsRebaseline = false,
            rebaselineAtWallMillis = null,
        ))
    }
}
