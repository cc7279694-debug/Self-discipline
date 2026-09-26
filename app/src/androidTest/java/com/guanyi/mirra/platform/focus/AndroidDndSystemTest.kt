package com.guanyi.mirra.platform.focus

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDndSystemTest {
    @Test fun ownedRuleIsReusableAndNeverChangesGlobalPolicy() {
        if (Build.VERSION.SDK_INT < 29) return
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.isNotificationPolicyAccessGranted) return // Denied path belongs to controller tests.
        val system = AndroidDndSystem(context)
        val before = manager.notificationPolicy
        val existing = system.findOwnedRule()
        val id = existing ?: system.createOwnedRule()
        try {
            assertEquals(id, system.findOwnedRule())
            val policy = manager.getAutomaticZenRule(id)!!.zenPolicy!!
            assertEquals(ZenPolicy.PEOPLE_TYPE_NONE, policy.priorityMessageSenders)
            assertEquals(ZenPolicy.STATE_DISALLOW, policy.visualEffectNotificationList)
            // UNSET is resolved by Android to the user's current system DND call policy.
            assertEquals(manager.notificationPolicy.priorityCallSenders, policy.priorityCallSenders)
            system.activateOwnedRule(id)
            if (Build.VERSION.SDK_INT >= 35) {
                assertEquals(Condition.STATE_TRUE, manager.getAutomaticZenRuleState(id))
            }
            system.deactivateOwnedRule(id)
            if (Build.VERSION.SDK_INT >= 35) {
                assertEquals(Condition.STATE_FALSE, manager.getAutomaticZenRuleState(id))
            }
            assertEquals(before, manager.notificationPolicy)
        } finally {
            system.deactivateOwnedRule(id)
            if (existing == null) manager.removeAutomaticZenRule(id)
        }
    }
}
