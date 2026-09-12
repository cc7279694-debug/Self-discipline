package com.guanyi.mirra.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object CreateLearningItemRoute : NavKey
@Serializable data object CreateFirstLearningItemRoute : NavKey
@Serializable data class LearningItemDetailRoute(val itemId: String) : NavKey
@Serializable data class NoteListRoute(val learningItemId: String? = null) : NavKey
@Serializable data class CreateNoteRoute(val initialLearningItemId: String? = null) : NavKey
@Serializable data class NoteDetailRoute(val noteId: String) : NavKey
@Serializable data object ImageListRoute : NavKey
@Serializable data class ImagePreviewRoute(val noteId: String, val initialImageId: String) : NavKey
@Serializable data class PreparationRoute(val intentId: String) : NavKey
@Serializable data class SessionRoute(val sessionId: String) : NavKey
@Serializable data class SessionSummaryRoute(val sessionId: String) : NavKey
