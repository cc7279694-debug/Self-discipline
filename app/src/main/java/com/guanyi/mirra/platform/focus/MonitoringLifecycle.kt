package com.guanyi.mirra.platform.focus

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ServicePhase { STOPPED, STARTING, FOREGROUND_ACK, MONITOR_INITIALIZING, MONITOR_READY, FAILED }

data class ServiceState(
    val phase: ServicePhase = ServicePhase.STOPPED,
    val generation: String? = null,
    val foregroundAck: Boolean = false,
    val lastError: String? = null,
)

/** The service lifecycle is independent of Session and Room facts. */
class MonitoringLifecycle {
    private val mutableState = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = mutableState

    @Synchronized fun start(generation: String): Boolean {
        if (generation.isBlank()) return false
        val current = mutableState.value
        if (current.phase != ServicePhase.STOPPED && current.phase != ServicePhase.FAILED) {
            return current.generation == generation
        }
        mutableState.value = ServiceState(ServicePhase.STARTING, generation)
        return true
    }

    @Synchronized fun foregroundAck(generation: String): Boolean = move(
        generation, ServicePhase.STARTING, ServicePhase.FOREGROUND_ACK, acknowledged = true,
    )

    @Synchronized fun monitorInitializing(generation: String): Boolean = move(
        generation, ServicePhase.FOREGROUND_ACK, ServicePhase.MONITOR_INITIALIZING,
    )

    @Synchronized fun monitorReady(generation: String): Boolean = move(
        generation, ServicePhase.MONITOR_INITIALIZING, ServicePhase.MONITOR_READY,
    )

    @Synchronized fun monitorInterrupted(generation: String): Boolean = move(
        generation, ServicePhase.MONITOR_READY, ServicePhase.MONITOR_INITIALIZING,
    )

    @Synchronized fun failed(generation: String, reason: String): Boolean {
        if (mutableState.value.generation != generation || mutableState.value.phase == ServicePhase.STOPPED) return false
        mutableState.value = mutableState.value.copy(phase = ServicePhase.FAILED, lastError = reason)
        return true
    }

    @Synchronized fun stop(generation: String): Boolean {
        if (mutableState.value.generation != generation) return false
        mutableState.value = ServiceState(lastError = mutableState.value.lastError)
        return true
    }

    private fun move(generation: String, from: ServicePhase, to: ServicePhase, acknowledged: Boolean = false): Boolean {
        val current = mutableState.value
        if (current.generation != generation) return false
        if (current.phase == to) return true
        if (current.phase != from) return false
        mutableState.value = current.copy(phase = to, foregroundAck = current.foregroundAck || acknowledged)
        return true
    }
}
