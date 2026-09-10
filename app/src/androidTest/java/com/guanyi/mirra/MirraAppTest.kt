package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.guanyi.mirra.navigation.TopLevelDestination
import org.junit.Rule
import org.junit.Test

class MirraAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun knowledgeTabOpensKnowledgeScreen() {
        composeRule.setContent {
            MirraApp(
                restoredDestination = TopLevelDestination.Start,
                onDestinationChanged = {},
            )
        }

        composeRule.onNode(hasText("知识") and hasClickAction()).performClick().assertIsSelected()
        composeRule.onNodeWithText("知识会在学习中自然沉淀").assertExists()
    }
}
