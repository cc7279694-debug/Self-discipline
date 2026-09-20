package com.guanyi.mirra.platform.focus

enum class UsageOpMode { ALLOWED, DENIED, DEFAULT, ERRORED }

interface UsageAccessGateway {
    fun appOpMode(): UsageOpMode
    fun userUnlocked(): Boolean
    /** A successful empty query returns true. Null result or failure returns false. */
    fun canQuery(): Boolean
}

class UsageAccessCapability(private val gateway: UsageAccessGateway) {
    /** Runtime recheck only; the poll itself supplies the real query evidence. */
    fun checkLightweight(): CapabilityStatus = try {
        when (gateway.appOpMode()) {
            UsageOpMode.DENIED, UsageOpMode.DEFAULT -> CapabilityStatus.NEEDS_USER_ACTION
            UsageOpMode.ERRORED -> CapabilityStatus.UNAVAILABLE
            UsageOpMode.ALLOWED -> if (gateway.userUnlocked()) CapabilityStatus.AVAILABLE
                else CapabilityStatus.TEMPORARILY_UNAVAILABLE
        }
    } catch (_: SecurityException) {
        CapabilityStatus.NEEDS_USER_ACTION
    } catch (_: RuntimeException) {
        CapabilityStatus.TEMPORARILY_UNAVAILABLE
    }

    fun check(): CapabilityStatus = try {
        when (gateway.appOpMode()) {
            UsageOpMode.DENIED, UsageOpMode.DEFAULT -> CapabilityStatus.NEEDS_USER_ACTION
            UsageOpMode.ERRORED -> CapabilityStatus.UNAVAILABLE
            UsageOpMode.ALLOWED -> when {
                !gateway.userUnlocked() -> CapabilityStatus.TEMPORARILY_UNAVAILABLE
                gateway.canQuery() -> CapabilityStatus.AVAILABLE
                else -> CapabilityStatus.TEMPORARILY_UNAVAILABLE
            }
        }
    } catch (_: SecurityException) {
        CapabilityStatus.NEEDS_USER_ACTION
    } catch (_: RuntimeException) {
        CapabilityStatus.TEMPORARILY_UNAVAILABLE
    }
}

interface NotificationGateway {
    fun runtimePermissionGranted(): Boolean
    fun channelEnabled(): Boolean
}

class NotificationCapability(private val gateway: NotificationGateway) {
    /** Notification denial does not prevent the FGS itself from running. */
    fun check(): NotificationStatus = NotificationStatus(
        fgsCanRun = true,
        drawerVisible = gateway.runtimePermissionGranted() && gateway.channelEnabled(),
    )
}

fun interface DndGateway { fun policyAccessGranted(): Boolean }

class DndCapability(private val gateway: DndGateway) {
    fun check(): CapabilityStatus = try {
        if (gateway.policyAccessGranted()) CapabilityStatus.AVAILABLE else CapabilityStatus.NEEDS_USER_ACTION
    } catch (_: SecurityException) {
        CapabilityStatus.NEEDS_USER_ACTION
    } catch (_: RuntimeException) {
        CapabilityStatus.TEMPORARILY_UNAVAILABLE
    }
}
