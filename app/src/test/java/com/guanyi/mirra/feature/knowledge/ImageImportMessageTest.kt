package com.guanyi.mirra.feature.knowledge

import com.guanyi.mirra.data.repository.ImportBatchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageImportMessageTest {
    @Test
    fun partialFailureAndFallbackOverflowAreBothExplained() {
        val message = imageImportMessage(ImportBatchResult(added = emptyList(), failedCount = 1, rejectedCount = 5))

        assertEquals("已添加 0 张，1 张处理失败；单次最多 20 张，已忽略 5 张", message)
    }
}
