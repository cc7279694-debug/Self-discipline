package com.guanyi.mirra.feature.start

import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartStateResolverTest {
    @Test
    fun activeSessionWinsOverIntentAndMainline() {
        val item = item(id = "book", mainlineSlot = 1)
        val result = resolveStartContent(
            items = listOf(item),
            activeIntent = intent("book"),
            activeSession = session("book", currentPage = 40, startedAt = 1_000L),
            selectedItemId = null,
            setSelectedAsMainline = false,
            recentReading = null,
            nowMillis = 121_000L,
        )

        val content = assertType<StartResolution.Content>(result).value
        val active = assertType<StartContentState.ActiveSession>(content)
        assertEquals("book", active.learningItemId)
        assertEquals(40, active.currentPage)
        assertEquals(2L, active.elapsedMinutes)
    }

    @Test
    fun activeIntentWinsWhenThereIsNoSession() {
        val item = item(id = "book", mainlineSlot = 1, currentPage = 40)
        val result = resolveStartContent(
            items = listOf(item),
            activeIntent = intent("book"),
            activeSession = null,
            selectedItemId = null,
            setSelectedAsMainline = false,
            recentReading = null,
            nowMillis = 1L,
        )

        val content = assertType<StartResolution.Content>(result).value
        val active = assertType<StartContentState.ActiveIntent>(content)
        assertEquals("拿起《book》，翻到第 40 页。", active.firstAction)
    }

    @Test
    fun inProgressMainlineUsesCurrentPageWithoutIncrementing() {
        val result = resolveStartContent(
            items = listOf(item(id = "book", mainlineSlot = 1, currentPage = 40)),
            activeIntent = null,
            activeSession = null,
            selectedItemId = null,
            setSelectedAsMainline = false,
            recentReading = null,
            nowMillis = 1L,
        )

        val content = assertType<StartResolution.Content>(result).value
        val mainline = assertType<StartContentState.Mainline>(content)
        assertEquals(40, mainline.item.currentPage)
        assertEquals("拿起《book》，翻到第 40 页。", mainline.item.firstAction)
    }

    @Test
    fun pausedMainlineCannotBecomeStartMainline() {
        val result = resolveStartContent(
            items = listOf(
                item(id = "paused", mainlineSlot = 1, status = LearningItemStatus.PAUSED),
                item(id = "available", mainlineSlot = null),
            ),
            activeIntent = null,
            activeSession = null,
            selectedItemId = null,
            setSelectedAsMainline = false,
            recentReading = null,
            nowMillis = 1L,
        )

        val content = assertType<StartResolution.Content>(result).value
        val choose = assertType<StartContentState.ChooseInProgress>(content)
        assertEquals(listOf("available"), choose.items.map { it.id })
        assertNull(choose.selectedItemId)
    }

    @Test
    fun selectionDoesNotImplicitlySetMainline() {
        val source = item(id = "book", mainlineSlot = null)
        val result = resolveStartContent(
            items = listOf(source),
            activeIntent = null,
            activeSession = null,
            selectedItemId = "book",
            setSelectedAsMainline = false,
            recentReading = null,
            nowMillis = 1L,
        )

        val content = assertType<StartResolution.Content>(result).value
        val choose = assertType<StartContentState.ChooseInProgress>(content)
        assertEquals("book", choose.selectedItemId)
        assertFalse(choose.setSelectedAsMainline)
        assertNull(source.mainlineSlot)
    }

    @Test
    fun noInProgressAndEmptyLibraryAreDifferentStates() {
        val noInProgress = resolveStartContent(
            items = listOf(item(id = "done", status = LearningItemStatus.COMPLETED)),
            activeIntent = null,
            activeSession = null,
            selectedItemId = null,
            setSelectedAsMainline = false,
            recentReading = null,
            nowMillis = 1L,
        )
        val empty = resolveStartContent(
            items = emptyList(),
            activeIntent = null,
            activeSession = null,
            selectedItemId = null,
            setSelectedAsMainline = false,
            recentReading = null,
            nowMillis = 1L,
        )

        assertType<StartContentState.NoInProgress>(assertType<StartResolution.Content>(noInProgress).value)
        assertType<StartContentState.EmptyLibrary>(assertType<StartResolution.Content>(empty).value)
    }

    private fun item(
        id: String,
        mainlineSlot: Int? = null,
        status: LearningItemStatus = LearningItemStatus.IN_PROGRESS,
        currentPage: Int = 10,
    ) = LearningItemEntity(
        id = id,
        name = id,
        status = status,
        totalPages = 100,
        currentPage = currentPage,
        mainlineSlot = mainlineSlot,
        firstAction = "",
        createdAt = 1L,
        updatedAt = if (id == "available") 2L else 1L,
        completedAt = null,
    )

    private fun intent(itemId: String) = StudyIntentEntity(
        id = "intent",
        learningItemId = itemId,
        createdAt = 1L,
        transitionedAt = null,
        convertedAt = null,
        endedAt = null,
        outcome = null,
        activeSlot = 1,
    )

    private fun session(itemId: String, currentPage: Int, startedAt: Long) = StudySessionEntity(
        id = "session",
        learningItemId = itemId,
        intentId = "intent",
        startedAt = startedAt,
        stableStartedAt = null,
        endedAt = null,
        startPage = currentPage,
        currentPage = currentPage,
        endPage = null,
        endType = null,
        generatedSummary = null,
        activeSlot = 1,
    )

    private inline fun <reified T> assertType(value: Any?): T {
        assertTrue("Expected ${T::class.java.simpleName}, was ${value?.javaClass?.simpleName}", value is T)
        return value as T
    }
}
