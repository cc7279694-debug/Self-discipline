package com.guanyi.mirra.feature.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.local.model.NoteListItem
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.data.repository.NoteRepository
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi

data class NoteListUiState(
    val notes: List<NoteListItem> = emptyList(),
    val learningItems: List<LearningItemEntity> = emptyList(),
    val semanticType: NoteSemanticType? = null,
    val learningItemId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class NoteListViewModel(
    initialLearningItemId: String?,
    notes: NoteRepository,
    learningItems: LearningItemRepository,
) : ViewModel() {
    private val semanticType = MutableStateFlow<NoteSemanticType?>(null)
    private val learningItemId = MutableStateFlow(initialLearningItemId)
    private val filteredNotes = combine(semanticType, learningItemId, ::Pair)
        .flatMapLatest { (type, itemId) -> notes.observeAll(type, itemId) }

    val uiState = combine(
        filteredNotes,
        learningItems.observeAll(),
        semanticType,
        learningItemId,
        ::NoteListUiState,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteListUiState(learningItemId = initialLearningItemId))

    fun filterByType(type: NoteSemanticType?) {
        semanticType.value = type
    }

    fun filterByLearningItem(id: String?) {
        learningItemId.value = id
    }
}

@Composable
fun NoteListScreen(
    viewModel: NoteListViewModel,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var itemMenuExpanded by remember { mutableStateOf(false) }
    val selectedItemName = state.learningItems.firstOrNull { it.id == state.learningItemId }?.name

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("全部笔记", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.semanticType == null,
                onClick = { viewModel.filterByType(null) },
                label = { Text("全部") },
            )
            NoteSemanticType.entries.forEach { type ->
                FilterChip(
                    selected = state.semanticType == type,
                    onClick = { viewModel.filterByType(type) },
                    label = { Text(type.displayName) },
                )
            }
        }
        Box {
            OutlinedButton(onClick = { itemMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedItemName ?: "全部学习内容")
            }
            DropdownMenu(expanded = itemMenuExpanded, onDismissRequest = { itemMenuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("全部学习内容") },
                    onClick = {
                        itemMenuExpanded = false
                        viewModel.filterByLearningItem(null)
                    },
                )
                state.learningItems.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.name) },
                        onClick = {
                            itemMenuExpanded = false
                            viewModel.filterByLearningItem(item.id)
                        },
                    )
                }
            }
        }
        if (state.notes.isEmpty()) {
            Text(
                if (state.semanticType == null && state.learningItemId == null) "还没有笔记" else "当前筛选没有结果",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.notes, key = { it.note.id }) { row ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(row.note.id) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.note.content, maxLines = 2)
                            Text(
                                "${row.note.semanticType.displayName} · 来自《${row.learningItemName}》" +
                                    (row.note.pageNumber?.let { " · p$it" } ?: ""),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                DateFormat.getDateInstance(DateFormat.SHORT).format(Date(row.note.createdAt)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("新建笔记") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
}

internal val NoteSemanticType.displayName: String
    get() = when (this) {
        NoteSemanticType.QUOTE -> "摘录"
        NoteSemanticType.SUMMARY -> "总结"
        NoteSemanticType.UNDERSTANDING -> "我的理解"
        NoteSemanticType.QUESTION -> "问题"
    }
