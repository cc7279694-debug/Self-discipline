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

/** Navigation alone must not create, export, restore or mutate any learning facts. */
class DataManagementNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var container: TestAppContainer
    @Before fun setup() { container = TestAppContainer(ApplicationProvider.getApplicationContext()) }
    @After fun cleanup() { rule.activityRule.scenario.close(); container.close() }

    @Test fun mineFlatDataManagementEntryAndBackPreserveStoredFacts() {
        runBlocking { insertReadingRecordFixture(container) }
        val before = facts()
        rule.setContent { MirraTheme { MirraApp(container, TopLevelDestination.Profile, {}) } }
        await("数据管理")
        rule.onNodeWithText("数据管理").performScrollTo().performClick()
        await("完整备份包含私人笔记和图片。文件未加密，请妥善保管。Mirra 不会自动上传；保存位置由你选择，所选服务可能联网。")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("返回"))
        rule.onNodeWithText("返回").performClick()
        await("我的")
        assertEquals(before, facts())
    }

    private fun await(text: String) = rule.waitUntil(5_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun facts() = runBlocking {
        listOf("learning_items", "study_intents", "study_sessions", "session_focus_contexts", "session_segments", "focus_events", "notes")
            .map { table -> container.database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
            } }
    }
}
