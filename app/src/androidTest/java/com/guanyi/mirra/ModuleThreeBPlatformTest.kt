package com.guanyi.mirra

import android.app.usage.UsageEvents
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.domain.monitoring.UsageEventKind
import com.guanyi.mirra.platform.focus.AndroidNotificationGateway
import com.guanyi.mirra.platform.focus.AndroidUsageEventSource
import com.guanyi.mirra.platform.focus.FocusMonitoringService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModuleThreeBPlatformTest {
    @Test fun androidUsageEventsMapToDomainFacts() {
        val resumed = AndroidUsageEventSource.mapEvent(UsageEvents.Event.ACTIVITY_RESUMED, 1234, "reader", "Main")
        assertEquals(UsageEventKind.ACTIVITY_RESUMED, resumed?.kind)
        assertEquals("reader", resumed?.packageName)
        assertEquals(1234L, resumed?.eventWallMillis)
        assertEquals(UsageEventKind.SCREEN_OFF,
            AndroidUsageEventSource.mapEvent(UsageEvents.Event.SCREEN_NON_INTERACTIVE, 2345, null, null)?.kind)
        assertEquals(UsageEventKind.DEVICE_LOCKED,
            AndroidUsageEventSource.mapEvent(UsageEvents.Event.KEYGUARD_SHOWN, 3456, null, null)?.kind)
        assertNull(AndroidUsageEventSource.mapEvent(UsageEvents.Event.KEYGUARD_HIDDEN, 4567, null, null))
    }

    @Test fun serviceIsPrivateAndDeclaresSpecialUseForegroundType() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getServiceInfo(
            ComponentName(context, FocusMonitoringService::class.java), PackageManager.ComponentInfoFlags.of(0),
        ) else @Suppress("DEPRECATION") context.packageManager.getServiceInfo(
            ComponentName(context, FocusMonitoringService::class.java), 0,
        )
        assertFalse(info.exported)
        if (Build.VERSION.SDK_INT >= 34) {
            assertTrue(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0)
        }
    }

    @Test fun notificationChannelExists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val gateway = AndroidNotificationGateway(context)
        gateway.ensureChannel()
        val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 26) assertNotNull(manager.getNotificationChannel(AndroidNotificationGateway.CHANNEL_ID))
    }
}
