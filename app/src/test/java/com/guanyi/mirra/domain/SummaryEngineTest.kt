package com.guanyi.mirra.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryEngineTest {
    @Test
    fun `summary reports pages duration and independent note count`() {
        val summary = RuleBasedSummaryEngine().create(
            startPage = 126,
            endPage = 145,
            durationMillis = 45 * 60 * 1_000L,
            noteCount = 3,
        )

        assertEquals("本次阅读 126–145 页，用时 45 分钟，共记录 3 条笔记。", summary)
    }

    @Test
    fun `sub minute session is reported without inventing effective focus time`() {
        val summary = RuleBasedSummaryEngine().create(
            startPage = 8,
            endPage = 8,
            durationMillis = 20_000L,
            noteCount = 0,
        )

        assertEquals("本次阅读第 8 页，用时不足 1 分钟，未记录笔记。", summary)
    }
}
