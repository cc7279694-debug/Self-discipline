package com.guanyi.mirra.data.backup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Metadata provenance comes from the installed package; failure stays explicitly unknown. */
internal fun installedAppVersion(context: Context): String = try {
    val info = if (Build.VERSION.SDK_INT >= 33)
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    else context.packageManager.getPackageInfo(context.packageName, 0)
    info.versionName?.takeIf { it.length in 1..128 } ?: "UNKNOWN"
} catch (_: Exception) { "UNKNOWN" }
