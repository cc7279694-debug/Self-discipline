package com.guanyi.mirra.feature.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.ui.components.MirraPrimaryButton
import com.guanyi.mirra.ui.components.MirraSecondaryButton
import com.guanyi.mirra.ui.components.MirraTextAction
import com.guanyi.mirra.ui.theme.MirraTheme
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
    onCreateLearningItem: () -> Unit,
    onCreateNote: () -> Unit,
    onCreateTopic: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenTopics: () -> Unit,
    onSearch: () -> Unit,
    onOpenItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val learningItems by viewModel.items.collectAsStateWithLifecycle()
    var showCreateChoices by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("知识", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            MirraTextAction(onClick = { showCreateChoices = true }) {
                Text("创建", color = MirraTheme.colors.accentStrong, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(MirraTheme.colors.surface, MirraTheme.shapes.medium)
                .clickable(onClick = onSearch)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MirraTheme.colors.textSecondary,
            )
            Text("搜索知识", color = MirraTheme.colors.textSecondary)
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            KnowledgeEntry("笔记", onOpenNotes, Modifier.weight(1f))
            KnowledgeEntry("图片", onOpenImages, Modifier.weight(1f))
            KnowledgeEntry("Topic", onOpenTopics, Modifier.weight(1f))
        }
        HorizontalDivider(color = MirraTheme.colors.divider)
        Spacer(Modifier.height(24.dp))
        Text("你的学习内容", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (learningItems.isEmpty()) {
            Text("还没有学习内容。先创建一本正在读的书。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(learningItems, key = LearningItemEntity::id) { item ->
                    Column(modifier = Modifier.fillMaxWidth().clickable { onOpenItem(item.id) }.padding(vertical = 14.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${item.status.displayName} · 第 ${item.currentPage} / ${item.totalPages} 页${if (item.isMainline) " · 主线" else ""}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        HorizontalDivider(Modifier.padding(top = 14.dp), color = MirraTheme.colors.divider)
                    }
                }
            }
        }
    }
    if (showCreateChoices) {
        AlertDialog(
            onDismissRequest = { showCreateChoices = false },
            title = { Text("创建") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MirraPrimaryButton(
                        onClick = {
                            showCreateChoices = false
                            onCreateLearningItem()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("创建学习内容") }
                    MirraSecondaryButton(
                        onClick = {
                            showCreateChoices = false
                            onCreateNote()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("创建笔记") }
                    MirraSecondaryButton(
                        onClick = {
                            showCreateChoices = false
                            onCreateTopic()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("创建 Topic") }
                }
            },
            confirmButton = {},
            dismissButton = {
                MirraTextAction(onClick = { showCreateChoices = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun KnowledgeEntry(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MirraTheme.colors.textPrimary, fontWeight = FontWeight.Medium)
    }
}

internal val LearningItemStatus.displayName: String
    get() = when (this) {
        LearningItemStatus.IN_PROGRESS -> "进行中"
        LearningItemStatus.PAUSED -> "已暂停"
        LearningItemStatus.COMPLETED -> "已完成"
    }
