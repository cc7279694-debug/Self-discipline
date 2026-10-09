package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.ui.theme.MirraTheme
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Room facts and shipping screens on Android; synthetic isolated data, not a private user history. */
class TrendsExperienceCaptureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var container: TestAppContainer
    @Before fun setup() {
        container = TestAppContainer(ApplicationProvider.getApplicationContext())
        runBlocking { seed() }
    }
    @After fun cleanup() { rule.activityRule.scenario.close(); container.close() }

    @Test fun realRoomWeeklyChartAndDetailedAnalysisAreReachableWithoutWritingFacts() {
        val before = runBlocking { container.database.sessionDao().listAll().size }
        rule.setContent { MirraTheme { MirraApp(container, TopLevelDestination.Profile, {}) } }
        await("本周学习")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("weekly-reading-duration").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("weekly-reading-duration").assertTextEquals("1 小时 35 分钟")
        rule.onNodeWithText("详细分析").assertIsDisplayed()
        rule.waitForIdle(); captureReadingRecordEvidence("v1-experience-mine")
        rule.onNodeWithText("详细分析").performClick()
        await("启动")
        rule.onNodeWithTag("trend-conversion").assertTextEquals("3 / 4 · 75%")
        rule.waitForIdle(); captureReadingRecordEvidence("v1-experience-trends")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("专注"))
        rule.onNodeWithText("专注").assertIsDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("trends-reading-chart"))
        rule.onNodeWithTag("trends-reading-chart").assertExists()
        rule.waitForIdle(); captureReadingRecordEvidence("v1-experience-trends-daily")
        org.junit.Assert.assertEquals(before, runBlocking { container.database.sessionDao().listAll().size })
    }

    private suspend fun seed() {
        val item = container.learningItemRepository.create("体验验证书籍", 300, 40,
            "把书放到桌上，翻到上次阅读的位置", true)
        val now = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val original = readingRecordTestSource()
        for ((index, minutes) in listOf(20, 30, 45).withIndex()) {
            val end = today.minusDays((index * 2).toLong()).atStartOfDay(ZoneId.systemDefault())
                .plusHours(1).toInstant().toEpochMilli().coerceAtMost(now.toEpochMilli() - 1_000)
            val start = end - minutes * 60_000L
            val intentId = "experience-intent-$index"
            val sessionId = "experience-session-$index"
            container.database.intentDao().insert(StudyIntentEntity(intentId, item.id, start - 60_000,
                start, start, start, IntentOutcome.CONVERTED, null))
            container.database.sessionDao().insert(original.session.copy(id = sessionId, learningItemId = item.id,
                intentId = intentId, startedAt = start, endedAt = end))
            container.database.focusDao().insertContext(original.context!!.copy(sessionId = sessionId,
                lastHeartbeatAt = end, createdAt = start, updatedAt = end))
            original.segments.forEach { segment ->
                fun scale(value: Long) = start + (value - original.session.startedAt) * minutes / 30
                container.database.focusDao().insertSegment(segment.copy(id = "$sessionId-${segment.id}",
                    sessionId = sessionId, startedAt = scale(segment.startedAt), endedAt = scale(segment.endedAt!!)))
            }
        }
        val abandonedAt = now.toEpochMilli() - 1_000
        container.database.intentDao().insert(StudyIntentEntity("experience-abandoned", item.id,
            abandonedAt - 60_000, abandonedAt, null, abandonedAt, IntentOutcome.ABANDONED, null))
    }

    private fun await(text: String) = rule.waitUntil(5_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}
