package com.guanyi.mirra.platform.focus

import com.guanyi.mirra.domain.monitoring.ClockSample
import com.guanyi.mirra.domain.monitoring.ForegroundObservationReducer
import com.guanyi.mirra.domain.monitoring.MonitoringSignal
import com.guanyi.mirra.domain.monitoring.ObservationState
import com.guanyi.mirra.domain.monitoring.QueryFailureReason
import com.guanyi.mirra.domain.monitoring.QueryResult
import com.guanyi.mirra.domain.monitoring.UsageEventFact
import com.guanyi.mirra.domain.monitoring.UsageEventKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun interface UsageEventSource {
    /** Null means that the platform returned no usable query result; empty list is success. */
    fun query(startWallMillis: Long, endWallMillis: Long): List<UsageEventFact>?
}

/** One poll at a time; the owning Service schedules it on an IO coroutine. */
class UsageMonitor(
    private val source: UsageEventSource,
    private val overlapMillis: Long = 3_000,
    private val reducer: ForegroundObservationReducer = ForegroundObservationReducer(),
) {
    private var observationState = ObservationState()
    private val pendingDeviceEvents = ArrayList<UsageEventFact>()
    private val mutableState = MutableStateFlow(MonitorSnapshot())
    val state: StateFlow<MonitorSnapshot> = mutableState

    @Synchronized fun poll(clock: ClockSample): MonitorSnapshot {
        val start = observationState.rebaselineAtWallMillis
            ?: observationState.cursorWallMillis?.let { it - overlapMillis }
            ?: (clock.wallNowMillis - overlapMillis)
        val result = try {
            val events = source.query(start, clock.wallNowMillis)
            if (events == null) QueryResult.Failure(clock, QueryFailureReason.QUERY_FAILED)
            else QueryResult.Success(start, clock.wallNowMillis, clock, events + pendingDeviceEvents).also {
                pendingDeviceEvents.clear()
            }
        } catch (_: SecurityException) {
            QueryResult.Failure(clock, QueryFailureReason.PERMISSION_LOST)
        } catch (_: RuntimeException) {
            QueryResult.Failure(clock, QueryFailureReason.QUERY_FAILED)
        }
        val reduction = reducer.reduce(observationState, result)
        observationState = reduction.state
        val healthy = result is QueryResult.Success && MonitoringSignal.GAP !in reduction.signals &&
            reduction.state.lastSuccessfulQueryElapsed == clock.elapsedNowMillis &&
            !reduction.state.cursorNeedsRebaseline
        val previous = mutableState.value
        val snapshot = previous.copy(
            running = healthy,
            queryGeneration = previous.queryGeneration + if (healthy) 1 else 0,
            lastSuccessfulQueryElapsed = reduction.state.lastSuccessfulQueryElapsed,
            lastSuccessfulQueryClock = if (healthy) clock else previous.lastSuccessfulQueryClock,
            continuityEpoch = previous.continuityEpoch + if (!healthy && previous.running) 1 else 0,
            lastForegroundEvidenceElapsed = reduction.state.lastForegroundEvidenceElapsed,
            cursorWallMillis = reduction.state.cursorWallMillis,
            observation = reduction.state.observation,
            signals = reduction.signals,
            lastPlatformError = (result as? QueryResult.Failure)?.reason?.name,
        )
        mutableState.value = snapshot
        return snapshot
    }

    @Synchronized fun deviceSignal(value: String, eventWallMillis: Long? = null, kind: UsageEventKind? = null) {
        if (eventWallMillis != null && kind != null) pendingDeviceEvents += UsageEventFact(eventWallMillis, kind)
        mutableState.value = mutableState.value.copy(lastDeviceSignal = value)
    }

    @Synchronized fun stop() {
        mutableState.value = mutableState.value.copy(running = false)
    }
}
