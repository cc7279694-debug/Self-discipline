package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FirstActionPageUpdateTest {
    @Test fun canonical465Offers467WithoutChangingStoredAction() {
        val item = item("翻到第 465 页，读这一页的第一段。")
        assertEquals("翻到第 467 页，读这一页的第一段。", FirstActionResolver.currentPageUpdate(item))
        assertEquals("翻到第 465 页，读这一页的第一段。", item.firstAction)
        assertEquals(item.firstAction, FirstActionResolver.requireChosen(item.firstAction))
    }

    @Test fun samePageHasNoUpdate() {
        assertNull(FirstActionResolver.currentPageUpdate(item("翻到第 467 页，读这一页的第一段。")))
    }

    @Test fun legacyDefaultOffersAnExplicitValidReplacementButOriginalRemainsInvalid() {
        val item = item("拿起《阅读测试书》，翻到第 465 页。")
        assertEquals("翻到第 467 页，读这一页的第一段。", FirstActionResolver.currentPageUpdate(item))
        assertEquals("", FirstActionResolver.resolve(item))
        assertThrows(IllegalArgumentException::class.java) { FirstActionResolver.requireChosen(item.firstAction) }
    }

    @Test fun samePageLegacyDefaultRemainsInvalidWithoutOfferingAPageCorrection() {
        val item = item("拿起《阅读测试书》，翻到第 467 页。")
        assertNull(FirstActionResolver.currentPageUpdate(item))
        assertEquals("", FirstActionResolver.resolve(item))
        assertThrows(IllegalArgumentException::class.java) { FirstActionResolver.requireChosen(item.firstAction) }
    }

    @Test fun arbitraryCustomReviewAndSpecialExpressionsAreUntouched() {
        listOf(
            "把书翻到第 465 页，标出昨天的问题。",
            "复习第 465 页，读这一页的第一段。",
            "翻到第 465 页，读这一页的第一段。然后复习上一节。",
            "翻到第 465–467 页，读这一页的第一段。",
            "翻到第 四百六十五 页，读这一页的第一段。",
            "拿起《另一本书》，翻到第 465 页。",
            "翻到第 465 页，读第一段。",
        ).forEach { action -> assertNull(action, FirstActionResolver.currentPageUpdate(item(action))) }
    }

    @Test fun noncanonicalSpacingPunctuationAndNumericFormsAreUntouched() {
        listOf(
            " 翻到第 465 页，读这一页的第一段。",
            "翻到第465页，读这一页的第一段。",
            "翻到第 465 页，读这一页的第一段",
            "翻到第 0465 页，读这一页的第一段。",
            "翻到第 0 页，读这一页的第一段。",
            "翻到第 -1 页，读这一页的第一段。",
            "翻到第 99999999999999999999 页，读这一页的第一段。",
        ).forEach { action -> assertNull(action, FirstActionResolver.currentPageUpdate(item(action))) }
    }

    @Test fun invalidCurrentPageNeverProducesAnAction() {
        assertNull(FirstActionResolver.currentPageUpdate(item("翻到第 465 页，读这一页的第一段。", 0)))
        assertNull(FirstActionResolver.currentPageUpdate(item("翻到第 465 页，读这一页的第一段。", -1)))
    }

    @Test fun numericBookTitleDoesNotGetRewrittenAsPage() {
        val item = item("拿起《1984》，翻到第 465 页。").copy(name = "1984")
        assertEquals("翻到第 467 页，读这一页的第一段。", FirstActionResolver.currentPageUpdate(item))
    }

    private fun item(action: String, page: Int = 467) = LearningItemEntity(
        "item", "阅读测试书", LearningItemStatus.IN_PROGRESS, 600, page, 1, action, 1, 1, null,
    )
}
