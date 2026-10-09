package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Controlled in-memory facts; opening history/trends must not mutate learning facts. */
class TrendsNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var container: TestAppContainer
    @Before fun setup() { container = TestAppContainer(ApplicationProvider.getApplicationContext()) }
    @After fun cleanup() { rule.activityRule.scenario.close(); container.close() }

    @Test fun mineTrendsRangesAndBackPreserveFacts() {
        runBlocking { insertReadingRecordFixture(container) }
        val before = facts()
        rule.setContent { MirraTheme { MirraApp(container, TopLevelDestination.Profile, {}) } }
        await("详细分析")
        rule.onNodeWithText("详细分析").performScrollTo().performClick()
        await("趋势")
        for (range in listOf("THIRTY_DAYS", "NINETY_DAYS", "ALL")) {
            rule.onNodeWithTag("trends-range-$range").performClick()
            rule.waitForIdle()
        }
        captureReadingRecordEvidence("phase4a-trends-all")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("返回"))
        rule.onNodeWithText("返回").performClick()
        await("详细分析")
        captureReadingRecordEvidence("phase4a-mine")
        assertEquals(before, facts())
    }

    @Test fun globalHistoryOpensExistingRecordAndReturnsToOwnOrigin() {
        runBlocking { insertReadingRecordFixture(container) }
        val before = facts()
        rule.setContent { MirraTheme { MirraApp(container, TopLevelDestination.Profile, {}) } }
        await("阅读记录")
        rule.onNodeWithText("阅读记录").performScrollTo().performClick()
        await("统一记录测试")
        captureReadingRecordEvidence("phase4a-global-history")
        rule.onNodeWithText("统一记录测试").performClick()
        await("40 → 58 页 · 18 页")
        rule.onNodeWithText("查看本次记录").performScrollTo().performClick()
        rule.onNodeWithText("正在回到学习").assertExists()
        captureReadingRecordEvidence("phase4a-history-detail")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("返回"))
        rule.onNodeWithText("返回").performClick()
        await("统一记录测试")
        rule.onNodeWithText("返回").performClick()
        await("我的")
        assertEquals(before, facts())
    }

    private fun await(text: String) = rule.waitUntil(5_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun facts() = runBlocking {
        listOf("study_intents", "study_sessions", "session_focus_contexts", "session_segments", "focus_events", "notes")
            .map { table ->
                container.database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
                    }
                }
            }
    }
}
