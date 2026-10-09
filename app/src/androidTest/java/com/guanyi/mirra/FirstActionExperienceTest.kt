package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.feature.knowledge.CreateLearningItemScreen
import com.guanyi.mirra.feature.knowledge.CreateLearningItemViewModel
import com.guanyi.mirra.feature.session.PreparationScreen
import com.guanyi.mirra.feature.session.PreparationViewModel
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FirstActionExperienceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var container: TestAppContainer
    private val scopes = mutableListOf<androidx.lifecycle.ViewModel>()

    @Before fun setup() { container = TestAppContainer(ApplicationProvider.getApplicationContext()) }
    @After fun close() {
        rule.activityRule.scenario.close()
        scopes.forEach { it.viewModelScope.cancel() }
        container.close()
    }

    @Test fun createBookRequiresExplicitChoiceAndCapturesControlledScreen() = createAt(360, 1f, capture = true)
    @Test fun createActionAndExitRemainReachableAt320dpDoubleFont() = createAt(320, 2f)

    private fun createAt(width: Int, fontScale: Float, capture: Boolean = false) {
        val vm = CreateLearningItemViewModel(container.learningItemRepository).also(scopes::add)
        var created: String? = null
        val density = rule.activity.resources.displayMetrics.density
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
            MirraTheme { Box(Modifier.width(width.dp).height(680.dp)) {
                CreateLearningItemScreen(vm, { created = it }, {})
            } }
        } }
        input("书名", "第一步测试书")
        input("总页数", "200")
        rule.onNode(hasText("创建") and hasClickAction()).performScrollTo().assertIsNotEnabled()
        assertTrue(runBlocking { container.database.learningItemDao().listAll() }.isEmpty())
        input("第一步动作", "今天读30页")
        rule.onNode(hasText("创建") and hasClickAction()).performScrollTo().assertIsNotEnabled()
        rule.onNode(hasText("第一步动作") and hasSetTextAction()).performScrollTo().performTextReplacement("")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("使用建议动作").performScrollTo()
        if (capture) { rule.waitForIdle(); captureReadingRecordEvidence("v1-experience-create-book") }
        rule.onNodeWithText("使用建议动作").performClick()
        rule.onNode(hasText("创建") and hasClickAction()).performScrollTo().performClick()
        rule.waitUntil(5_000) { created != null }
        val item = runBlocking { container.learningItemRepository.get(requireNotNull(created)) }
        assertEquals("翻到第 1 页，读这一页的第一段。", item?.firstAction)
        assertNull(runBlocking { container.studyWorkflowRepository.observeActiveIntent().first() })
        assertNull(runBlocking { container.studyWorkflowRepository.observeActiveSession().first() })
    }

    @Test fun legacyBlankNeedsChoiceBeforeNewIntentThenExplicitCompletionBeforeSession() {
        legacyNewStart("")
    }

    @Test fun knownAutomaticLegacyActionAlsoRequiresANewExplicitChoice() {
        legacyNewStart("拿起《旧版空动作书》，翻到第 1 页。")
    }

    private fun legacyNewStart(action: String) {
        val item = LearningItemEntity("legacy", "旧版空动作书", LearningItemStatus.IN_PROGRESS, 200, 8, 1, action, 1, 1, null)
        runBlocking { container.database.learningItemDao().insert(item) }
        rule.setContent { MirraApp(container, TopLevelDestination.Start, onDestinationChanged = {}) }
        await("开始准备")
        rule.onNodeWithText("开始准备").performScrollTo().performClick()
        await("使用建议动作")
        assertNull(runBlocking { container.studyWorkflowRepository.observeActiveIntent().first() })
        assertNull(runBlocking { container.studyWorkflowRepository.observeActiveSession().first() })
        rule.onNodeWithText("使用建议动作").performClick()
        rule.onNodeWithText("确认动作，开始准备").performClick()
        await("我已完成这一步，开始阅读")
        val intent = runBlocking { container.studyWorkflowRepository.observeActiveIntent().first() }
        assertTrue(intent != null)
        assertNull(runBlocking { container.studyWorkflowRepository.observeActiveSession().first() })
        rule.onNodeWithText("翻到第 8 页，读这一页的第一段。").assertExists()
        rule.onNodeWithText("我已完成这一步，开始阅读").performScrollTo().performClick()
        rule.waitUntil(5_000) { runBlocking { container.studyWorkflowRepository.observeActiveSession().first() } != null }
        assertEquals(intent?.id, runBlocking { container.studyWorkflowRepository.observeActiveSession().first() }?.intentId)
        assertEquals(1, runBlocking { container.database.sessionDao().listAll().size })
    }

    @Test fun chosenActionCanBeEditedBeforeStartingAndReusedAfterCancellation() {
        val item = runBlocking { container.learningItemRepository.create("可复用动作书", 200, 8,
            "把书放到桌面。", setAsMainline = true) }
        rule.setContent { MirraApp(container, TopLevelDestination.Start, onDestinationChanged = {}) }
        await("编辑第一步")
        rule.onNodeWithText("编辑第一步").performScrollTo().performClick()
        val updated = "站起来，翻到第 8 页读第一段。"
        rule.onNode(hasText("第一步动作") and hasSetTextAction()).performTextReplacement(updated)
        rule.onNodeWithText("保存动作").performClick()
        rule.waitUntil(5_000) { runBlocking { container.learningItemRepository.get(item.id) }?.firstAction == updated }
        assertNull(runBlocking { container.studyWorkflowRepository.observeActiveIntent().first() })
        rule.onNodeWithText("开始准备").performScrollTo().performClick()
        await("我已完成这一步，开始阅读")
        rule.onNodeWithText(updated).assertExists()
        val first = runBlocking { container.studyWorkflowRepository.observeActiveIntent().first() }
        rule.onNodeWithText("取消本次启动").performScrollTo().performClick()
        await("开始准备")
        rule.onNodeWithText("开始准备").performScrollTo().performClick()
        await("我已完成这一步，开始阅读")
        rule.onNodeWithText(updated).assertExists()
        assertTrue(first?.id != runBlocking { container.studyWorkflowRepository.observeActiveIntent().first() }?.id)
        assertEquals(updated, runBlocking { container.learningItemRepository.get(item.id) }?.firstAction)
        assertNull(runBlocking { container.studyWorkflowRepository.observeActiveSession().first() })
    }

    @Test fun preparationUsesChosenActionAndCapturesControlledScreen() = preparationAt(360, 1f, capture = true)
    @Test fun preparationActionsRemainReachableAt320dpDoubleFont() = preparationAt(320, 2f)

    @Test fun legacyBlankActiveIntentKeepsAVisibleActionAndCanBeExplicitlyCompleted() = legacyPreparation("")
    @Test fun legacyGeneratedActiveIntentKeepsAVisibleActionAndCanBeExplicitlyCompleted() =
        legacyPreparation("拿起《旧版准备书》，翻到第 1 页。")

    private fun legacyPreparation(action: String) {
        val item = LearningItemEntity("legacy-preparation", "旧版准备书", LearningItemStatus.IN_PROGRESS, 200, 8, 1, action, 1, 1, null)
        val intent = StudyIntentEntity("legacy-intent", item.id, System.currentTimeMillis(), null, null, null, null, 1)
        runBlocking {
            container.database.learningItemDao().insert(item)
            container.database.intentDao().insert(intent)
        }
        val vm = PreparationViewModel(intent.id, container.studyWorkflowRepository, container.learningItemRepository,
            container.sessionStartCoordinator).also(scopes::add)
        rule.setContent { MirraTheme { PreparationScreen(vm, {}, {}, {}) } }
        await("拿起《旧版准备书》，翻到第 8 页。")
        assertNull(runBlocking { container.studyWorkflowRepository.observeActiveSession().first() })
        assertEquals(action, runBlocking { container.learningItemRepository.get(item.id) }?.firstAction)
        rule.onNodeWithText("我已完成这一步，开始阅读").performScrollTo().performClick()
        rule.waitUntil(5_000) { runBlocking { container.studyWorkflowRepository.observeActiveSession().first() } != null }
        assertEquals(intent.createdAt, runBlocking { container.database.intentDao().get(intent.id) }?.createdAt)
        assertEquals(1, runBlocking { container.database.sessionDao().listAll().size })
    }

    private fun preparationAt(width: Int, fontScale: Float, capture: Boolean = false) {
        val item = runBlocking { container.learningItemRepository.create("动作确认测试书", 200, 8, "站起来，把书放到桌上，翻到第 8 页读第一段。") }
        val intent = runBlocking { container.studyWorkflowRepository.createIntent(item.id) }
        val vm = PreparationViewModel(intent.id, container.studyWorkflowRepository, container.learningItemRepository,
            container.sessionStartCoordinator).also(scopes::add)
        var backs = 0
        val density = rule.activity.resources.displayMetrics.density
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
            MirraTheme { Box(Modifier.width(width.dp).height(680.dp)) {
                PreparationScreen(vm, {}, {}, { backs++ })
            } }
        } }
        await("我已完成这一步，开始阅读")
        rule.waitUntil(5_000) { vm.uiState.value.item != null }
        rule.onNodeWithText(item.firstAction).performScrollTo().assertExists()
        assertNull(runBlocking { container.studyWorkflowRepository.observeActiveSession().first() })
        if (capture) { rule.waitForIdle(); captureReadingRecordEvidence("v1-experience-preparation") }
        rule.onNodeWithText("我已完成这一步，开始阅读").performScrollTo().assertExists()
        rule.onNodeWithText("稍后再说").performScrollTo().performClick()
        assertEquals(1, backs)
        assertEquals(intent.id, runBlocking { container.studyWorkflowRepository.observeActiveIntent().first() }?.id)
    }

    private fun input(label: String, value: String) {
        rule.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextInput(value)
    }
    private fun await(text: String) = rule.waitUntil(5_000) {
        rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }
}
