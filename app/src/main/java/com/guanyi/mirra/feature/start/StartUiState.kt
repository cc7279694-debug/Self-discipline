package com.guanyi.mirra.feature.start

import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.local.model.RecentReadingSnapshot
import com.guanyi.mirra.domain.FirstActionResolver

data class StartUiState(
    val content: StartContentState? = null,
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface StartContentState {
    data class ActiveSession(
        val sessionId: String,
        val learningItemId: String,
        val learningItemName: String,
        val currentPage: Int,
        val elapsedMinutes: Long,
    ) : StartContentState

    data class ActiveIntent(
        val intentId: String,
        val learningItemId: String,
        val learningItemName: String,
        val firstAction: String,
    ) : StartContentState

    data class Mainline(
        val item: StartLearningItem,
        val recentReading: RecentReadingSnapshot?,
    ) : StartContentState

    data class ChooseInProgress(
        val items: List<StartLearningItem>,
        val selectedItemId: String?,
        val setSelectedAsMainline: Boolean,
        val recentReading: RecentReadingSnapshot?,
    ) : StartContentState

    data class NoInProgress(val totalItemCount: Int) : StartContentState

    data object EmptyLibrary : StartContentState
}

data class StartLearningItem(
    val id: String,
    val name: String,
    val currentPage: Int,
    val totalPages: Int,
    val progressPercent: Int,
    val firstAction: String,
)

sealed interface StartResolution {
    data class Content(val value: StartContentState) : StartResolution
    data class DataError(
        val message: String,
        val sessionId: String? = null,
        val intentId: String? = null,
    ) : StartResolution
}

internal fun resolveStartContent(
    items: List<LearningItemEntity>,
    activeIntent: StudyIntentEntity?,
    activeSession: StudySessionEntity?,
    selectedItemId: String?,
    setSelectedAsMainline: Boolean,
    recentReading: RecentReadingSnapshot?,
    nowMillis: Long,
): StartResolution {
    val byId = items.associateBy(LearningItemEntity::id)

    if (activeSession != null) {
        val item = byId[activeSession.learningItemId] ?: return StartResolution.DataError(
            message = "当前阅读对应的学习内容不存在",
            sessionId = activeSession.id,
        )
        return StartResolution.Content(
            StartContentState.ActiveSession(
                sessionId = activeSession.id,
                learningItemId = item.id,
                learningItemName = item.name,
                currentPage = activeSession.currentPage,
                elapsedMinutes = ((nowMillis - activeSession.startedAt).coerceAtLeast(0L) / 60_000L),
            ),
        )
    }

    if (activeIntent != null) {
        val item = byId[activeIntent.learningItemId] ?: return StartResolution.DataError(
            message = "当前启动对应的学习内容不存在",
            intentId = activeIntent.id,
        )
        return StartResolution.Content(
            StartContentState.ActiveIntent(
                intentId = activeIntent.id,
                learningItemId = item.id,
                learningItemName = item.name,
                firstAction = FirstActionResolver.resolve(item),
            ),
        )
    }

    val mainline = items.firstOrNull {
        it.mainlineSlot != null && it.status == LearningItemStatus.IN_PROGRESS
    }
    if (mainline != null) {
        return StartResolution.Content(
            StartContentState.Mainline(
                item = mainline.toStartLearningItem(),
                recentReading = recentReading?.takeIf { it.learningItemId == mainline.id },
            ),
        )
    }

    val inProgress = items
        .asSequence()
        .filter { it.status == LearningItemStatus.IN_PROGRESS }
        .sortedWith(compareByDescending<LearningItemEntity> { it.updatedAt }.thenBy { it.id })
        .map(LearningItemEntity::toStartLearningItem)
        .toList()
    if (inProgress.isNotEmpty()) {
        val validSelection = selectedItemId?.takeIf { selected -> inProgress.any { it.id == selected } }
        return StartResolution.Content(
            StartContentState.ChooseInProgress(
                items = inProgress,
                selectedItemId = validSelection,
                setSelectedAsMainline = validSelection != null && setSelectedAsMainline,
                recentReading = recentReading?.takeIf { it.learningItemId == validSelection },
            ),
        )
    }

    return StartResolution.Content(
        if (items.isEmpty()) StartContentState.EmptyLibrary else StartContentState.NoInProgress(items.size),
    )
}

private fun LearningItemEntity.toStartLearningItem() = StartLearningItem(
    id = id,
    name = name,
    currentPage = currentPage,
    totalPages = totalPages,
    progressPercent = ((currentPage.toLong() * 100L) / totalPages.coerceAtLeast(1)).toInt().coerceIn(0, 100),
    firstAction = FirstActionResolver.resolve(this),
)
