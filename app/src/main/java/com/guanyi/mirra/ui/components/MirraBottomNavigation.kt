package com.guanyi.mirra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.ui.theme.MirraTheme

@Composable
fun MirraBottomNavigation(selected: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit, modifier: Modifier = Modifier) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp, bottom = bottomInset + 10.dp)
            .shadow(MirraTheme.depth.raised, MirraTheme.shapes.pill)
            .background(
                Brush.verticalGradient(
                    listOf(MirraTheme.colors.surfaceHighlight, MirraTheme.colors.surfaceRaised),
                ),
                MirraTheme.shapes.pill,
            ),
        color = Color.Transparent,
        shape = MirraTheme.shapes.pill,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(6.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevelDestination.entries.forEach { destination ->
                val isSelected = destination == selected
                Row(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .clip(MirraTheme.shapes.pill)
                        .background(if (isSelected) MirraTheme.colors.accent else Color.Transparent)
                        .selectable(selected = isSelected, onClick = { onSelect(destination) }, role = Role.Tab),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val iconColor = if (isSelected) MirraTheme.colors.onAccent else MirraTheme.colors.textSecondary
                    when (destination) {
                        TopLevelDestination.Start -> Icon(Icons.Default.Home, contentDescription = destination.label, modifier = Modifier.size(20.dp), tint = iconColor)
                        TopLevelDestination.Knowledge -> MirraBookGlyph(iconColor)
                        TopLevelDestination.Profile -> MirraPersonGlyph(iconColor)
                    }
                    Text(
                        destination.label,
                        color = if (isSelected) MirraTheme.colors.onAccent else MirraTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
fun MirraPersonGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        drawCircle(color, radius = size.width * .15f, center = Offset(size.width * .5f, size.height * .29f), style = Stroke(stroke))
        val shoulders = Path().apply {
            moveTo(size.width * .17f, size.height * .85f)
            quadraticTo(size.width * .19f, size.height * .57f, size.width * .5f, size.height * .57f)
            quadraticTo(size.width * .81f, size.height * .57f, size.width * .83f, size.height * .85f)
        }
        drawPath(shoulders, color, style = Stroke(stroke))
    }
}

@Composable
private fun MirraBookGlyph(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val leftPage = Path().apply {
            moveTo(size.width * .5f, size.height * .22f)
            quadraticTo(size.width * .36f, size.height * .11f, size.width * .1f, size.height * .17f)
            lineTo(size.width * .1f, size.height * .78f)
            quadraticTo(size.width * .35f, size.height * .72f, size.width * .5f, size.height * .84f)
        }
        val rightPage = Path().apply {
            moveTo(size.width * .5f, size.height * .22f)
            quadraticTo(size.width * .64f, size.height * .11f, size.width * .9f, size.height * .17f)
            lineTo(size.width * .9f, size.height * .78f)
            quadraticTo(size.width * .65f, size.height * .72f, size.width * .5f, size.height * .84f)
        }
        drawPath(leftPage, color, style = Stroke(stroke))
        drawPath(rightPage, color, style = Stroke(stroke))
    }
}
