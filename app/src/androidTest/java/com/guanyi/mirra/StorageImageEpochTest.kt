package com.guanyi.mirra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.entity.ImageAssetEntity
import com.guanyi.mirra.data.repository.ImageRepository
import com.guanyi.mirra.di.AppContainer
import com.guanyi.mirra.navigation.TopLevelDestination
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Restore changes an image's bytes, not necessarily its path, ID, size or timestamp. */
@RunWith(AndroidJUnit4::class)
class StorageImageEpochTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun replacingStorageOwnerCannotReuseOldBitmapForTheSamePathAndMtime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "image-epoch-${UUID.randomUUID()}")
        check(root.mkdir())
        val image = File(root, "same-image.jpg")
        val source = TestAppContainer(context)
        try {
            writeJpeg(image, AndroidColor.RED)
            runBlocking {
                val book = source.learningItemRepository.create("图片代际测试", 100, firstAction = "把书放到桌上，翻到上次阅读的位置")
                val note = source.noteRepository.createStandalone(book.id, "代际图片笔记")
                source.database.imageAssetDao().insert(ImageAssetEntity(
                    "epoch-image", note.id, "images/epoch-image.jpg", CAPTION, 96, 96,
                    image.length(), 1,
                ))
            }
            val oldOwner = owner(source, image)
            val newOwner = owner(source, image)
            var selectedOwner by mutableStateOf(oldOwner)
            composeRule.setContent {
                key(selectedOwner) {
                    MirraApp(selectedOwner, TopLevelDestination.Knowledge, onDestinationChanged = {})
                }
            }
            openImageList()
            val oldPixel = waitForDecodedPixel()
            assertTrue("The first generation must really decode the red JPEG", oldPixel.red > 0.8f && oldPixel.blue < 0.2f)

            // Keep the same path and mtime, including when filesystem clock precision collides.
            writeJpeg(image, AndroidColor.BLUE)
            composeRule.runOnIdle { selectedOwner = newOwner }
            openImageList()
            val restoredPixel = waitForDecodedPixel()
            assertTrue(
                "A new storage owner must display restored blue bytes, not the old cached red bitmap: $restoredPixel",
                restoredPixel.blue > 0.8f && restoredPixel.red < 0.2f,
            )
        } finally {
            composeRule.activityRule.scenario.close()
            source.close()
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(root.deleteRecursively())
        }
    }

    private fun owner(source: TestAppContainer, file: File): AppContainer = object : AppContainer by source {
        override val imageRepository: ImageRepository = object : ImageRepository by source.imageRepository {
            // Only storage resolution is redirected; real Room projections and UI are unchanged.
            override fun displayFile(localPath: String): File = file
        }
    }

    private fun openImageList() {
        composeRule.waitUntil(10_000) {
            runCatching { composeRule.onNodeWithText("图片").fetchSemanticsNode() }.isSuccess
        }
        composeRule.onNodeWithText("图片").performClick()
    }

    private fun waitForDecodedPixel(): Color {
        // Loading/error surfaces are not red or blue, so a decode must have actually finished.
        composeRule.waitUntil(10_000) {
            runCatching { pixel() }.getOrNull()?.let {
                (it.red > 0.8f && it.blue < 0.2f) || (it.blue > 0.8f && it.red < 0.2f)
            } == true
        }
        return pixel()
    }

    private fun pixel(): Color {
        val pixels = composeRule.onNodeWithContentDescription(CAPTION).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    private fun writeJpeg(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally { bitmap.recycle() }
        check(file.setLastModified(1_600_000_000_000L))
    }

    private companion object { const val CAPTION = "代际缓存图片" }
}
