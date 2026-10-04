package com.guanyi.mirra.platform.intervention

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.guanyi.mirra.R
import com.guanyi.mirra.domain.intervention.*
import com.guanyi.mirra.domain.monitoring.InterventionUiModel
import com.guanyi.mirra.ui.theme.MirraPalettes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class NotificationInterventionPresenter(private val context: Context, private val bookName: () -> String) : ExternalInterventionChannel {
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "学习提醒", NotificationManager.IMPORTANCE_HIGH))
    }
    fun capabilities(): DeliveryCapabilities {
        ensureChannel()
        val permission = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return DeliveryCapabilities(Build.VERSION.SDK_INT, Settings.canDrawOverlays(context), permission,
            NotificationManagerCompat.from(context).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 26 ||
                manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE))
    }
    override suspend fun show(prompt: InterventionUiModel): Boolean {
        if (!capabilities().notificationAvailable) return false
        val request = InterventionNavigationRequest(prompt.sessionId, prompt.promptToken, InterventionNavigationAction.VIEW)
        val intent = InterventionActivityIntents.pendingIntent(context, request)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID) else Notification.Builder(context)
        manager.notify(NOTIFICATION_ID, builder.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("还在读《${bookName()}》").setContentText("返回 Mirra 继续本次学习")
            .setContentIntent(intent).setOnlyAlertOnce(true).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "查看", intent).build()).build())
        return true // POSTED, never a claim of visibility (DND may suppress it).
    }
    override suspend fun clear() { manager.cancel(NOTIFICATION_ID) }
    fun settingsIntent(): Intent = if (Build.VERSION.SDK_INT >= 26)
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    companion object {
        const val CHANNEL_ID = "mirra_focus_intervention"
        const val NOTIFICATION_ID = 3002
    }
}

/** A bounded native panel, not an Activity, not a full-screen touch interceptor. */
class OverlayInterventionPresenter(private val context: Context, private val bookName: () -> String,
    private val close: (InterventionUiModel) -> Unit,
    private val navigate: (InterventionNavigationRequest) -> Unit = { InterventionActivityIntents.sendFromOverlay(context, it) },
    private val hasOverlayPermission: () -> Boolean = { Settings.canDrawOverlays(context) },
) : ExternalInterventionChannel {
    private val windows = context.getSystemService(WindowManager::class.java)
    @Volatile private var panel: LinearLayout? = null
    private var token: String? = null
    override fun isAttached(): Boolean = panel?.isAttachedToWindow == true
    override suspend fun show(prompt: InterventionUiModel): Boolean = withContext(Dispatchers.Main.immediate) {
        if (Build.VERSION.SDK_INT < 26 || !hasOverlayPermission()) return@withContext false
        val layout = parameters()
        if (token == prompt.promptToken && isAttached()) {
            windows.updateViewLayout(panel, layout)
            return@withContext true
        }
        clear()
        val colors = MirraPalettes.blue
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            elevation = dp(4).toFloat()
            background = GradientDrawable().apply { setColor(colors.surface.toArgb()); cornerRadius = dp(20).toFloat() }
            addView(TextView(context).apply { text = context.getString(R.string.intervention_book_title, bookName()); textSize = 18f
                setTextColor(colors.textPrimary.toArgb()); maxLines = 2 })
            addView(TextView(context).apply { setText(R.string.intervention_return_body); textSize = 14f
                setTextColor(colors.textSecondary.toArgb()) })
            fun action(label: String, requested: InterventionNavigationAction) {
                addView(Button(context).apply { text = label; minHeight = dp(48)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(colors.accentSoft.toArgb())
                    setTextColor(colors.accentStrong.toArgb()); isAllCaps = false
                    setOnClickListener { navigate(InterventionNavigationRequest(prompt.sessionId, prompt.promptToken, requested)) } })
            }
            action("回到学习", InterventionNavigationAction.RETURN_TO_STUDY)
            action("临时使用", InterventionNavigationAction.OPEN_ALLOWANCE)
            action("打开 Mirra 结束", InterventionNavigationAction.OPEN_FINISH)
            addView(Button(context).apply { text = "关闭"; minHeight = dp(48); isAllCaps = false
                backgroundTintList = android.content.res.ColorStateList.valueOf(colors.surfacePressed.toArgb())
                setTextColor(colors.textSecondary.toArgb()); setOnClickListener { close(prompt) } })
        }
        // Keep handle if addView throws after partial attachment, so cleanup can still remove it.
        panel = view
        windows.addView(view, layout)
        token = prompt.promptToken
        // addView returns before the first traversal attaches the ViewRoot on some devices.
        // Only an actual attachment is a receipt; bounded cancellation removes the listener.
        withTimeoutOrNull(2_000) {
            if (view.isAttachedToWindow) true else suspendCancellableCoroutine { continuation ->
                val listener = object : android.view.View.OnAttachStateChangeListener {
                    private fun finish(attached: Boolean) {
                        view.removeOnAttachStateChangeListener(this)
                        if (continuation.isActive) continuation.resume(attached)
                    }
                    override fun onViewAttachedToWindow(v: android.view.View) = finish(true)
                    override fun onViewDetachedFromWindow(v: android.view.View) = finish(false)
                }
                view.addOnAttachStateChangeListener(listener)
                continuation.invokeOnCancellation { view.removeOnAttachStateChangeListener(listener) }
                if (view.isAttachedToWindow) listener.onViewAttachedToWindow(view)
            }
        } == true
    }
    override suspend fun clear() = withContext(Dispatchers.Main.immediate) {
        val current = panel ?: return@withContext
        try { windows.removeViewImmediate(current) }
        catch (absent: IllegalArgumentException) { if (current.isAttachedToWindow) throw absent }
        if (!current.isAttachedToWindow) { panel = null; token = null }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    @androidx.annotation.RequiresApi(26)
    private fun parameters() = WindowManager.LayoutParams(
        minOf(dp(304), context.resources.displayMetrics.widthPixels - dp(32)), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(64) }
    companion object {
        fun settingsIntent(context: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${context.packageName}".toUri())
    }
}
