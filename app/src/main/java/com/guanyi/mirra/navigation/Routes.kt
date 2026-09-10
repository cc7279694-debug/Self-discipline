package com.guanyi.mirra.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object CreateLearningItemRoute : NavKey
@Serializable data class LearningItemDetailRoute(val itemId: String) : NavKey
@Serializable data class PreparationRoute(val intentId: String) : NavKey
@Serializable data class SessionRoute(val sessionId: String) : NavKey
@Serializable data class SessionSummaryRoute(val sessionId: String) : NavKey
