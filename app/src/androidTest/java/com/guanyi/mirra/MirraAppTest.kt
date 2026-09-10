package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.navigation.TopLevelDestination
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
    fun tearDown() = container.close()

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
}
