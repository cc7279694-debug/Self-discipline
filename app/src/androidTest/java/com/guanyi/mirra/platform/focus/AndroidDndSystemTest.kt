package com.guanyi.mirra.platform.focus

import android.app.NotificationManager
import android.content.Context
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDndSystemTest {
    @Test fun ownedRuleIsReusableAndNeverChangesGlobalPolicy() {
        assumeTrue("Mirra-owned DND rules require API 29+", Build.VERSION.SDK_INT >= 29)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assumeTrue("DND platform verification requires explicitly granted policy access", manager.isNotificationPolicyAccessGranted)
        val system = AndroidDndSystem(context)
        val before = manager.notificationPolicy
        val existing = system.findOwnedRule()
        val id = existing ?: system.createOwnedRule()
        try {
            assertEquals(id, system.findOwnedRule())
            val policy = manager.getAutomaticZenRule(id)!!.zenPolicy!!
            assertEquals(ZenPolicy.STATE_DISALLOW, policy.priorityCategoryMessages)
            assertEquals(ZenPolicy.PEOPLE_TYPE_NONE, policy.priorityMessageSenders)
            assertEquals(ZenPolicy.STATE_DISALLOW, policy.priorityCategoryEvents)
            assertEquals(ZenPolicy.STATE_DISALLOW, policy.priorityCategoryReminders)
            if (Build.VERSION.SDK_INT >= 30) {
                assertEquals(ZenPolicy.STATE_DISALLOW, policy.priorityCategoryConversations)
                assertEquals(ZenPolicy.CONVERSATION_SENDERS_NONE, policy.priorityConversationSenders)
            }
            assertEquals(ZenPolicy.STATE_DISALLOW, policy.visualEffectFullScreenIntent)
            assertEquals(ZenPolicy.STATE_DISALLOW, policy.visualEffectLights)
            assertEquals(ZenPolicy.STATE_DISALLOW, policy.visualEffectPeek)
            assertEquals(ZenPolicy.STATE_DISALLOW, policy.visualEffectStatusBar)
            assertEquals(ZenPolicy.STATE_DISALLOW, policy.visualEffectBadge)
            assertEquals(ZenPolicy.STATE_DISALLOW, policy.visualEffectAmbient)
            assertEquals(ZenPolicy.STATE_DISALLOW, policy.visualEffectNotificationList)
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

    @Test fun controlledPolicyChangeIsRejected() {
        assumeTrue("Mirra-owned DND rules require API 29+", Build.VERSION.SDK_INT >= 29)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assumeTrue("DND platform verification requires explicitly granted policy access", manager.isNotificationPolicyAccessGranted)
        val system = AndroidDndSystem(context)
        val existing = system.findOwnedRule()
        val id = existing ?: system.createOwnedRule()
        val rule = manager.getAutomaticZenRule(id)!!
        val originalPolicy = rule.zenPolicy
        try {
            rule.zenPolicy = ZenPolicy.Builder()
                .allowMessages(ZenPolicy.PEOPLE_TYPE_ANYONE)
                .allowEvents(false)
                .allowReminders(false)
                .hideAllVisualEffects()
                .apply {
                    if (Build.VERSION.SDK_INT >= 30) {
                        allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
                    }
                }
                .build()
            assertTrue(manager.updateAutomaticZenRule(id, rule))
            assertThrows(IllegalStateException::class.java) { system.activateOwnedRule(id) }
        } finally {
            rule.zenPolicy = originalPolicy
            manager.updateAutomaticZenRule(id, rule)
            runCatching { system.deactivateOwnedRule(id) }
            if (existing == null) manager.removeAutomaticZenRule(id)
        }
    }

    @Test fun accessibleNonMirraRuleIsIgnored() {
        assumeTrue("Mirra-owned DND rules require API 29+", Build.VERSION.SDK_INT >= 29)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assumeTrue("DND platform verification requires explicitly granted policy access", manager.isNotificationPolicyAccessGranted)
        val foreignCondition = Uri.parse("mirra://focus/not-the-reading-rule")
        val foreignRule = android.app.AutomaticZenRule(
            "Not Mirra reading",
            null,
            ComponentName(context, com.guanyi.mirra.MainActivity::class.java),
            foreignCondition,
            ZenPolicy.Builder().allowEvents(false).build(),
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true,
        )
        val foreignId = manager.addAutomaticZenRule(foreignRule)
        try {
            assertNotEquals(foreignId, AndroidDndSystem(context).findOwnedRule())
        } finally {
            manager.removeAutomaticZenRule(foreignId)
        }
    }
}
