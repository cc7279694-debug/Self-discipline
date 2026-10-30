package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.feature.session.PreparationScreen
import com.guanyi.mirra.feature.session.PreparationViewModel
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Room + preparation UI: offering a repair is separate from writing it or starting. */
class PreparationExperienceV111Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var container: TestAppContainer
    private lateinit var item: LearningItemEntity
    private lateinit var intent: StudyIntentEntity
    private lateinit var vm: PreparationViewModel

    @Before fun setup() { container = TestAppContainer(ApplicationProvider.getApplicationContext()) }

    @After fun close() {
        rule.activityRule.scenario.close()
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        container.close()
    }

    @Test fun stale465At467OffersRepairWithoutWritingOrStarting() {
        prepare("翻到第 465 页，读这一页的第一段。")
        rule.onNodeWithText("当前读到第 467 页，可更新这一步。").performScrollTo().assertExists()
        rule.onNodeWithText("使用当前页码").performScrollTo().assertIsEnabled()
        rule.onNodeWithText("保留原动作").performScrollTo().assertIsEnabled()
        assertFactsUnchanged()
    }

    @Test fun explicitPageUpdatePersistsThroughSameIntentAndStillRequiresCompletion() {
        prepare("翻到第 465 页，读这一页的第一段。")
        val originalIntent = currentIntent()
        rule.onNodeWithText("使用当前页码").performScrollTo().performClick()
        rule.waitUntil(5_000) { currentItem()?.firstAction == "翻到第 467 页，读这一页的第一段。" }
        assertEquals(originalIntent, currentIntent())
        assertNull(activeSession())
        rule.onNodeWithText("我已完成，开始阅读").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { activeSession() != null }
        assertEquals(intent.id, activeSession()?.intentId)
        assertEquals(467, activeSession()?.startPage)
        assertEquals(1, runBlocking { container.database.sessionDao().listAll().size })
    }

    @Test fun keepingOriginalActionDoesNotWriteOrPreventExplicitStart() {
        prepare("翻到第 465 页，读这一页的第一段。")
        val originalIntent = currentIntent()
        rule.onNodeWithText("保留原动作").performScrollTo().performClick()
        assertFactsUnchanged()
        assertEquals(originalIntent, currentIntent())
        rule.onNodeWithText("我已完成，开始阅读").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { activeSession() != null }
        assertEquals(item.firstAction, currentItem()?.firstAction)
        assertEquals(intent.id, activeSession()?.intentId)
    }

    @Test fun keepingLegacyDefaultDoesNotBypassRequiredExplicitChoice() {
        prepare("拿起《页码建议测试书》，翻到第 465 页。", legacy = true)
        rule.onNodeWithText("保留原动作").performScrollTo().performClick()
        assertFactsUnchanged()
        rule.onNodeWithText("我已完成，开始阅读").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("确定第一步").performScrollTo().performClick()
        rule.onNodeWithText("使用建议动作").performScrollTo().performClick()
        rule.onNodeWithText("保存动作").performClick()
        rule.waitUntil(5_000) { currentItem()?.firstAction == "翻到第 467 页，读这一页的第一段。" }
        assertNull(activeSession())
        rule.onNodeWithText("我已完成，开始阅读").performScrollTo().assertIsEnabled()
    }

    @Test fun equalCurrentPageDoesNotOfferAnUpdate() {
        prepare("翻到第 467 页，读这一页的第一段。")
        rule.onNodeWithText("使用当前页码").assertDoesNotExist()
        rule.onNodeWithText("当前读到第 467 页，可更新这一步。").assertDoesNotExist()
        assertFactsUnchanged()
    }

    @Test fun customActionWithOldPageIsNeverClassifiedAsStaleTemplate() {
        prepare("把书翻到第 465 页，标出昨天的问题。")
        rule.onNodeWithText("使用当前页码").assertDoesNotExist()
        assertFactsUnchanged()
    }

    @Test fun reviewOldPageIsNeverClassifiedAsStaleTemplate() {
        prepare("复习第 465 页，读这一页的第一段。")
        rule.onNodeWithText("使用当前页码").assertDoesNotExist()
        assertFactsUnchanged()
    }

    @Test fun specialPageExpressionIsNeverClassifiedAsStaleTemplate() {
        prepare("翻到第 465–467 页，读这一页的第一段。")
        rule.onNodeWithText("使用当前页码").assertDoesNotExist()
        assertFactsUnchanged()
    }

    @Test fun preparationShowsBookProgressAndHonestFutureServiceCheck() {
        prepare("把书放到桌上，读第一段。")
        rule.onNodeWithText("页码建议测试书").assertExists()
        rule.onNodeWithText("第 467 / 600 页").assertExists()
        rule.onNodeWithText("开始后将检查监测服务。").performScrollTo().assertExists()
        rule.onNodeWithText("FULL", substring = true).assertDoesNotExist()
        rule.onNodeWithText("READY", substring = true).assertDoesNotExist()
        rule.onNodeWithText("本次监测已运行", substring = true).assertDoesNotExist()
        assertFactsUnchanged()
    }

    @Test fun openingAndCancellingEditorNeverWritesOrStarts() {
        prepare("翻到第 465 页，读这一页的第一段。")
        val originalIntent = currentIntent()
        rule.onNodeWithText("编辑第一步").performScrollTo().performClick()
        rule.onNodeWithText("第一步动作").assertExists()
        assertFactsUnchanged()
        rule.onNodeWithText("取消").performClick()
        assertEquals(originalIntent, currentIntent())
        assertFactsUnchanged()
    }

    @Test fun failedPageUpdateThenFailedCustomRetryPreservesUserDraftAndError() {
        val attempts = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
        var attemptNumber = 0
        prepare("翻到第 465 页，读这一页的第一段。", beforePreparationSave = {
            val number = ++attemptNumber
            val gate = CompletableDeferred<Unit>()
            attempts.send(gate)
            gate.await()
            throw IllegalStateException("第 $number 次保存失败")
        })
        val originalIntent = currentIntent()
        rule.onNodeWithText("使用当前页码").performScrollTo().performClick()
        val firstSave = runBlocking { withTimeout(5_000) { attempts.receive() } }
        rule.waitForIdle()
        firstSave.complete(Unit)
        rule.waitUntil(5_000) { vm.error == "第 1 次保存失败" && !vm.savingFirstAction }

        val draft = "把书放到桌上，读完眼前的第一段。"
        rule.onNode(hasText("第一步动作") and hasSetTextAction()).performTextReplacement(draft)
        rule.onNodeWithText("保存动作").performClick()
        val retry = runBlocking { withTimeout(5_000) { attempts.receive() } }
        // Keep the retry pending across an actual recomposition, rather than throwing synchronously.
        rule.waitForIdle()
        retry.complete(Unit)
        rule.waitUntil(5_000) { vm.error == "第 2 次保存失败" && !vm.savingFirstAction }

        rule.onNode(hasText("第一步动作") and hasSetTextAction()).assertTextContains(draft)
        assertTrue(rule.onAllNodes(hasText("第 2 次保存失败")).fetchSemanticsNodes().isNotEmpty())
        assertEquals(originalIntent, currentIntent())
        assertFactsUnchanged()
        attempts.close()
    }

    private fun prepare(action: String, legacy: Boolean = false, beforePreparationSave: (suspend () -> Unit)? = null) {
        item = if (legacy) {
            LearningItemEntity("legacy-book", "页码建议测试书", LearningItemStatus.IN_PROGRESS,
                600, 467, 1, action, 1, 1, null).also {
                runBlocking { container.database.learningItemDao().insert(it) }
            }
        } else runBlocking { container.learningItemRepository.create("页码建议测试书", 600, 467, action) }
        intent = if (legacy) {
            // A historical Intent predates today's explicit-action admission gate.
            StudyIntentEntity("legacy-intent", item.id, System.currentTimeMillis(), null,
                null, null, null, 1).also {
                runBlocking { container.database.intentDao().insert(it) }
            }
        } else runBlocking { container.studyWorkflowRepository.createIntent(item.id) }
        val items = if (beforePreparationSave == null) container.learningItemRepository else {
            object : LearningItemRepository by container.learningItemRepository {
                override suspend fun updatePreparationFirstAction(intentId: String, firstAction: String): LearningItemEntity {
                    beforePreparationSave()
                    return container.learningItemRepository.updatePreparationFirstAction(intentId, firstAction)
                }
            }
        }
        vm = PreparationViewModel(intent.id, container.studyWorkflowRepository, items,
            container.sessionStartCoordinator, clock = { intent.createdAt + 185_000 })
        rule.setContent { MirraTheme { PreparationScreen(vm, {}, {}, {}) } }
        rule.waitUntil(5_000) { vm.uiState.value.item != null && currentIntent()?.transitionedAt != null }
        rule.onNodeWithText("已等待 3 分 5 秒").assertExists()
    }

    private fun currentItem() = runBlocking { container.learningItemRepository.get(item.id) }
    private fun currentIntent() = runBlocking { container.database.intentDao().get(intent.id) }
    private fun activeSession() = runBlocking { container.studyWorkflowRepository.observeActiveSession().first() }

    private fun assertFactsUnchanged() {
        assertEquals(item, currentItem())
        assertEquals(intent.createdAt, currentIntent()?.createdAt)
        assertNull(currentIntent()?.convertedAt)
        assertNull(currentIntent()?.endedAt)
        assertNull(activeSession())
    }
}
