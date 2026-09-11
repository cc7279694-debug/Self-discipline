package com.guanyi.mirra.feature.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.SubcomposeAsyncImage
import com.guanyi.mirra.data.local.model.ImageListItem
import com.guanyi.mirra.data.repository.ImageRepository
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class ImageListViewModel(private val repository: ImageRepository) : ViewModel() {
    val images = repository.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun imageFile(localPath: String): File = repository.displayFile(localPath)
}

@Composable
fun ImageListScreen(
    viewModel: ImageListViewModel,
    onOpenImage: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    val images by viewModel.images.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("全部图片", style = MaterialTheme.typography.headlineMedium)
        if (images.isEmpty()) {
            Text("还没有图片笔记", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(images, key = { it.image.id }) { item ->
                    ImageGridCard(item, viewModel::imageFile) {
                        onOpenImage(item.image.noteId, item.image.id)
                    }
                }
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
}

@Composable
private fun ImageGridCard(item: ImageListItem, imageFile: (String) -> File, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SubcomposeAsyncImage(
                model = imageFile(item.image.localPath),
                contentDescription = item.image.caption ?: "笔记图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(150.dp),
                loading = { Text("正在读取图片…", modifier = Modifier.padding(12.dp)) },
                error = { Text("图片缺失或已损坏", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) },
            )
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                item.image.caption?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(item.learningItemName, style = MaterialTheme.typography.labelLarge)
                item.notePageNumber?.let { Text("第 $it 页", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.image.createdAt)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
