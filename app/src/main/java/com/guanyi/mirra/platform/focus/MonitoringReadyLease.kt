package com.guanyi.mirra.platform.focus

/** Immutable evidence from one successful query sample, not the user's click time. */
data class MonitoringReadyLease(
    val generation: String,
    val readyAtWall: Long,
    val readyAtElapsed: Long,
    val successfulQueryGeneration: Long,
    val cursorWallMillis: Long,
    val lastSuccessfulQueryElapsed: Long,
    val continuityEpoch: Long,
    val notificationVisible: Boolean,
    val dndAccessAvailable: Boolean,
)

class ReadyLeasePolicy(private val ttlMillis: Long = 2_000) {
    fun capture(state: MonitoringCapabilities, nowElapsed: Long): MonitoringReadyLease? {
        if (!state.monitoringReady) return null
        val generation = state.service.generation ?: return null
        val clock = state.monitor.lastSuccessfulQueryClock ?: return null
        val cursor = state.monitor.cursorWallMillis ?: return null
        if (nowElapsed < clock.elapsedNowMillis || nowElapsed - clock.elapsedNowMillis > ttlMillis) return null
        return MonitoringReadyLease(
            generation, clock.wallNowMillis, clock.elapsedNowMillis,
            state.monitor.queryGeneration, cursor, clock.elapsedNowMillis,
            state.monitor.continuityEpoch, state.notification.drawerVisible,
            state.dnd == CapabilityStatus.AVAILABLE,
        )
    }

    fun isValid(lease: MonitoringReadyLease, state: MonitoringCapabilities, nowElapsed: Long): Boolean {
        val clock = state.monitor.lastSuccessfulQueryClock ?: return false
        return state.monitoringReady && state.service.generation == lease.generation &&
            state.monitor.continuityEpoch == lease.continuityEpoch &&
            state.monitor.queryGeneration >= lease.successfulQueryGeneration &&
            state.monitor.cursorWallMillis != null && state.monitor.cursorWallMillis >= lease.cursorWallMillis &&
            clock.elapsedNowMillis >= lease.lastSuccessfulQueryElapsed &&
            nowElapsed >= clock.elapsedNowMillis && nowElapsed - clock.elapsedNowMillis <= ttlMillis &&
            nowElapsed >= lease.readyAtElapsed && nowElapsed - lease.readyAtElapsed <= ttlMillis
    }
}
