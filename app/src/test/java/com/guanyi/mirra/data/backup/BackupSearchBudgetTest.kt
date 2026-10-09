package com.guanyi.mirra.data.backup

import com.guanyi.mirra.domain.DefaultSearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BackupSearchBudgetTest {
    @Test fun normalDocumentsKeepTheFrozenSearchOutputUnchanged() {
        val parts = listOf("  阅读中文笔记  ", null, "Ｆｏｃｕｓ", "\uFDFA")
        val before = DefaultSearchEngine().buildDocument(parts)
        BackupSearchBudget.requireBounded(parts, 1_000_000)
        assertEquals(before, DefaultSearchEngine().buildDocument(parts))
    }

    @Test fun compatibilityExpansionCannotAllocateUnboundedTokenDocuments() {
        rejected { BackupSearchBudget.requireBounded(listOf("\uFDFA".repeat(60_000)), 1_000_000) }
    }

    @Test fun rootLocaleLowercaseExpansionIsIncludedInTheBudget() {
        rejected { BackupSearchBudget.requireBounded(listOf("\u0130"), 1) }
    }

    @Test fun documentSeparatorsCountTowardTheJoinedTextBudget() {
        rejected { BackupSearchBudget.requireBounded(listOf("abc", "def"), 6) }
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expanded search document must fail") } catch (_: BackupValidationException) { }
    }
}
