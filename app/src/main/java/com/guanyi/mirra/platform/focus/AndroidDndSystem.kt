package com.guanyi.mirra.platform.focus

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.guanyi.mirra.MainActivity
import com.guanyi.mirra.domain.DndSystem

/** Only this adapter touches the system's DND APIs. It never changes global Policy. */
class AndroidDndSystem(context: Context) : DndSystem {
    private val appContext = context.applicationContext
    private val manager get() = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val configActivity get() = ComponentName(appContext, MainActivity::class.java)
    private val conditionId: Uri = "mirra://focus/reading-session-v1".toUri()
    override val apiLevel: Int get() = Build.VERSION.SDK_INT

    private var legacyWatching = false
    private var legacyUserChanged = false
    private var expectedLegacyApplyBroadcast = false
    private val legacyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (expectedLegacyApplyBroadcast && currentFilter() == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                expectedLegacyApplyBroadcast = false
                return
            }
            // Any later system change means ownership is no longer provable, even if the user
            // eventually returns to PRIORITY.
            if (legacyWatching) legacyUserChanged = true
        }
    }

    override fun hasAccess() = manager.isNotificationPolicyAccessGranted

    @RequiresApi(29)
    override fun findOwnedRule(): String? {
        if (apiLevel < 29) return null
        val owned = manager.automaticZenRules.filterValues {
            it.conditionId == conditionId && it.configurationActivity == configActivity && it.owner == null
        }
        if (owned.size > 1) error("Multiple Mirra rules require manual reconciliation")
        val match = owned.entries.singleOrNull() ?: return null
        return match.key
    }

    @RequiresApi(29)
    override fun createOwnedRule(): String {
        check(apiLevel >= 29)
        // One stable condition URI allows rediscovery even if the process dies before Room stores the returned ID.
        val rule = AutomaticZenRule("Mirra 学习勿扰", null, configActivity, conditionId,
            policy(), NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
        return manager.addAutomaticZenRule(rule)
    }

    @RequiresApi(29)
    override fun activateOwnedRule(id: String) = setOwnedRuleState(id, Condition.STATE_TRUE)
    @RequiresApi(29)
    override fun deactivateOwnedRule(id: String) = setOwnedRuleState(id, Condition.STATE_FALSE)

    @RequiresApi(29)
    private fun setOwnedRuleState(id: String, state: Int) {
        check(apiLevel >= 29)
        // getAutomaticZenRule returns null for a non-owned ID. Never operate on another app's rule.
        val rule = manager.getAutomaticZenRule(id) ?: error("Mirra rule not accessible")
        check(rule.conditionId == conditionId && rule.configurationActivity == configActivity && rule.owner == null)
        if (state == Condition.STATE_TRUE) {
            check(rule.isEnabled) { "User disabled the Mirra rule" }
            check(rule.interruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY &&
                rule.zenPolicy == policy()) {
                if (Build.VERSION.SDK_INT >= 35 && manager.areAutomaticZenRulesUserManaged())
                    "User-managed rule policy differs; open system settings"
                else "Mirra rule policy differs; do not overwrite user settings"
            }
        }
        manager.setAutomaticZenRuleState(id, Condition(conditionId, "Mirra reading", state))
    }

    @RequiresApi(29)
    private fun policy(): ZenPolicy {
        check(apiLevel >= 29)
        // Calls and repeat callers are left UNSET so Android's/user's call policy is inherited.
        val builder = ZenPolicy.Builder()
            .allowMessages(ZenPolicy.PEOPLE_TYPE_NONE)
            .allowEvents(false)
            .allowReminders(false)
            .hideAllVisualEffects()
        if (Build.VERSION.SDK_INT >= 30) builder.allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
        return builder.build()
    }

    override fun currentFilter(): Int = manager.currentInterruptionFilter

    override fun applyLegacyPriority() {
        check(apiLevel in 23..28)
        legacyUserChanged = false
        expectedLegacyApplyBroadcast = true
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        if (!legacyWatching) {
            ContextCompat.registerReceiver(appContext, legacyReceiver,
                IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
                ContextCompat.RECEIVER_EXPORTED)
            legacyWatching = true
        }
    }

    override fun legacyOwnershipIntact(): Boolean = legacyWatching && !legacyUserChanged &&
        currentFilter() == NotificationManager.INTERRUPTION_FILTER_PRIORITY

    override fun restoreLegacyFilter(priorFilter: Int) {
        check(apiLevel in 23..28 && legacyOwnershipIntact())
        appContext.unregisterReceiver(legacyReceiver)
        legacyWatching = false
        expectedLegacyApplyBroadcast = false
        manager.setInterruptionFilter(priorFilter)
    }
}
