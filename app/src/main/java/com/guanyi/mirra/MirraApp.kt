package com.guanyi.mirra

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.guanyi.mirra.di.AppContainer
import com.guanyi.mirra.feature.knowledge.CreateLearningItemScreen
import com.guanyi.mirra.feature.knowledge.CreateLearningItemViewModel
import com.guanyi.mirra.feature.knowledge.KnowledgeScreen
import com.guanyi.mirra.feature.knowledge.KnowledgeViewModel
import com.guanyi.mirra.feature.knowledge.LearningItemDetailScreen
import com.guanyi.mirra.feature.knowledge.LearningItemDetailViewModel
import com.guanyi.mirra.feature.profile.ProfileScreen
import com.guanyi.mirra.feature.session.PreparationScreen
import com.guanyi.mirra.feature.session.PreparationViewModel
import com.guanyi.mirra.feature.session.SessionScreen
import com.guanyi.mirra.feature.session.SessionSummaryScreen
import com.guanyi.mirra.feature.session.SessionSummaryViewModel
import com.guanyi.mirra.feature.session.SessionViewModel
import com.guanyi.mirra.feature.start.StartScreen
import com.guanyi.mirra.feature.start.StartViewModel
import com.guanyi.mirra.navigation.CreateLearningItemRoute
import com.guanyi.mirra.navigation.LearningItemDetailRoute
import com.guanyi.mirra.navigation.PreparationRoute
import com.guanyi.mirra.navigation.SessionRoute
import com.guanyi.mirra.navigation.SessionSummaryRoute
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.ui.viewModelFactory

@Composable
fun MirraApp(
    container: AppContainer,
    restoredDestination: TopLevelDestination,
    onDestinationChanged: (TopLevelDestination) -> Unit,
) {
    val backStack = rememberNavBackStack(TopLevelDestination.Start)
    var selectedDestination by remember { mutableStateOf(TopLevelDestination.Start) }

    fun open(route: NavKey) {
        backStack.add(route)
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun select(destination: TopLevelDestination, persist: Boolean) {
        selectedDestination = destination
        backStack.clear()
        backStack.add(destination)
        if (persist) onDestinationChanged(destination)
    }

    LaunchedEffect(restoredDestination) {
        select(restoredDestination, persist = false)
    }

    val isTopLevel = backStack.lastOrNull() is TopLevelDestination
    Scaffold(
        bottomBar = {
            if (isTopLevel) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = destination == selectedDestination,
                            onClick = { select(destination, persist = true) },
                            icon = {
                                Icon(
                                    imageVector = when (destination) {
                                        TopLevelDestination.Start -> Icons.Default.Home
                                        TopLevelDestination.Knowledge -> Icons.AutoMirrored.Filled.List
                                        TopLevelDestination.Profile -> Icons.Default.Person
                                    },
                                    contentDescription = null,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.padding(contentPadding),
            onBack = ::back,
            entryProvider = entryProvider {
                entry<TopLevelDestination> { destination ->
                    when (destination) {
                        TopLevelDestination.Start -> StartScreen(
                            viewModel = viewModel(factory = viewModelFactory {
                                StartViewModel(container.learningItemRepository, container.studyWorkflowRepository)
                            }),
                            onOpenIntent = { open(PreparationRoute(it)) },
                            onOpenSession = { open(SessionRoute(it)) },
                            onCreateLearningItem = { open(CreateLearningItemRoute) },
                        )
                        TopLevelDestination.Knowledge -> KnowledgeScreen(
                            viewModel = viewModel(factory = viewModelFactory {
                                KnowledgeViewModel(container.learningItemRepository)
                            }),
                            onCreate = { open(CreateLearningItemRoute) },
                            onOpenItem = { open(LearningItemDetailRoute(it)) },
                        )
                        TopLevelDestination.Profile -> ProfileScreen()
                    }
                }
                entry<CreateLearningItemRoute> {
                    CreateLearningItemScreen(
                        viewModel = viewModel(factory = viewModelFactory {
                            CreateLearningItemViewModel(container.learningItemRepository)
                        }),
                        onCreated = { itemId ->
                            back()
                            open(LearningItemDetailRoute(itemId))
                        },
                        onBack = ::back,
                    )
                }
                entry<LearningItemDetailRoute> { route ->
                    LearningItemDetailScreen(
                        viewModel = viewModel(
                            key = route.itemId,
                            factory = viewModelFactory {
                                LearningItemDetailViewModel(
                                    route.itemId,
                                    container.learningItemRepository,
                                    container.studyWorkflowRepository,
                                )
                            },
                        ),
                        onStart = { open(PreparationRoute(it)) },
                        onBack = ::back,
                    )
                }
                entry<PreparationRoute> { route ->
                    PreparationScreen(
                        viewModel = viewModel(
                            key = route.intentId,
                            factory = viewModelFactory {
                                PreparationViewModel(
                                    route.intentId,
                                    container.studyWorkflowRepository,
                                    container.learningItemRepository,
                                    container.sessionManager,
                                )
                            },
                        ),
                        onStarted = { sessionId ->
                            backStack.clear()
                            backStack.add(SessionRoute(sessionId))
                        },
                        onBack = ::back,
                    )
                }
                entry<SessionRoute> { route ->
                    SessionScreen(
                        viewModel = viewModel(
                            key = route.sessionId,
                            factory = viewModelFactory {
                                SessionViewModel(
                                    route.sessionId,
                                    container.studyWorkflowRepository,
                                    container.noteRepository,
                                    container.sessionManager,
                                )
                            },
                        ),
                        onFinished = { sessionId ->
                            backStack.clear()
                            backStack.add(SessionSummaryRoute(sessionId))
                        },
                    )
                }
                entry<SessionSummaryRoute> { route ->
                    SessionSummaryScreen(
                        viewModel = viewModel(
                            key = route.sessionId,
                            factory = viewModelFactory {
                                SessionSummaryViewModel(
                                    route.sessionId,
                                    container.studyWorkflowRepository,
                                    container.noteRepository,
                                )
                            },
                        ),
                        onDone = { select(TopLevelDestination.Start, persist = true) },
                    )
                }
            },
        )
    }
}
