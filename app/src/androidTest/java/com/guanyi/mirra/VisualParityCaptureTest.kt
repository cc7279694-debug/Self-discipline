package com.guanyi.mirra

import android.graphics.Bitmap
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.ui.theme.MirraTheme
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Captures the four real Compose surfaces used for visual acceptance on an emulator. */
class VisualParityCaptureTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var container: TestAppContainer

    @Before fun setUp() {
        container = TestAppContainer(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() {
        composeRule.activityRule.scenario.close()
        container.close()
    }

    @Test fun captureStartAndNeighboringSurfaces() {
        composeRule.setContent { MirraTheme { MirraApp(container, TopLevelDestination.Start, onDestinationChanged = {}) } }
        waitFor("开始第一次学习")
        capture("start-empty-library")

        val now = System.currentTimeMillis()
        val item = runBlocking {
            container.learningItemRepository.create(
                name = "怪诞行为学",
                totalPages = 320,
                currentPage = 146,
                setAsMainline = true,
            ).also {
                val endedAt = now - Duration.ofDays(1).toMillis()
                val startedAt = endedAt - Duration.ofMinutes(42).toMillis()
                container.database.intentDao().insert(
                    StudyIntentEntity(
                        id = "visual-intent",
                        learningItemId = it.id,
                        createdAt = startedAt - 60_000L,
                        transitionedAt = startedAt - 30_000L,
                        convertedAt = startedAt,
                        endedAt = startedAt,
                        outcome = IntentOutcome.CONVERTED,
                        activeSlot = null,
                    ),
                )
                container.database.sessionDao().insert(
                    StudySessionEntity(
                        id = "visual-session",
                        learningItemId = it.id,
                        intentId = "visual-intent",
                        startedAt = startedAt,
                        stableStartedAt = null,
                        endedAt = endedAt,
                        startPage = 128,
                        currentPage = 146,
                        endPage = 146,
                        endType = SessionEndType.NORMAL,
                        generatedSummary = "本次阅读 128–146 页",
                        activeSlot = null,
                    ),
                )
            }
        }
        waitFor("怪诞行为学")
        waitFor("最近阅读")
        composeRule.onNodeWithText("146 / 320 页").assertExists()
        composeRule.onNodeWithText("46%").assertExists()
        composeRule.onNodeWithText("上次停在第 146 页").assertExists()
        capture("start-mainline")

        composeRule.onNode(hasText("知识") and hasClickAction()).performClick()
        waitFor("你的学习内容")
        composeRule.onNodeWithText(item.name).assertExists()
        capture("knowledge")

        composeRule.onNode(hasText("我的") and hasClickAction()).performClick()
        waitFor("最近 7 天")
        capture("mine")
    }

    private fun waitFor(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        if (!InstrumentationRegistry.getArguments().getBoolean("captureVisualParity", false)) return
        composeRule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val resolver = composeRule.activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "mirra-$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MirraVisualParity")
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri).use {
            check(it != null && bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
