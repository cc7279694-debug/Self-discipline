package com.guanyi.mirra.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.guanyi.mirra.ui.theme.MirraTheme

@Composable
fun MirraPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MirraTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp).shadow(
            elevation = MirraTheme.depth.raised,
            shape = MirraTheme.shapes.pill,
            ambientColor = colors.textTertiary.copy(alpha = .18f),
            spotColor = colors.textTertiary.copy(alpha = .24f),
        ),
        shape = MirraTheme.shapes.pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accentStrong,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.surfacePressed,
            disabledContentColor = colors.textTertiary,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        content = content,
    )
}

@Composable
fun MirraSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val colors = MirraTheme.colors
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = MirraTheme.shapes.pill,
        border = BorderStroke(1.dp, colors.divider),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary, disabledContentColor = colors.textTertiary),
        content = content,
    )
}

@Composable
fun MirraTextAction(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), content = content)
}

@Composable
fun MirraFocusCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = MirraTheme.colors
    Card(
        modifier = modifier.shadow(MirraTheme.depth.raised, MirraTheme.shapes.extraLarge, ambientColor = Color.White.copy(alpha = .5f), spotColor = colors.textTertiary.copy(alpha = .16f)),
        shape = MirraTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceRaised),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) { content() }
}

@Composable
fun MirraSurface(modifier: Modifier = Modifier, raised: Boolean = false, content: @Composable () -> Unit) {
    Surface(modifier = modifier, shape = MirraTheme.shapes.medium, color = if (raised) MirraTheme.colors.surfaceRaised else MirraTheme.colors.surface, content = content)
}

@Composable
fun MirraProgress(progress: () -> Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress().coerceIn(0f, 1f) },
        modifier = modifier.heightIn(min = 8.dp),
        color = MirraTheme.colors.accent,
        trackColor = MirraTheme.colors.accentSoft,
    )
}

@Composable
fun MirraToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Switch(
        checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MirraTheme.colors.onAccent,
            checkedTrackColor = MirraTheme.colors.accent,
            uncheckedThumbColor = MirraTheme.colors.textTertiary,
            uncheckedTrackColor = MirraTheme.colors.surfacePressed,
        ),
    )
}
