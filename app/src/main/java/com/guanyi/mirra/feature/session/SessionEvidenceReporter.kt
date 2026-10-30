package com.guanyi.mirra.feature.session

import android.os.PowerManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.view.Window
import android.content.Intent
import android.content.IntentFilter
import android.view.ViewTreeObserver
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal fun sessionHasPositivePageEvidence(visible: Boolean, resumed: Boolean, windowFocused: Boolean) =
    visible && resumed && windowFocused

/** Window and lifecycle callbacks revoke evidence immediately; the page loop never backfills gaps. */
@Composable
internal fun SessionEvidenceReporter(viewModel: SessionViewModel, keepScreenOn: Boolean = false,
    onFocusSurfaceChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val requestedScreenOn by rememberUpdatedState(keepScreenOn)
    val surfaceChanged by rememberUpdatedState(onFocusSurfaceChanged)
    val screen = remember(view, owner) { ReadingScreenOnLease({ view.keepScreenOn }, { view.keepScreenOn = it }) }
    val bars = remember(view, owner) { view.context.activityWindow()?.let { WindowCompat.getInsetsController(it, view) } }
    val themePalette = MirraTheme.colors
    // Theme restoration can arrive after Activity recreation. Retire only the old appearance lease,
    // then acquire against the current theme's bars; do not restart the page evidence producer.
    val barLeases = remember(bars, themePalette) { bars?.let { bar ->
        ReadingScreenOnLease({ bar.isAppearanceLightStatusBars }, { bar.isAppearanceLightStatusBars = it }, activeValue = false) to
            ReadingScreenOnLease({ bar.isAppearanceLightNavigationBars }, { bar.isAppearanceLightNavigationBars = it }, activeValue = false)
    } }
    val currentBars by rememberUpdatedState(barLeases)
    DisposableEffect(barLeases) {
        onDispose { barLeases?.first?.close(); barLeases?.second?.close() }
    }
    fun updateWindow(eligible: Boolean) {
        screen.update(eligible); currentBars?.first?.update(eligible); currentBars?.second?.update(eligible)
        surfaceChanged(eligible)
    }
    // Display preference changes do not dispose the evidence producer or manufacture a visibility gap.
    SideEffect {
        updateWindow(keepScreenOn && power.isInteractive &&
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && view.hasWindowFocus())
    }
    DisposableEffect(view, owner, viewModel) {
        var attached = true
        fun report() {
            val visible = sessionHasPositivePageEvidence(attached,
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), view.hasWindowFocus())
            updateWindow(requestedScreenOn && visible && power.isInteractive)
            viewModel.reportEvidence(!power.isInteractive, visible)
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { report() }
        val lifecycle = LifecycleEventObserver { _, _ -> report() }
        val powerChanges = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { report() }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        owner.lifecycle.addObserver(lifecycle)
        ContextCompat.registerReceiver(context, powerChanges, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        report()
        onDispose {
            attached = false
            updateWindow(false)
            report()
            view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            owner.lifecycle.removeObserver(lifecycle)
            context.unregisterReceiver(powerChanges)
        }
    }
    LaunchedEffect(view, owner, viewModel) {
        while (isActive) {
            viewModel.observeFocusEvidence(!power.isInteractive,
                sessionHasPositivePageEvidence(true, owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), view.hasWindowFocus()))
            delay(1_000)
        }
    }
}

private tailrec fun Context.activityWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> if (baseContext !== this) baseContext.activityWindow() else null
    else -> null
}

/** Used inside the allowance dialog's own window, not the obscured Activity window. */
@Composable
internal fun AllowancePanelVisibility(viewModel: SessionViewModel, reasonSelected: Boolean) {
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(view, owner, viewModel, reasonSelected) {
        fun report() {
            if (reasonSelected) viewModel.setPromptVisible(
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && view.hasWindowFocus())
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { report() }
        val lifecycle = LifecycleEventObserver { _, _ -> report() }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        owner.lifecycle.addObserver(lifecycle)
        report()
        onDispose {
            view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            owner.lifecycle.removeObserver(lifecycle)
            if (reasonSelected) viewModel.setPromptVisible(false)
        }
    }
}
