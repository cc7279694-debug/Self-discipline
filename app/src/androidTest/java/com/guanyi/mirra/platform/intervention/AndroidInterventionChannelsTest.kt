package com.guanyi.mirra.platform.intervention

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.domain.monitoring.InterventionUiModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test

class AndroidInterventionChannelsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prompt = InterventionUiModel("platform-test", "platform-prompt", "platform-prompt", "seg", "risk", 0, 0)
    @Test fun independentChannelDoesNotBypassDndAndStartupCancelIsIdempotent() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        val channel = NotificationInterventionPresenter(context) { "专用平台测试书" }
        val manager = context.getSystemService(NotificationManager::class.java)
        val prior = manager.currentInterruptionFilter
        channel.ensureChannel()
        val created = manager.getNotificationChannel(NotificationInterventionPresenter.CHANNEL_ID)
        assertEquals("学习提醒", created.name.toString())
        assertFalse(created.canBypassDnd())
        assertNotEquals(com.guanyi.mirra.platform.focus.AndroidNotificationGateway.CHANNEL_ID, created.id)
        channel.clear(); channel.clear()
        assertEquals(prior, manager.currentInterruptionFilter)
    }
    @Test fun grantedNotificationIsPostedButNeverFullScreen() = runBlocking {
        val presenter = NotificationInterventionPresenter(context) { "专用平台测试书" }
        assumeTrue("Notification permission and channel must be granted", presenter.capabilities().notificationAvailable)
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            assertTrue(presenter.show(prompt))
            // notify() acknowledges posting, while NMS enqueues the record asynchronously.
            val notification = kotlinx.coroutines.withTimeout(2_000) {
                var posted = manager.activeNotifications.firstOrNull { it.id == NotificationInterventionPresenter.NOTIFICATION_ID }
                while (posted == null) {
                    kotlinx.coroutines.delay(20)
                    posted = manager.activeNotifications.firstOrNull { it.id == NotificationInterventionPresenter.NOTIFICATION_ID }
                }
                posted.notification
            }
            assertTrue(notification.contentIntent.isActivity)
            assertTrue(notification.contentIntent.isImmutable)
            assertNull(notification.fullScreenIntent)
            assertEquals(1, notification.actions.size)
        } finally { presenter.clear() }
    }
    @Test fun notificationDenialReturnsUnavailableNotPosted() = runBlocking {
        assumeTrue("Runtime notification permission exists on API 33+", Build.VERSION.SDK_INT >= 33)
        // Inject a denied permission check without changing the device's grant during a full run.
        val deniedContext = object : android.content.ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == android.Manifest.permission.POST_NOTIFICATIONS) android.content.pm.PackageManager.PERMISSION_DENIED
                else super.checkPermission(permission, pid, uid)
        }
        val presenter = NotificationInterventionPresenter(deniedContext) { "专用平台测试书" }
        assertFalse(presenter.show(prompt))
    }
    @Test fun grantedOverlayAttachUpdateAndRepeatedRemoveAreSafe() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 26 && Settings.canDrawOverlays(context))
        val presenter = OverlayInterventionPresenter(context, { "专用平台测试书" }, {})
        try {
            assertTrue(presenter.show(prompt))
            assertTrue(presenter.isAttached())
            assertTrue(presenter.show(prompt))
        } finally { presenter.clear(); presenter.clear() }
        assertFalse(presenter.isAttached())
    }
    @Test fun missingOverlayPermissionDoesNotAttachOrCrash() = runBlocking {
        // A denied capability seam; actual denied/granted settings are tested separately on the AVD.
        val presenter = OverlayInterventionPresenter(context, { "专用平台测试书" }, {}, hasOverlayPermission = { false })
        assertFalse(presenter.show(prompt))
        presenter.clear(); presenter.clear()
        assertFalse(presenter.isAttached())
    }
}
