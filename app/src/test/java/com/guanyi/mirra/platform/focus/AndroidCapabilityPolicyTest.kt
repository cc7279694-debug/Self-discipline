package com.guanyi.mirra.platform.focus

import org.junit.Assert.*
import org.junit.Test

class AndroidCapabilityPolicyTest {
    @Test fun `usage allowed still requires a real successful query and empty is success`() {
        val gateway = FakeUsageGateway(mode = UsageOpMode.ALLOWED, queryAvailable = true)
        val capability = UsageAccessCapability(gateway)
        assertEquals(CapabilityStatus.AVAILABLE, capability.check())
        gateway.queryAvailable = false
        assertEquals(CapabilityStatus.TEMPORARILY_UNAVAILABLE, capability.check())
    }

    @Test fun `usage denied default and revoked require user action`() {
        val gateway = FakeUsageGateway(mode = UsageOpMode.DENIED)
        val capability = UsageAccessCapability(gateway)
        assertEquals(CapabilityStatus.NEEDS_USER_ACTION, capability.check())
        gateway.mode = UsageOpMode.DEFAULT
        assertEquals(CapabilityStatus.NEEDS_USER_ACTION, capability.check())
        gateway.mode = UsageOpMode.ALLOWED
        gateway.queryAvailable = true
        assertEquals(CapabilityStatus.AVAILABLE, capability.check())
        gateway.mode = UsageOpMode.DENIED
        assertEquals(CapabilityStatus.NEEDS_USER_ACTION, capability.check())
    }

    @Test fun `locked credential storage is temporary not a permission revocation`() {
        val gateway = FakeUsageGateway(mode = UsageOpMode.ALLOWED, queryAvailable = true, unlocked = false)
        assertEquals(CapabilityStatus.TEMPORARILY_UNAVAILABLE, UsageAccessCapability(gateway).check())
    }

    @Test fun `notification denial hides drawer but cannot disable foreground service`() {
        val gateway = FakeNotificationGateway(runtimePermission = false, channelEnabled = true)
        val capability = NotificationCapability(gateway)
        assertEquals(NotificationStatus(fgsCanRun = true, drawerVisible = false), capability.check())
        gateway.runtimePermission = true
        assertTrue(capability.check().drawerVisible)
        gateway.channelEnabled = false
        assertFalse(capability.check().drawerVisible)
    }

    @Test fun `DND policy check has no apply side effect`() {
        val gateway = FakeDndGateway(false)
        val capability = DndCapability(gateway)
        assertEquals(CapabilityStatus.NEEDS_USER_ACTION, capability.check())
        gateway.granted = true
        assertEquals(CapabilityStatus.AVAILABLE, capability.check())
    }

    private class FakeUsageGateway(
        var mode: UsageOpMode,
        var queryAvailable: Boolean = false,
        var unlocked: Boolean = true,
    ) : UsageAccessGateway {
        override fun appOpMode() = mode
        override fun userUnlocked() = unlocked
        override fun canQuery(): Boolean = queryAvailable
    }

    private class FakeNotificationGateway(
        var runtimePermission: Boolean,
        var channelEnabled: Boolean,
    ) : NotificationGateway {
        override fun runtimePermissionGranted() = runtimePermission
        override fun channelEnabled() = channelEnabled
    }

    private class FakeDndGateway(var granted: Boolean) : DndGateway {
        override fun policyAccessGranted() = granted
    }
}
