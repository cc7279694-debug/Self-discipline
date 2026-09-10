package com.guanyi.mirra.feature.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.repository.LearningItemRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class KnowledgeViewModel(repository: LearningItemRepository) : ViewModel() {
    val items = repository.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
}

@Composable
fun KnowledgeScreen(
    viewModel: KnowledgeViewModel,
    onCreate: () -> Unit,
    onOpenItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val learningItems by viewModel.items.collectAsStateWithLifecycle()
    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("知识", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Button(onClick = onCreate) { Text("创建") }
        }
        Spacer(Modifier.height(20.dp))
        if (learningItems.isEmpty()) {
            Text("还没有学习内容。先创建一本正在读的书。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(learningItems, key = LearningItemEntity::id) { item ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenItem(item.id) }) {
                        Column(Modifier.padding(18.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "第 ${item.currentPage} / ${item.totalPages} 页${if (item.isMainline) " · 主线" else ""}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
