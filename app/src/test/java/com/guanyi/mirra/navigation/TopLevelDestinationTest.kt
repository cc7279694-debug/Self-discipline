package com.guanyi.mirra.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class TopLevelDestinationTest {
    @Test
    fun `stored knowledge destination is restored`() {
        assertEquals(TopLevelDestination.Knowledge, TopLevelDestination.fromStorageValue("knowledge"))
    }

    @Test
    fun `unknown stored destination falls back to start`() {
        assertEquals(TopLevelDestination.Start, TopLevelDestination.fromStorageValue("corrupted-value"))
    }
}
