package com.guanyi.mirra.ui.theme

import androidx.compose.ui.graphics.Color
import com.guanyi.mirra.data.preferences.MirraThemeId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirraPaletteTest {
    @Test
    fun unknownThemeFallsBackToBlue() {
        assertEquals(MirraThemeId.BLUE, MirraThemeId.fromStorageValue(null))
        assertEquals(MirraThemeId.BLUE, MirraThemeId.fromStorageValue("future-theme"))
        MirraThemeId.entries.forEach { id ->
            assertEquals(id, MirraThemeId.fromStorageValue(id.storageValue))
        }
    }

    @Test
    fun palettesKeepReadableTextAndActions() {
        listOf(MirraPalettes.blue, MirraPalettes.mono, MirraPalettes.night).forEach { colors ->
            listOf(colors.textPrimary, colors.textSecondary, colors.textTertiary).forEach { text ->
                assertTrue(contrast(text, colors.background) >= 4.5)
                assertTrue(contrast(text, colors.surface) >= 4.5)
            }
            assertTrue(contrast(colors.onAccent, colors.accentStrong) >= 4.5)
            assertTrue(contrast(colors.onPositive, colors.positive) >= 4.5)
            assertTrue(contrast(colors.onWarning, colors.warning) >= 4.5)
            assertTrue(contrast(colors.onDanger, colors.danger) >= 4.5)
        }
    }

    @Test
    fun blueUsesDedicatedActionAndProgressColors() {
        assertEquals(Color(0xFF6598E8), MirraPalettes.blue.accent)
        assertEquals(Color(0xFF386FBE), MirraPalettes.blue.accentStrong)
        assertEquals(Color.White, MirraPalettes.blue.onAccent)
    }

    private fun contrast(first: Color, second: Color): Double {
        val lighter = max(luminance(first), luminance(second))
        val darker = min(luminance(first), luminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val normalized = value.toDouble()
            return if (normalized <= 0.04045) normalized / 12.92
            else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
