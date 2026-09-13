package com.guanyi.mirra.feature.knowledge

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.SubcomposeAsyncImage
import com.guanyi.mirra.data.repository.ImageRepository
import java.io.File
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ImagePreviewViewModel(
    val noteId: String,
    val initialImageId: String,
    private val repository: ImageRepository,
) : ViewModel() {
    val images = repository.observeForNote(noteId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun imageFile(localPath: String): File = repository.displayFile(localPath)
}

@Composable
fun ImagePreviewScreen(
    viewModel: ImagePreviewViewModel,
    onOpenNote: (String) -> Unit,
    onBack: () -> Unit,
) {
    val images by viewModel.images.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { images.size.coerceAtLeast(1) }
    var positioned by remember { mutableStateOf(false) }
    var zoomedPage by remember { mutableIntStateOf(-1) }

    LaunchedEffect(images, viewModel.initialImageId) {
        if (!positioned && images.isNotEmpty()) {
            pagerState.scrollToPage(images.indexOfFirst { it.id == viewModel.initialImageId }.coerceAtLeast(0))
            positioned = true
        }
    }
    LaunchedEffect(pagerState.currentPage) { zoomedPage = -1 }

    Column(
        modifier = Modifier.fillMaxSize().background(com.guanyi.mirra.ui.theme.MirraTheme.colors.mediaBackdrop).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("关闭") }
            Text(
                if (images.isEmpty()) "0 / 0" else "${pagerState.currentPage + 1} / ${images.size}",
                color = com.guanyi.mirra.ui.theme.MirraTheme.colors.onMediaBackdrop,
            )
            OutlinedButton(onClick = { onOpenNote(viewModel.noteId) }) { Text("查看笔记") }
        }
        if (images.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("图片已被删除", color = com.guanyi.mirra.ui.theme.MirraTheme.colors.onMediaBackdrop)
            }
        } else {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = zoomedPage != pagerState.currentPage,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                ZoomableImage(
                    file = viewModel.imageFile(images[page].localPath),
                    description = images[page].caption ?: "笔记图片",
                    resetKey = pagerState.currentPage,
                    onZoomChanged = { zoomed -> zoomedPage = if (zoomed) page else -1 },
                )
            }
            images.getOrNull(pagerState.currentPage)?.caption?.let {
                Text(it, color = com.guanyi.mirra.ui.theme.MirraTheme.colors.onMediaBackdrop, style = MaterialTheme.typography.bodyLarge)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(
                    onClick = { scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) } },
                    enabled = pagerState.currentPage > 0,
                ) { Text("上一张") }
                Button(
                    onClick = { scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(images.lastIndex)) } },
                    enabled = pagerState.currentPage < images.lastIndex,
                ) { Text("下一张") }
            }
        }
    }
}

@Composable
private fun ZoomableImage(file: File, description: String, resetKey: Int, onZoomChanged: (Boolean) -> Unit) {
    var scale by remember(file, resetKey) { mutableFloatStateOf(1f) }
    var offset by remember(file, resetKey) { mutableStateOf(Offset.Zero) }
    var size by remember(file, resetKey) { mutableStateOf(IntSize.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        val maxX = size.width * (nextScale - 1f) / 2f
        val maxY = size.height * (nextScale - 1f) / 2f
        scale = nextScale
        offset = if (nextScale == 1f) Offset.Zero else Offset(
            (offset.x + panChange.x).coerceIn(-maxX, maxX),
            (offset.y + panChange.y).coerceIn(-maxY, maxY),
        )
        onZoomChanged(nextScale > 1f)
    }
    SubcomposeAsyncImage(
        model = file,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            .transformable(transformState),
        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
        error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("图片缺失或已损坏", color = com.guanyi.mirra.ui.theme.MirraTheme.colors.onMediaBackdrop) } },
    )
}
