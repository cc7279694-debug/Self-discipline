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
import com.guanyi.mirra.feature.knowledge.ImageListScreen
import com.guanyi.mirra.feature.knowledge.ImageListViewModel
import com.guanyi.mirra.feature.knowledge.ImagePreviewScreen
import com.guanyi.mirra.feature.knowledge.ImagePreviewViewModel
import com.guanyi.mirra.feature.knowledge.LearningItemDetailScreen
import com.guanyi.mirra.feature.knowledge.LearningItemDetailViewModel
import com.guanyi.mirra.feature.knowledge.NoteEditorScreen
import com.guanyi.mirra.feature.knowledge.NoteEditorViewModel
import com.guanyi.mirra.feature.knowledge.NoteListScreen
import com.guanyi.mirra.feature.knowledge.NoteListViewModel
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
import com.guanyi.mirra.navigation.CreateFirstLearningItemRoute
import com.guanyi.mirra.navigation.CreateNoteRoute
import com.guanyi.mirra.navigation.LearningItemDetailRoute
import com.guanyi.mirra.navigation.ImageListRoute
import com.guanyi.mirra.navigation.ImagePreviewRoute
import com.guanyi.mirra.navigation.NoteDetailRoute
import com.guanyi.mirra.navigation.NoteListRoute
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
                            onCreateFirstLearningItem = { open(CreateFirstLearningItemRoute) },
                            onOpenKnowledge = { select(TopLevelDestination.Knowledge, persist = true) },
                        )
                        TopLevelDestination.Knowledge -> KnowledgeScreen(
                            viewModel = viewModel(factory = viewModelFactory {
                                KnowledgeViewModel(container.learningItemRepository)
                            }),
                            onCreateLearningItem = { open(CreateLearningItemRoute) },
                            onCreateNote = { open(CreateNoteRoute()) },
                            onOpenNotes = { open(NoteListRoute()) },
                            onOpenImages = { open(ImageListRoute) },
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
                entry<CreateFirstLearningItemRoute> {
                    CreateLearningItemScreen(
                        viewModel = viewModel(
                            key = "create-first-learning-item",
                            factory = viewModelFactory {
                                CreateLearningItemViewModel(container.learningItemRepository)
                            },
                        ),
                        onCreated = { select(TopLevelDestination.Start, persist = true) },
                        onBack = ::back,
                        showMainlineOption = true,
                        defaultSetAsMainline = true,
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
                        onOpenNotes = { open(NoteListRoute(it)) },
                        onBack = ::back,
                    )
                }
                entry<NoteListRoute> { route ->
                    NoteListScreen(
                        viewModel = viewModel(
                            key = "notes-${route.learningItemId.orEmpty()}",
                            factory = viewModelFactory {
                                NoteListViewModel(
                                    route.learningItemId,
                                    container.noteRepository,
                                    container.learningItemRepository,
                                )
                            },
                        ),
                        onCreate = { open(CreateNoteRoute(route.learningItemId)) },
                        onOpen = { open(NoteDetailRoute(it)) },
                        onBack = ::back,
                    )
                }
                entry<CreateNoteRoute> { route ->
                    NoteEditorScreen(
                        viewModel = viewModel(
                            key = "create-note-${route.initialLearningItemId.orEmpty()}",
                            factory = viewModelFactory {
                                NoteEditorViewModel(
                                    initialNoteId = null,
                                    initialLearningItemId = route.initialLearningItemId,
                                    notes = container.noteRepository,
                                    imageRepository = container.imageRepository,
                                    learningItems = container.learningItemRepository,
                                )
                            },
                        ),
                        onBack = ::back,
                        onDeleted = ::back,
                        onOpenImage = { noteId, imageId -> open(ImagePreviewRoute(noteId, imageId)) },
                    )
                }
                entry<NoteDetailRoute> { route ->
                    NoteEditorScreen(
                        viewModel = viewModel(
                            key = "note-${route.noteId}",
                            factory = viewModelFactory {
                                NoteEditorViewModel(
                                    initialNoteId = route.noteId,
                                    initialLearningItemId = null,
                                    notes = container.noteRepository,
                                    imageRepository = container.imageRepository,
                                    learningItems = container.learningItemRepository,
                                )
                            },
                        ),
                        onBack = ::back,
                        onDeleted = ::back,
                        onOpenImage = { noteId, imageId -> open(ImagePreviewRoute(noteId, imageId)) },
                    )
                }
                entry<ImageListRoute> {
                    ImageListScreen(
                        viewModel = viewModel(factory = viewModelFactory {
                            ImageListViewModel(container.imageRepository)
                        }),
                        onOpenImage = { noteId, imageId -> open(ImagePreviewRoute(noteId, imageId)) },
                        onBack = ::back,
                    )
                }
                entry<ImagePreviewRoute> { route ->
                    ImagePreviewScreen(
                        viewModel = viewModel(
                            key = "image-${route.noteId}-${route.initialImageId}",
                            factory = viewModelFactory {
                                ImagePreviewViewModel(route.noteId, route.initialImageId, container.imageRepository)
                            },
                        ),
                        onOpenNote = { open(NoteDetailRoute(it)) },
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
                        onAbandoned = { select(TopLevelDestination.Start, persist = true) },
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
                        onOpenNote = { open(NoteDetailRoute(it)) },
                        onBack = { select(TopLevelDestination.Start, persist = true) },
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
