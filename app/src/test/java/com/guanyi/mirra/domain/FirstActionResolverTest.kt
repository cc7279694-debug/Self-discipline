package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstActionResolverTest {
    @Test
    fun blankActionNeedsAnExplicitChoiceBeforeNewIntent() {
        val item = item(currentPage = 146, firstAction = "")

        assertEquals(
            "",
            FirstActionResolver.resolve(item),
        )
    }

    @Test
    fun legacyGeneratedActionNeedsAnExplicitChoiceBeforeNewIntent() {
        val item = item(
            currentPage = 146,
            firstAction = "拿起《怪诞行为学》，翻到第 10 页。",
        )

        assertEquals(
            "",
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

    @Test
    fun obviousLearningGoalsCannotStandInForAnAction() {
        listOf("完成这本书", "读完这本书", "提高阅读能力", "学会 Kotlin").forEach { goal ->
            assertEquals("", FirstActionResolver.resolve(item(146, goal)))
        }
    }

    @Test
    fun invisibleTextCannotStandInForAnAction() {
        assertEquals("", FirstActionResolver.resolve(item(146, "\u200B\uFEFF")))
    }

    @Test
    fun pageAndDurationTargetsCannotStandInForAnImmediateAction() {
        listOf("今天读30页", "读30页", "阅读30分钟", "本次阅读 1 小时", "读完30页", "读完《怪诞行为学》").forEach { goal ->
            assertEquals("", FirstActionResolver.resolve(item(146, goal)))
        }
    }

    @Test
    fun concreteAdjustmentIsNotMistakenForAnAbstractImprovementGoal() {
        assertEquals("提高台灯亮度，再翻开书。", FirstActionResolver.resolve(item(146, "提高台灯亮度，再翻开书。")))
    }

    @Test
    fun punctuationCannotStandInForAnAction() {
        assertEquals("", FirstActionResolver.resolve(item(146, "...！！！")))
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
