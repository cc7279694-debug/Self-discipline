package com.guanyi.mirra.platform.focus

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

data class LaunchableRiskApp(val packageName: String, val label: String)

/** Explicit, narrow launcher-app inventory. Never requests QUERY_ALL_PACKAGES. */
class RiskAppCatalog(private val context: Context) {
    fun list(): List<LaunchableRiskApp> {
        val manager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val excluded = query(home).map { it.activityInfo.packageName }.toSet() +
            setOf(context.packageName, "com.android.systemui", "com.android.settings")
        return query(launcher).asSequence()
            .filter { it.activityInfo.packageName !in excluded }
            .map { LaunchableRiskApp(it.activityInfo.packageName,
                it.loadLabel(manager).toString().ifBlank { it.activityInfo.packageName }) }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    private fun query(intent: Intent) = if (Build.VERSION.SDK_INT >= 33)
        context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    else @Suppress("DEPRECATION") context.packageManager.queryIntentActivities(intent, 0)
}
