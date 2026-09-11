package com.guanyi.mirra.feature.knowledge

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.guanyi.mirra.data.local.entity.ImageAssetEntity
import com.guanyi.mirra.data.storage.CameraTarget
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun NoteImageSection(
    images: List<ImageAssetEntity>,
    imageFile: (String) -> File,
    isBusy: Boolean,
    message: String,
    onGalleryResult: (List<Uri>) -> Unit,
    onCreateCameraTarget: suspend () -> CameraTarget?,
    onCameraResult: (CameraTarget, Boolean) -> Unit,
    onCameraUnavailable: (CameraTarget) -> Unit,
    onCaptionChanged: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (ImageAssetEntity) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteImageId by remember { mutableStateOf<String?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20),
        onResult = onGalleryResult,
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = cameraPath
        val uri = cameraUri
        cameraPath = null
        cameraUri = null
        if (path != null && uri != null) onCameraResult(CameraTarget(path, Uri.parse(uri)), success)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("图片", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                enabled = !isBusy,
            ) { Text("从相册添加") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val target = onCreateCameraTarget() ?: return@launch
                        cameraPath = target.tempPath
                        cameraUri = target.uri.toString()
                        try {
                            cameraLauncher.launch(target.uri)
                        } catch (_: ActivityNotFoundException) {
                            cameraPath = null
                            cameraUri = null
                            onCameraUnavailable(target)
                        }
                    }
                },
                enabled = !isBusy,
            ) { Text("拍照") }
        }
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary)
        if (images.isEmpty()) {
            Text("还没有图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(images, key = ImageAssetEntity::id) { image ->
                    Card(modifier = Modifier.fillParentMaxWidth(0.78f)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SubcomposeAsyncImage(
                                model = imageFile(image.localPath),
                                contentDescription = image.caption ?: "笔记图片",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(180.dp).clickable { onOpen(image) },
                                loading = {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                },
                                error = {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text("图片缺失或已损坏", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                            )
                            CaptionField(image, onCaptionChanged)
                            TextButton(
                                onClick = { deleteImageId = image.id },
                                enabled = !isBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("删除图片") }
                        }
                    }
                }
            }
        }
    }

    if (deleteImageId != null) {
        AlertDialog(
            onDismissRequest = { deleteImageId = null },
            title = { Text("删除这张图片？") },
            text = { Text("图片文件和 Caption 都会删除，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteImageId?.let(onDelete)
                    deleteImageId = null
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { deleteImageId = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun CaptionField(
    image: ImageAssetEntity,
    onCaptionChanged: (String, String) -> Unit,
) {
    var value by rememberSaveable(image.id) { mutableStateOf(image.caption.orEmpty()) }
    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            onCaptionChanged(image.id, it)
        },
        label = { Text("Caption（可选）") },
        modifier = Modifier.fillMaxWidth(),
    )
}
