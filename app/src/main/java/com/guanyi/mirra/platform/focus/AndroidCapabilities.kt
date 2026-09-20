package com.guanyi.mirra.platform.focus

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.content.pm.PackageManager

class AndroidUsageAccessGateway(private val context: Context) : UsageAccessGateway {
    private val appOps get() = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private val usageStats get() = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    override fun appOpMode(): UsageOpMode = when (appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
    )) {
        AppOpsManager.MODE_ALLOWED -> UsageOpMode.ALLOWED
        AppOpsManager.MODE_IGNORED -> UsageOpMode.DENIED
        AppOpsManager.MODE_DEFAULT -> UsageOpMode.DEFAULT
        else -> UsageOpMode.ERRORED
    }

    override fun userUnlocked(): Boolean = Build.VERSION.SDK_INT < 24 ||
        (context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked

    override fun canQuery(): Boolean {
        val end = System.currentTimeMillis()
        return usageStats.queryEvents(end - 1_000, end) != null
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}

class AndroidNotificationGateway(private val context: Context) : NotificationGateway {
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun runtimePermissionGranted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun channelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return false
        return manager.areNotificationsEnabled() && channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "学习期间监测", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "仅显示本次学习的前台监测状态" })
        }
    }

    companion object { const val CHANNEL_ID = "mirra_focus_monitoring" }
}

class AndroidDndGateway(private val context: Context) : DndGateway {
    override fun policyAccessGranted(): Boolean =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted

    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
}
