package com.guanyi.mirra.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import com.guanyi.mirra.ui.theme.MirraPalettes
import com.guanyi.mirra.ui.theme.MirraTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MirraComponentsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun primaryButtonKeepsLargeTouchTargetAndClickSemantics() {
        var clicked = false
        composeRule.setContent {
            MirraTheme {
                MirraPrimaryButton(onClick = { clicked = true }, modifier = Modifier.testTag("primary")) {
                    androidx.compose.material3.Text("开始学习")
                }
            }
        }
        composeRule.onNodeWithTag("primary").assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithText("开始学习").performClick()
        assertTrue(clicked)
    }

    @Test
    fun progressTrackEndDoesNotDrawASecondAccentMarker() {
        composeRule.setContent {
            MirraTheme {
                MirraProgress(
                    progress = { 0.46f },
                    modifier = Modifier.width(240.dp).testTag("progress"),
                )
            }
        }

        val pixels = composeRule.onNodeWithTag("progress").captureToImage().toPixelMap()
        val accent = MirraPalettes.blue.accent.toArgb()
        val accentPixelsAtTrackEnd = ((pixels.width * 3) / 4 until pixels.width).sumOf { x ->
            (0 until pixels.height).count { y -> pixels[x, y].toArgb() == accent }
        }

        assertTrue(
            "The unfilled end of the track must not contain an accent stop marker",
            accentPixelsAtTrackEnd == 0,
        )
    }
}
