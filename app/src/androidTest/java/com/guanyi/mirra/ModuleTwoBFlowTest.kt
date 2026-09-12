package com.guanyi.mirra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.navigation.TopLevelDestination
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ModuleTwoBFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var container: TestAppContainer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = TestAppContainer(context)
    }

    @After
    fun tearDown() {
        composeRule.activityRule.scenario.close()
        container.close()
    }

    @Test
    fun globalImageListOpensPreviewAndSwitchesWithinNote() {
        val noteId = runBlocking {
            val item = container.learningItemRepository.create("图片所属书", 200)
            val note = container.noteRepository.createStandalone(item.id, "双图笔记", pageNumber = 18)
            addImage(note.id, "第一张")
            delay(5)
            addImage(note.id, "第二张")
            note.id
        }
        launchKnowledge()

        composeRule.onNodeWithText("全部图片").performClick()
        assertEquals(2, composeRule.onAllNodes(hasText("图片所属书")).fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodes(hasText("第 18 页")).fetchSemanticsNodes().size)
        composeRule.onNodeWithText("第一张").performClick()
        composeRule.onNodeWithText("1 / 2").assertExists()
        composeRule.onNodeWithText("下一张").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("2 / 2")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("查看笔记").performClick()
        composeRule.onNodeWithText("双图笔记").assertExists()
        composeRule.onNodeWithText("从相册添加").assertExists()
        composeRule.onNodeWithText("拍照").assertExists()
        check(noteId.isNotEmpty())
    }

    @Test
    fun captionCanBeEditedAndNoteDeleteRemovesPhysicalImage() {
        val data = runBlocking {
            val item = container.learningItemRepository.create("删除图片书", 100)
            val note = container.noteRepository.createStandalone(item.id, "有图笔记")
            val image = addImage(note.id, "旧 Caption")
            note.id to image.localPath
        }
        launchKnowledge()
        composeRule.onNodeWithText("全部笔记").performClick()
        composeRule.onNodeWithText("有图笔记").performClick()

        composeRule.onNode(hasText("Caption（可选）") and hasSetTextAction()).performTextReplacement("新 Caption")
        composeRule.waitUntil(5_000) {
            runBlocking { container.imageRepository.observeForNote(data.first).first().single().caption } == "新 Caption"
        }
        composeRule.onNodeWithText("删除笔记").performScrollTo().performClick()
        composeRule.onNodeWithText("删除后无法恢复，并将同时删除 1 张图片。").assertExists()
        composeRule.onNodeWithText("确认删除").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { container.noteRepository.observe(data.first).first() } == null
        }
        assertFalse(container.imageRepository.displayFile(data.second).exists())
    }

    private suspend fun addImage(noteId: String, caption: String) =
        container.imageRepository.createCameraTarget().let { target ->
            writeJpeg(context.filesDir.resolve(target.tempPath))
            container.imageRepository.completeCameraImport(noteId, target).also {
                container.imageRepository.updateCaption(it.id, caption)
            }
        }

    private fun writeJpeg(file: File) {
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.RGB_565).apply { eraseColor(Color.WHITE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
    }

    private fun launchKnowledge() {
        composeRule.setContent { MirraApp(container, TopLevelDestination.Knowledge, onDestinationChanged = {}) }
    }
}
