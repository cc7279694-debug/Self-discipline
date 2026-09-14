package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.navigation.TopLevelDestination
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MirraAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var container: TestAppContainer

    @Before
    fun setUp() {
        container = TestAppContainer(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        composeRule.activityRule.scenario.close()
        container.close()
    }

    @Test
    fun knowledgeTabOpensKnowledgeScreen() {
        composeRule.setContent {
            MirraApp(
                container = container,
                restoredDestination = TopLevelDestination.Start,
                onDestinationChanged = {},
            )
        }

        composeRule.onNode(hasText("知识") and hasClickAction()).performClick().assertIsSelected()
        composeRule.onNodeWithText("创建").assertExists()
    }

    @Test
    fun profileTabReadsRecentNormalSessionsThroughAnalyticsWiring() {
        val now = System.currentTimeMillis()
        runBlocking {
            container.database.learningItemDao().insert(
                LearningItemEntity("analytics-book", "Analytics Book", LearningItemStatus.IN_PROGRESS, 320, 20, null, "", now, now, null),
            )
            insertEndedSession("normal", now - Duration.ofMinutes(1).toMillis(), SessionEndType.NORMAL)
            insertEndedSession("abnormal", now - Duration.ofMinutes(2).toMillis(), SessionEndType.ABNORMAL)
        }
        composeRule.setContent {
            MirraApp(container, TopLevelDestination.Start, onDestinationChanged = {})
        }

        composeRule.onNode(hasText("我的") and hasClickAction()).performClick().assertIsSelected()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("1 次")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("最近 7 天").assertExists()
        composeRule.onNodeWithText("4 页").assertExists()
    }

    @Test
    fun learningItemDetailShowsRepositoryBackedPaceAndHistory() {
        val now = System.currentTimeMillis()
        runBlocking {
            container.database.learningItemDao().insert(
                LearningItemEntity("analytics-book", "Analytics Book", LearningItemStatus.IN_PROGRESS, 320, 20, null, "", now, now, null),
            )
            insertEndedSession("normal-1", now - Duration.ofMinutes(1).toMillis(), SessionEndType.NORMAL)
            insertEndedSession("normal-2", now - Duration.ofHours(1).toMillis(), SessionEndType.NORMAL)
            insertEndedSession("normal-3", now - Duration.ofHours(2).toMillis(), SessionEndType.NORMAL)
        }
        composeRule.setContent {
            MirraApp(container, TopLevelDestination.Knowledge, onDestinationChanged = {})
        }

        composeRule.onNodeWithText("Analytics Book").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("最近约 24 页/小时")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("阅读历史").assertExists()
        composeRule.onNodeWithText("近期节奏仍在积累，暂不估算完成日期").assertExists()
    }

    private suspend fun insertEndedSession(id: String, endedAt: Long, endType: SessionEndType) {
        val intentId = "intent-$id"
        container.database.intentDao().insert(
            StudyIntentEntity(intentId, "analytics-book", endedAt - 700_000L, endedAt - 650_000L, endedAt - 600_000L, endedAt - 600_000L, IntentOutcome.CONVERTED, null),
        )
        container.database.sessionDao().insert(
            StudySessionEntity(
                id = id,
                learningItemId = "analytics-book",
                intentId = intentId,
                startedAt = endedAt - Duration.ofMinutes(10).toMillis(),
                stableStartedAt = null,
                endedAt = endedAt,
                startPage = 1,
                currentPage = 5,
                endPage = 5,
                endType = endType,
                generatedSummary = null,
                activeSlot = null,
            ),
        )
    }
}
