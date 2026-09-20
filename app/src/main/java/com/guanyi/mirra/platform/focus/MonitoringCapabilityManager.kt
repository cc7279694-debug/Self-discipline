package com.guanyi.mirra.platform.focus

import com.guanyi.mirra.domain.monitoring.ForegroundObservation
import com.guanyi.mirra.domain.monitoring.MonitoringSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class CapabilityStatus { AVAILABLE, NEEDS_USER_ACTION, TEMPORARILY_UNAVAILABLE, UNAVAILABLE }

data class NotificationStatus(val fgsCanRun: Boolean = true, val drawerVisible: Boolean = false)

data class MonitorSnapshot(
    val running: Boolean = false,
    val queryGeneration: Long = 0,
    val lastSuccessfulQueryElapsed: Long? = null,
    val lastForegroundEvidenceElapsed: Long? = null,
    val cursorWallMillis: Long? = null,
    val observation: ForegroundObservation = ForegroundObservation.Unknown("not queried"),
    val signals: Set<MonitoringSignal> = emptySet(),
    val lastPlatformError: String? = null,
    val lastDeviceSignal: String? = null,
)

data class MonitoringCapabilities(
    val usage: CapabilityStatus = CapabilityStatus.TEMPORARILY_UNAVAILABLE,
    val notification: NotificationStatus = NotificationStatus(),
    val dnd: CapabilityStatus = CapabilityStatus.TEMPORARILY_UNAVAILABLE,
    val service: ServiceState = ServiceState(),
    val monitor: MonitorSnapshot = MonitorSnapshot(),
) {
    val monitoringReady: Boolean
        get() = usage == CapabilityStatus.AVAILABLE &&
            service.phase == ServicePhase.MONITOR_READY && service.foregroundAck && monitor.running &&
            monitor.lastSuccessfulQueryElapsed != null
}

class MonitoringCapabilityManager {
    private val mutableState = MutableStateFlow(MonitoringCapabilities())
    val state: StateFlow<MonitoringCapabilities> = mutableState

    @Synchronized fun updateUsage(value: CapabilityStatus) { mutableState.value = mutableState.value.copy(usage = value) }
    @Synchronized fun updateNotification(value: NotificationStatus) { mutableState.value = mutableState.value.copy(notification = value) }
    @Synchronized fun updateDnd(value: CapabilityStatus) { mutableState.value = mutableState.value.copy(dnd = value) }
    @Synchronized fun updateService(value: ServiceState) { mutableState.value = mutableState.value.copy(service = value) }
    @Synchronized fun updateMonitor(value: MonitorSnapshot) { mutableState.value = mutableState.value.copy(monitor = value) }
}
