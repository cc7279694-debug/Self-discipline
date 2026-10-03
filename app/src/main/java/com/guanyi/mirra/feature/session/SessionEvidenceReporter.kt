package com.guanyi.mirra.feature.session

import android.os.PowerManager
import android.view.ViewTreeObserver
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal fun sessionHasPositivePageEvidence(visible: Boolean, resumed: Boolean, windowFocused: Boolean) =
    visible && resumed && windowFocused

/** Window and lifecycle callbacks revoke evidence immediately; the page loop never backfills gaps. */
@Composable
internal fun SessionEvidenceReporter(viewModel: SessionViewModel) {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(view, owner, viewModel) {
        var attached = true
        fun report() = viewModel.reportEvidence(!power.isInteractive,
            sessionHasPositivePageEvidence(attached, owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), view.hasWindowFocus()))
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { report() }
        val lifecycle = LifecycleEventObserver { _, _ -> report() }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        owner.lifecycle.addObserver(lifecycle)
        report()
        onDispose {
            attached = false
            view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            owner.lifecycle.removeObserver(lifecycle)
            report()
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
