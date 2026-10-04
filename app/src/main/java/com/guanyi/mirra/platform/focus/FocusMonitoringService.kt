package com.guanyi.mirra.platform.focus

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.guanyi.mirra.MainActivity
import com.guanyi.mirra.MirraApplication
import com.guanyi.mirra.R
import com.guanyi.mirra.domain.monitoring.ClockSample
import com.guanyi.mirra.domain.monitoring.MonitoringSignal
import com.guanyi.mirra.domain.monitoring.UsageEventKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A short-lived platform container; it never creates or mutates a Study Session. */
class FocusMonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtime get() = (application as MirraApplication).monitoringPlatform
    private var pollingJob: Job? = null
    private var watchdogJob: Job? = null
    private var monitor: UsageMonitor? = null
    private var registered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val current = monitor ?: return
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    val at = System.currentTimeMillis()
                    current.deviceSignal("SCREEN_OFF", at, UsageEventKind.SCREEN_OFF)
                    val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (keyguard.isDeviceLocked) current.deviceSignal("DEVICE_LOCKED", at, UsageEventKind.DEVICE_LOCKED)
                }
                Intent.ACTION_USER_PRESENT -> current.deviceSignal("USER_PRESENT") // unlock is not distraction
            }
            runtime.capabilities.updateMonitor(current.state.value)
            runtime.reconcileInterventionPresentation()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val generation = intent?.getStringExtra(EXTRA_GENERATION)
        if (intent?.action == ACTION_STOP) {
            if (generation != null && runtime.lifecycle.state.value.generation == generation) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val loss = runCatching {
                        runtime.onMonitoringDeadline(generation,
                            ClockSample(System.currentTimeMillis(), SystemClock.elapsedRealtime()),
                            "notification stop")
                    }
                    if (loss.isSuccess) withContext(Dispatchers.Main.immediate) { stopSelf() }
                    else {
                        runtime.lifecycle.failed(generation, "monitoring loss not saved")
                        runtime.capabilities.updateService(runtime.lifecycle.state.value)
                    }
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || generation.isNullOrBlank()) {
            if (runtime.lifecycle.state.value.phase == ServicePhase.STOPPED) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!runtime.acceptsGeneration(generation)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val lifecycle = runtime.lifecycle
        if (!lifecycle.start(generation)) return START_NOT_STICKY
        if (pollingJob != null) return START_NOT_STICKY // same generation: exactly one loop
        runtime.capabilities.updateService(lifecycle.state.value)
        try {
            runtime.notificationGateway.ensureChannel()
            val notification = notification(generation)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION_ID, notification)
            lifecycle.foregroundAck(generation)
            runtime.capabilities.updateService(lifecycle.state.value)
        } catch (failure: RuntimeException) {
            lifecycle.failed(generation, failure.javaClass.simpleName)
            runtime.capabilities.updateService(lifecycle.state.value)
            stopSelf()
            return START_NOT_STICKY
        }
        lifecycle.monitorInitializing(generation)
        runtime.capabilities.updateService(lifecycle.state.value)
        monitor = runtime.createMonitor()
        registerScreenReceiver()
        pollingJob = scope.launch {
            while (isActive) {
                runtime.recheckUsageWithoutQuery()
                if (runtime.capabilities.state.value.usage != CapabilityStatus.AVAILABLE) {
                    val sample = ClockSample(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                    val saved = runCatching {
                        runtime.onMonitoringDeadline(generation, sample, "usage access unavailable")
                    }.isSuccess
                    if (!saved) {
                        delay(POLL_MILLIS)
                        continue
                    }
                    lifecycle.failed(generation, "usage access unavailable")
                    runtime.capabilities.updateService(lifecycle.state.value)
                    stopSelf()
                    break
                }
                val current = monitor ?: break
                val sample = ClockSample(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                val snapshot = current.poll(sample)
                runtime.capabilities.updateMonitor(snapshot)
                runtime.onMonitorSample(generation, snapshot, sample)
                runtime.reconcileInterventionPresentation()
                if (!snapshot.running) {
                    if (MonitoringSignal.WALL_CLOCK_JUMP in snapshot.signals) {
                        lifecycle.monitorInterrupted(generation)
                        runtime.capabilities.updateService(lifecycle.state.value)
                        delay(POLL_MILLIS)
                        continue // the next query uses the reducer's new wall cursor
                    }
                    // onMonitorSample has already persisted the gap; repeat is idempotent
                    // and protects against an unbound-to-bound transition during the sample.
                    runtime.onMonitoringDeadline(generation, sample,
                        snapshot.lastPlatformError ?: "monitor query interrupted")
                    lifecycle.failed(generation, snapshot.lastPlatformError ?: "monitor query interrupted")
                    runtime.capabilities.updateService(lifecycle.state.value)
                    stopSelf()
                    break
                }
                if (lifecycle.state.value.phase == ServicePhase.MONITOR_INITIALIZING) {
                    lifecycle.monitorReady(generation)
                    runtime.capabilities.updateService(lifecycle.state.value)
                }
                delay(POLL_MILLIS)
            }
        }
        watchdogJob = scope.launch {
            while (isActive) {
                delay(POLL_MILLIS)
                val lastQuery = runtime.capabilities.state.value.monitor.lastSuccessfulQueryElapsed
                val nowElapsed = SystemClock.elapsedRealtime()
                if (runtime.binding?.generation == generation && lastQuery != null &&
                    nowElapsed - lastQuery >= 6_000L) {
                    runtime.onMonitoringDeadline(generation, ClockSample(System.currentTimeMillis(), nowElapsed))
                    lifecycle.failed(generation, "query deadline")
                    runtime.capabilities.updateService(lifecycle.state.value)
                    stopSelf()
                    break
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(screenReceiver, filter)
        registered = true
    }

    private fun notification(generation: String): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1, stopIntent(this, generation),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, AndroidNotificationGateway.CHANNEL_ID)
        else Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Mirra 正在监测本次学习")
            .setContentText("仅观察当前打开的 App，不读取内容")
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "停止监测", stopIntent).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        val generation = runtime.lifecycle.state.value.generation
        pollingJob?.cancel()
        pollingJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        monitor?.stop()
        monitor?.let { runtime.capabilities.updateMonitor(it.state.value) }
        monitor = null
        if (registered) unregisterReceiver(screenReceiver)
        registered = false
        runtime.lifecycle.state.value.generation?.let { runtime.lifecycle.stop(it) }
        runtime.capabilities.updateService(runtime.lifecycle.state.value)
        runtime.onServiceDestroyed(generation)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.guanyi.mirra.focus.START"
        private const val ACTION_STOP = "com.guanyi.mirra.focus.STOP"
        private const val EXTRA_GENERATION = "generation"
        private const val NOTIFICATION_ID = 3001
        private const val POLL_MILLIS = 1_000L

        fun startIntent(context: Context, generation: String): Intent = Intent(context, FocusMonitoringService::class.java)
            .setAction(ACTION_START).putExtra(EXTRA_GENERATION, generation)
        fun stopIntent(context: Context, generation: String): Intent = Intent(context, FocusMonitoringService::class.java)
            .setAction(ACTION_STOP).putExtra(EXTRA_GENERATION, generation)
    }
}
