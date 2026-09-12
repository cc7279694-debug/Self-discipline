package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstActionResolverTest {
    @Test
    fun blankActionUsesCurrentPageWithoutIncrementing() {
        val item = item(currentPage = 146, firstAction = "")

        assertEquals(
            "拿起《怪诞行为学》，翻到第 146 页。",
            FirstActionResolver.resolve(item),
        )
    }

    @Test
    fun legacyGeneratedActionUsesLatestCurrentPage() {
        val item = item(
            currentPage = 146,
            firstAction = "拿起《怪诞行为学》，翻到第 10 页。",
        )

        assertEquals(
            "拿起《怪诞行为学》，翻到第 146 页。",
            FirstActionResolver.resolve(item),
        )
    }

    @Test
    fun customActionIsTrimmedAndPreserved() {
        val item = item(
            currentPage = 146,
            firstAction = "  把手机放到桌外，再打开书。  ",
        )

        assertEquals("把手机放到桌外，再打开书。", FirstActionResolver.resolve(item))
    }

    private fun item(currentPage: Int, firstAction: String) = LearningItemEntity(
        id = "item-1",
        name = "怪诞行为学",
        status = LearningItemStatus.IN_PROGRESS,
        totalPages = 320,
        currentPage = currentPage,
        mainlineSlot = 1,
        firstAction = firstAction,
        createdAt = 1L,
        updatedAt = 1L,
        completedAt = null,
    )
}
