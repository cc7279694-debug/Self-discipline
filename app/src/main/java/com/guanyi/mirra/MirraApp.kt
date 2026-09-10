package com.guanyi.mirra

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.guanyi.mirra.feature.knowledge.KnowledgeScreen
import com.guanyi.mirra.feature.profile.ProfileScreen
import com.guanyi.mirra.feature.start.StartScreen
import com.guanyi.mirra.navigation.TopLevelDestination

@Composable
fun MirraApp(
    restoredDestination: TopLevelDestination,
    onDestinationChanged: (TopLevelDestination) -> Unit,
) {
    val backStack = rememberNavBackStack(TopLevelDestination.Start)
    var selectedDestination by remember { mutableStateOf(TopLevelDestination.Start) }

    fun select(destination: TopLevelDestination, persist: Boolean) {
        if (selectedDestination != destination) {
            selectedDestination = destination
            backStack.clear()
            backStack.add(destination)
        }
        if (persist) onDestinationChanged(destination)
    }

    LaunchedEffect(restoredDestination) {
        select(restoredDestination, persist = false)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == selectedDestination,
                        onClick = { select(destination, persist = true) },
                        icon = { Text(destination.compactLabel) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.padding(contentPadding),
            onBack = { },
            entryProvider = entryProvider {
                entry<TopLevelDestination> { destination ->
                    when (destination) {
                        TopLevelDestination.Start -> StartScreen()
                        TopLevelDestination.Knowledge -> KnowledgeScreen()
                        TopLevelDestination.Profile -> ProfileScreen()
                    }
                }
            },
        )
    }
}
