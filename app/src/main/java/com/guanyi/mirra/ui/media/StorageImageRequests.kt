package com.guanyi.mirra.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import java.io.File
import java.util.UUID

private val standaloneImageGeneration = UUID.randomUUID().toString()
private val LocalImageStorageGeneration = staticCompositionLocalOf { standaloneImageGeneration }

/** Image bytes can change at the same path when the storage owner is replaced. */
@Composable
fun StorageImageCacheScope(storageOwner: Any, content: @Composable () -> Unit) {
    val generation = remember(storageOwner) { UUID.randomUUID().toString() }
    CompositionLocalProvider(LocalImageStorageGeneration provides generation, content = content)
}

internal fun storageImageCacheKey(generation: String, absolutePath: String): String =
    "mirra-image:$generation:$absolutePath"

@Composable
fun rememberStorageImageRequest(file: File): ImageRequest {
    val context = LocalContext.current
    val generation = LocalImageStorageGeneration.current
    val cacheKey = storageImageCacheKey(generation, file.absolutePath)
    return remember(context, file, cacheKey) {
        ImageRequest.Builder(context)
            .data(file)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .build()
    }
}
