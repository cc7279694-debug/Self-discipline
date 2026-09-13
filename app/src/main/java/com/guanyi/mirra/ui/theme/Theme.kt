package com.guanyi.mirra.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.Shapes
import com.guanyi.mirra.data.preferences.MirraThemeId

private val LocalMirraColors = staticCompositionLocalOf { MirraPalettes.blue }
private val LocalMirraShapes = staticCompositionLocalOf { MirraShapes() }
private val LocalMirraDepth = staticCompositionLocalOf { MirraDepth() }

object MirraTheme {
    val colors: MirraColors
        @Composable @ReadOnlyComposable get() = LocalMirraColors.current
    val shapes: MirraShapes
        @Composable @ReadOnlyComposable get() = LocalMirraShapes.current
    val depth: MirraDepth
        @Composable @ReadOnlyComposable get() = LocalMirraDepth.current
}

@Composable
fun MirraTheme(themeId: MirraThemeId = MirraThemeId.BLUE, content: @Composable () -> Unit) {
    val colors = when (themeId) {
        MirraThemeId.BLUE -> MirraPalettes.blue
        MirraThemeId.MONO -> MirraPalettes.mono
        MirraThemeId.NIGHT -> MirraPalettes.night
    }
    val shapes = MirraShapes()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !colors.isDark
                isAppearanceLightNavigationBars = !colors.isDark
            }
        }
    }
    CompositionLocalProvider(
        LocalMirraColors provides colors,
        LocalMirraShapes provides shapes,
        LocalMirraDepth provides MirraDepth(),
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialColorScheme(),
            shapes = Shapes(small = shapes.small, medium = shapes.medium, large = shapes.large),
            content = content,
        )
    }
}

private fun MirraColors.toMaterialColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accentStrong, onPrimary = onAccent, primaryContainer = accentSoft, onPrimaryContainer = textPrimary,
        secondary = accent, onSecondary = if (isDark) textPrimary else onAccent,
        secondaryContainer = accentSoft, onSecondaryContainer = textPrimary,
        tertiary = positive, onTertiary = onPositive, tertiaryContainer = surfacePressed, onTertiaryContainer = textPrimary,
        background = background, onBackground = textPrimary, surface = surface, onSurface = textPrimary,
        surfaceVariant = surfacePressed, onSurfaceVariant = textSecondary,
        surfaceTint = accent, inverseSurface = textPrimary, inverseOnSurface = background, inversePrimary = accent,
        error = danger, onError = onDanger, errorContainer = if (isDark) danger.copy(alpha = .22f) else danger.copy(alpha = .12f),
        onErrorContainer = if (isDark) textPrimary else danger, outline = textTertiary, outlineVariant = divider, scrim = scrim,
        surfaceBright = surfaceRaised, surfaceDim = background, surfaceContainer = surface,
        surfaceContainerHigh = surfaceRaised, surfaceContainerHighest = surfaceRaised,
        surfaceContainerLow = surface, surfaceContainerLowest = background,
        primaryFixed = accentStrong, primaryFixedDim = accent, onPrimaryFixed = onAccent, onPrimaryFixedVariant = textPrimary,
        secondaryFixed = accent, secondaryFixedDim = accentSoft, onSecondaryFixed = textPrimary,
        onSecondaryFixedVariant = textSecondary, tertiaryFixed = positive, tertiaryFixedDim = positive.copy(alpha = .8f),
        onTertiaryFixed = onPositive, onTertiaryFixedVariant = textPrimary,
    )
}
