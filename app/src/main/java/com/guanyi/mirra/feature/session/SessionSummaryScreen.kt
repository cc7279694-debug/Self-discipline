package com.guanyi.mirra.feature.session

import androidx.compose.runtime.Composable
import com.guanyi.mirra.feature.profile.DndUserActions

@Composable
fun SessionSummaryScreen(viewModel: ReadingRecordViewModel, dndActions: DndUserActions, onDone: () -> Unit) {
    ReadingRecordPage(viewModel, justSaved = true, onExit = onDone, dndActions = dndActions)
}
