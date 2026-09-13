package com.guanyi.mirra.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.ui.theme.MirraTheme

@Composable
fun MirraBottomNavigation(selected: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
            .shadow(MirraTheme.depth.floating, MirraTheme.shapes.pill),
        color = MirraTheme.colors.surfaceRaised,
        shape = MirraTheme.shapes.pill,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(8.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevelDestination.entries.forEach { destination ->
                val isSelected = destination == selected
                Surface(
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).selectable(
                        selected = isSelected,
                        onClick = { onSelect(destination) },
                        role = Role.Tab,
                    ),
                    color = if (isSelected) MirraTheme.colors.accentStrong else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (isSelected) MirraTheme.colors.onAccent else MirraTheme.colors.textSecondary,
                    shape = MirraTheme.shapes.pill,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                    Icon(
                        imageVector = when (destination) {
                            TopLevelDestination.Start -> Icons.Default.Home
                            TopLevelDestination.Knowledge -> Icons.AutoMirrored.Filled.List
                            TopLevelDestination.Profile -> Icons.Default.Person
                        },
                        contentDescription = destination.label,
                    )
                    Text(destination.label)
                    }
                }
            }
        }
    }
}
