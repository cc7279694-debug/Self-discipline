package com.guanyi.mirra.platform.intervention

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.app.ActivityOptions
import android.net.Uri
import android.os.Build
import com.guanyi.mirra.MainActivity
import com.guanyi.mirra.domain.intervention.InterventionNavigationAction
import com.guanyi.mirra.domain.intervention.InterventionNavigationRequest

/** Only explicit user clicks send this Activity PendingIntent; never a background trampoline. */
object InterventionActivityIntents {
    private const val ACTION = "com.guanyi.mirra.intervention.OPEN"
    private fun uri(request: InterventionNavigationRequest): Uri = Uri.Builder().scheme("mirra")
        .authority("intervention").appendPath(request.sessionId).appendPath(request.promptToken)
        .appendPath(request.action.name).build()
    fun intent(context: Context, request: InterventionNavigationRequest): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION).setData(uri(request))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra("sessionId", request.sessionId).putExtra("promptToken", request.promptToken)
        .putExtra("requestedAction", request.action.name)
    fun read(context: Context, intent: Intent): InterventionNavigationRequest? {
        if (intent.action != ACTION || intent.component?.className != MainActivity::class.java.name ||
            intent.component?.packageName != context.packageName) return null
        val session = intent.getStringExtra("sessionId")?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        // Frozen risk IDs include two UUIDs, package name and timestamp; they are not just UUIDs.
        val token = intent.getStringExtra("promptToken")?.takeIf { it.isNotBlank() && it.length <= 1024 } ?: return null
        val action = intent.getStringExtra("requestedAction")?.let { value ->
            InterventionNavigationAction.entries.firstOrNull { it.name == value }
        } ?: return null
        val request = InterventionNavigationRequest(session, token, action)
        return request.takeIf { intent.data == uri(it) }
    }
    fun pendingIntent(context: Context, request: InterventionNavigationRequest): PendingIntent =
        PendingIntent.getActivity(context, 0, intent(context, request),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ActivityOptions.makeBasic().apply {
                if (Build.VERSION.SDK_INT >= 35) setPendingIntentCreatorBackgroundActivityStartMode(
                    if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                    else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle())
    fun sendFromOverlay(context: Context, request: InterventionNavigationRequest) {
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= 34) setPendingIntentBackgroundActivityStartMode(
                if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        pendingIntent(context, request).send(context, 0, null, null, null, null, options.toBundle())
    }
}
