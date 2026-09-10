package com.guanyi.mirra.feature.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.data.repository.NoteRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class SessionSummaryViewModel(
    sessionId: String,
    workflow: StudyWorkflowRepository,
    notesRepository: NoteRepository,
) : ViewModel() {
    val session = workflow.observeSession(sessionId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )
    val noteCount = notesRepository.observeForSession(sessionId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
}

@Composable
fun SessionSummaryScreen(viewModel: SessionSummaryViewModel, onDone: () -> Unit) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val notes by viewModel.noteCount.collectAsStateWithLifecycle()
    val value = session
    val durationMinutes = if (value?.endedAt != null) {
        ((value.endedAt - value.startedAt).coerceAtLeast(0) / 60_000)
    } else 0
    val pagesRead = value?.let { ((it.endPage ?: it.startPage) - it.startPage).coerceAtLeast(0) } ?: 0
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("本次阅读已保存", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(18.dp))
        Text(value?.generatedSummary ?: "正在生成总结…", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(18.dp))
        Text("Session 阅读时长：$durationMinutes 分钟")
        Text("起始页：${value?.startPage ?: "—"}")
        Text("结束页：${value?.endPage ?: "—"}")
        Text("阅读页数：$pagesRead")
        Text("Note 数量：${notes.size}")
        Spacer(Modifier.weight(1f))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("完成") }
    }
}
