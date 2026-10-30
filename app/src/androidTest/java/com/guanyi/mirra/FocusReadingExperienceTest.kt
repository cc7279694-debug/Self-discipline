package com.guanyi.mirra

import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.NoteEntity
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.SessionRiskAppSnapshotEntity
import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.data.repository.NoteRepository
import com.guanyi.mirra.domain.monitoring.FocusActionResult
import com.guanyi.mirra.domain.monitoring.FocusStatusUiModel
import com.guanyi.mirra.feature.session.SessionScreen
import com.guanyi.mirra.feature.session.SessionViewModel
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real isolated Room + Compose. Does not change the installed user's storage owner or permissions. */
class FocusReadingExperienceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureBeforeReadingStageOnlyWhenExplicitlyRequested() {
        assumeTrue("Explicit old-UI capture only", arguments("v111BeforeCapture") == "true")
        scenario {
            rule.onNode(hasSetTextAction() and hasText("写下摘录或想法")).assertExists()
            captureReadingRecordEvidence("v111-before-reading")
        }
    }

    @Test fun readingStageShowsSecondsAndKeepsFormsBehindExplicitActions() = scenario {
        rule.onNodeWithText("本次阅读").assertIsDisplayed()
        rule.onNodeWithTag("session-reading-timer").assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText("写下摘录或想法")).assertDoesNotExist()
        rule.onNode(hasSetTextAction() and hasText("当前页码")).assertDoesNotExist()
        rule.onNodeWithText("记录想法").assertExists()
        capture("v111-reading")
    }

    @Test fun closingAndReopeningNotePanelPreservesDraftAndOneDurableNote() = scenario { fixture ->
        rule.onNodeWithText("记录想法").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("写下摘录或想法")).performTextInput("窗口草稿")
        rule.waitUntil(5_000) { fixture.vm.savedMessage == "已自动保存" }
        val original = runBlocking { fixture.container.noteRepository.observeForSession(fixture.sessionId).first() }.single()
        capture("v111-note-expanded")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("收起笔记").performClick()
        rule.onNode(hasSetTextAction() and hasText("写下摘录或想法")).assertDoesNotExist()
        rule.runOnIdle { assertEquals("窗口草稿", fixture.vm.draftContent) }
        rule.onNodeWithText("记录想法").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("写下摘录或想法")).assertTextContains("窗口草稿")
        val current = runBlocking { fixture.container.noteRepository.observeForSession(fixture.sessionId).first() }.single()
        assertEquals(original.id, current.id)
        assertEquals("窗口草稿", current.content)
    }

    @Test fun saveAndContinueKeepsNoteSheetMountedWhileTheCurrentDraftIsSaving() {
        var barrier: SecondSaveBarrier? = null
        scenario(noteRepositoryDecorator = { SecondSaveBarrier(it).also { created -> barrier = created } }) { fixture ->
            val gate = checkNotNull(barrier)
            try {
                val original = openAndAutoSaveNote(fixture, "先写下这一段的理解。")
                blockSaveAndContinue(fixture, gate, original)
                assertRetainedDisabledNoteSheet(original.content)
                gate.release.complete(Unit)
                assertRotatedBlankDraftWithOneSavedNote(fixture, original)
                noteChip("问题").performScrollTo().assertIsEnabled().performClick().assertIsSelected()
                rule.runOnIdle { assertEquals(NoteSemanticType.QUESTION, fixture.vm.draftType) }

                // Prove the newly blank draft actually owns a new ID on its next real autosave.
                rule.onNode(hasSetTextAction() and hasText("写下摘录或想法")).performTextInput("接着写下一条")
                rule.waitUntil(5_000) {
                    runBlocking { fixture.container.noteRepository.observeForSession(fixture.sessionId).first() }.size == 2
                }
                val notes = runBlocking { fixture.container.noteRepository.observeForSession(fixture.sessionId).first() }
                val retained = notes.single { it.id == original.id }
                assertEquals(original.content, retained.content)
                assertEquals(original.createdAt, retained.createdAt)
                val next = notes.single { it.id != original.id }
                assertEquals("接着写下一条", next.content)
                assertEquals(NoteSemanticType.QUESTION, next.semanticType)
                assertNotEquals(original.id, gate.saveIds.last())
            } finally { gate.release.complete(Unit) }
        }
    }

    @Test fun failedSaveAndContinueKeepsTheSameDraftAndAllowsExplicitRetry() {
        var barrier: SecondSaveBarrier? = null
        scenario(noteRepositoryDecorator = { SecondSaveBarrier(it, failSecondSave = true).also { created -> barrier = created } }) { fixture ->
            val gate = checkNotNull(barrier)
            try {
                val original = openAndAutoSaveNote(fixture, "失败后仍然保留的这一条。")
                blockSaveAndContinue(fixture, gate, original)
                assertRetainedDisabledNoteSheet(original.content)
                gate.release.complete(Unit)
                rule.waitUntil(5_000) { fixture.vm.error == NOTE_ROTATION_FAILURE && fixture.vm.canEditLearning }
                rule.onNodeWithText("收起笔记").assertExists()
                rule.onNodeWithText("写下摘录或想法").assertTextContains(original.content).assertIsEnabled()
                rule.onNodeWithText(NOTE_ROTATION_FAILURE).assertExists()
                assertEquals(listOf(original.id, original.id), gate.saveIds.toList())
                assertEquals(original, runBlocking { fixture.container.noteRepository.observeForSession(fixture.sessionId).first() }.single())

                // The user's unchanged draft is explicitly retried; no automatic retry or new ID.
                rule.onNodeWithText("保存并记下一条").performScrollTo().assertIsEnabled().performClick()
                assertRotatedBlankDraftWithOneSavedNote(fixture, original)
                assertEquals(listOf(original.id, original.id, original.id), gate.saveIds.toList())
                noteChip("问题").performScrollTo().assertIsEnabled().performClick().assertIsSelected()
            } finally { gate.release.complete(Unit) }
        }
    }

    private fun openAndAutoSaveNote(fixture: Fixture, text: String): NoteEntity {
        rule.onNodeWithTag("session-note-action").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("写下摘录或想法")).performTextInput(text)
        rule.waitUntil(5_000) { fixture.vm.savedMessage == "已自动保存" }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        return runBlocking { fixture.container.noteRepository.observeForSession(fixture.sessionId).first() }.single().also {
            assertEquals(text, it.content)
        }
    }

    private fun blockSaveAndContinue(fixture: Fixture, gate: SecondSaveBarrier, original: NoteEntity) {
        assertEquals(listOf(original.id), gate.saveIds.toList())
        rule.onNodeWithText("保存并记下一条").performScrollTo().performClick()
        rule.waitUntil(5_000) { gate.entered.isCompleted && !fixture.vm.canEditLearning }
        assertEquals(original.id, runBlocking { gate.entered.await() })
        rule.runOnIdle { assertEquals(original.content, fixture.vm.draftContent); assertFalse(fixture.vm.canEditLearning) }
        assertEquals(original, runBlocking { fixture.container.noteRepository.observeForSession(fixture.sessionId).first() }.single())
    }

    private fun assertRetainedDisabledNoteSheet(text: String) {
        // Query the rendered busy composition while the repository barrier is still closed.
        rule.waitForIdle()
        rule.onNodeWithText("收起笔记").assertExists()
        rule.onNodeWithText("写下摘录或想法").assertExists().assertTextContains(text).assertIsNotEnabled()
        rule.onNodeWithText("笔记页码（可选）").assertExists().assertIsNotEnabled()
        for (label in listOf("摘录", "我的理解", "问题", "总结")) noteChip(label).assertExists().assertIsNotEnabled()
        rule.onNodeWithText("保存并记下一条").assertExists().assertIsNotEnabled()
    }

    private fun assertRotatedBlankDraftWithOneSavedNote(fixture: Fixture, original: NoteEntity) {
        rule.waitUntil(5_000) { fixture.vm.canEditLearning && fixture.vm.draftContent.isBlank() }
        rule.onNodeWithText("收起笔记").assertExists()
        rule.onNodeWithText("写下摘录或想法")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        val persisted = runBlocking { fixture.container.noteRepository.observeForSession(fixture.sessionId).first() }.single()
        assertEquals(original.id, persisted.id)
        assertEquals(original.content, persisted.content)
        assertEquals(original.createdAt, persisted.createdAt)
    }

    private fun noteChip(label: String) = rule.onNode(hasText(label) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))

    /** Real Room writes are retained; only the second save's completion is controlled. */
    private class SecondSaveBarrier(
        private val delegate: NoteRepository,
        private val failSecondSave: Boolean = false,
    ) : NoteRepository by delegate {
        val entered = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()
        val saveIds = CopyOnWriteArrayList<String>()
        private val calls = AtomicInteger()

        override suspend fun save(learningItemId: String, sessionId: String?, content: String,
            pageNumber: Int?, semanticType: NoteSemanticType, id: String?): NoteEntity {
            val saveId = checkNotNull(id)
            saveIds += saveId
            if (calls.incrementAndGet() == 2) {
                entered.complete(saveId)
                release.await()
                if (failSecondSave) throw IOException(NOTE_ROTATION_FAILURE)
            }
            return delegate.save(learningItemId, sessionId, content, pageNumber, semanticType, id)
        }
    }

    @Test fun focusDisplayBackRestoresOwnedScreenSettingAndDoesNotLeaveReading() = scenario { fixture ->
        val original = rule.runOnIdle { fixture.view.keepScreenOn }
        val bars = WindowCompat.getInsetsController(rule.activity.window, fixture.view)
        val statusBefore = rule.runOnIdle { bars.isAppearanceLightStatusBars }
        val navigationBefore = rule.runOnIdle { bars.isAppearanceLightNavigationBars }
        rule.onNodeWithText("专注显示").performScrollTo().performClick()
        rule.onNodeWithText("退出专注显示").assertExists()
        rule.waitUntil(5_000) { fixture.view.keepScreenOn }
        rule.runOnIdle { assertFalse(bars.isAppearanceLightStatusBars); assertFalse(bars.isAppearanceLightNavigationBars) }
        rule.onNode(hasSetTextAction() and hasText("写下摘录或想法")).assertDoesNotExist()
        capture("v111-focus-display")
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) { fixture.view.keepScreenOn == original }
        rule.runOnIdle { assertEquals(statusBefore, bars.isAppearanceLightStatusBars); assertEquals(navigationBefore, bars.isAppearanceLightNavigationBars) }
        rule.onNodeWithText("退出专注显示").assertDoesNotExist()
        rule.runOnIdle { assertFalse(fixture.left); assertTrue(fixture.vm.canEditLearning) }
        assertNull(runBlocking { fixture.container.studyWorkflowRepository.observeSession(fixture.sessionId).first() }!!.endedAt)
    }

    @Test fun backgroundFocusDisplayReleasesThenResumedVisibleDisplayReacquires() = scenario { fixture ->
        val original = rule.runOnIdle { fixture.view.keepScreenOn }
        rule.onNodeWithText("专注显示").performScrollTo().performClick()
        rule.waitUntil(5_000) { fixture.view.keepScreenOn }
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.waitUntil(5_000) { fixture.view.keepScreenOn == original }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil(5_000) { fixture.view.keepScreenOn }
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) { fixture.view.keepScreenOn == original }
    }

    @Test fun fullCoverageWithoutBindingCannotBePresentedAsHealthyService() = scenario(full = true) {
        rule.onNodeWithText("监测正常").assertDoesNotExist()
        rule.onNodeWithText("正在确认监测状态").assertExists()
    }

    @Test fun unavailableSessionDetailsDoNotInventAnEmptyRiskList() = scenario {
        rule.onNodeWithText("本场详情").performScrollTo().performClick()
        rule.onNodeWithText("本场名单暂不可确认", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("本场没有选择风险 App", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun sessionDetailsUseFrozenLabelsInsteadOfTheNextSessionsGlobalListOrRawPackage() = scenario(withSnapshot = true) {
        rule.onNodeWithText("本场详情").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("本场旧名称"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("本场旧名称", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("风险 App", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("下一场名称", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("com.example.unknownlabel", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun modeChangesDoNotInventInvisiblePageEvidence() = scenario { fixture ->
        val actions = fixture.container.focusSessionActions
        rule.waitUntil(5_000) { actions.calls.contains("evidence:false:true") }
        val before = rule.runOnIdle { actions.calls.size }
        rule.onNodeWithText("专注显示").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("退出专注显示").performClick()
        rule.waitForIdle()
        val during = rule.runOnIdle { actions.calls.drop(before) }
        assertFalse("Changing display mode must not reset continuous evidence: $during",
            during.contains("evidence:false:false"))
    }

    @Test fun monoFocusActionsAndActualFailureRemainReadable() = focusContrast(MirraThemeId.MONO)
    @Test fun blueFocusActionsAndActualFailureRemainReadable() = focusContrast(MirraThemeId.BLUE)

    @Test fun focusBarsRestoreTheLatestThemeInsteadOfTheThemeBeforeItsLateEmission() = scenario { fixture ->
        val bars = WindowCompat.getInsetsController(rule.activity.window, fixture.view)
        rule.onNodeWithText("专注显示").performScrollTo().performClick()
        rule.runOnIdle { fixture.theme.value = MirraThemeId.NIGHT }
        rule.waitForIdle()
        rule.onNodeWithText("退出专注显示").performClick()
        rule.runOnIdle { assertFalse(bars.isAppearanceLightStatusBars); assertFalse(bars.isAppearanceLightNavigationBars) }
        rule.onNodeWithText("专注显示").performScrollTo().performClick()
        rule.runOnIdle { fixture.theme.value = MirraThemeId.MONO }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(bars.isAppearanceLightStatusBars); assertFalse(bars.isAppearanceLightNavigationBars) }
        rule.onNodeWithText("退出专注显示").performClick()
        rule.runOnIdle { assertTrue(bars.isAppearanceLightStatusBars); assertTrue(bars.isAppearanceLightNavigationBars) }
    }

    private fun focusContrast(theme: MirraThemeId) = scenario(theme = theme) { fixture ->
        rule.runOnIdle {
            fixture.container.focusSessionActions.focusStatus.value = FocusStatusUiModel(fixture.sessionId,
                "fixture-break", SessionSegmentType.BREAK, MonitoringCoverage.NONE, remainingMillis = 30_000)
            fixture.container.focusSessionActions.result = FocusActionResult.SAVE_FAILED
        }
        rule.onNodeWithText("专注显示").performScrollTo().performClick()
        rule.onNodeWithText("显示控制").performClick()
        fun assertContrast(text: String) {
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(text, useUnmergedTree = true).performScrollTo()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Expected actual rendered text layout", layouts.isNotEmpty())
            val light = layouts.single().layoutInput.style.color.luminance()
            val dark = Color(0xFF101216).luminance()
            assertTrue("Focus text '$text' must meet 4.5:1 contrast, actual ${(light + .05f) / (dark + .05f)}",
                (light + .05f) / (dark + .05f) >= 4.5f)
        }
        assertContrast("提前结束休息")
        rule.onNodeWithText("提前结束休息").performClick()
        rule.waitUntil(5_000) { fixture.vm.focusError != null }
        assertContrast("保存失败，请重试")
    }

    @Test fun focusDisplayFinishStillRequiresTheFrozenConfirmation() = scenario { fixture ->
        rule.onNodeWithText("专注显示").performScrollTo().performClick()
        rule.onNodeWithText("显示控制").performClick()
        rule.onNodeWithText("结束阅读").performClick()
        rule.onNodeWithText("结束本次阅读？").assertExists()
        rule.runOnIdle { assertFalse(fixture.completed) }
        assertNull(runBlocking { fixture.container.studyWorkflowRepository.observeSession(fixture.sessionId).first() }!!.endedAt)
        rule.onNodeWithText("继续阅读").performClick()
        rule.runOnIdle { assertFalse(fixture.completed) }
    }

    @Test fun readingActionsReachable320Normal() = layout(320, 1f)
    @Test fun readingActionsReachable320Double() = layout(320, 2f)
    @Test fun readingActionsReachable360Normal() = layout(360, 1f)
    @Test fun readingActionsReachable360Double() = layout(360, 2f)
    @Test fun readingActionsReachable411Normal() = layout(411, 1f)
    @Test fun readingActionsReachable411Double() = layout(411, 2f)

    private fun layout(width: Int, scale: Float) = scenario(width, scale) {
        rule.onNodeWithTag("session-note-action").performScrollTo().assertIsDisplayed().assertHasClickAction()
        rule.onNodeWithTag("session-end-action").performScrollTo().assertIsDisplayed().assertHasClickAction()
        val minimumHeight = with(rule.density) { 48.dp.toPx() }
        assertTrue(rule.onNodeWithTag("session-note-action").fetchSemanticsNode().boundsInRoot.height >= minimumHeight)
        rule.onNodeWithTag("session-focus-display-action").performScrollTo().assertIsDisplayed().assertHasClickAction()
    }

    private data class Fixture(
        val container: TestAppContainer,
        val sessionId: String,
        val vm: SessionViewModel,
        var view: View,
        val theme: MutableState<MirraThemeId>,
        var left: Boolean = false,
        var completed: Boolean = false,
    )

    private fun scenario(width: Int = 360, scale: Float = 1f, full: Boolean = false,
        theme: MirraThemeId = MirraThemeId.BLUE, withSnapshot: Boolean = false,
        noteRepositoryDecorator: (NoteRepository) -> NoteRepository = { it }, body: (Fixture) -> Unit) {
        val container = TestAppContainer(ApplicationProvider.getApplicationContext())
        val session = runBlocking {
            val item = container.learningItemRepository.create("安静阅读", 520, currentPage = 40,
                firstAction = "翻到第 40 页，读这一页的第一段。")
            val intent = container.studyWorkflowRepository.createIntent(item.id)
            container.studyWorkflowRepository.startSession(intent.id, 40)
        }
        if (withSnapshot) runBlocking {
            // Controlled in-memory historical rows, not an installed monitoring execution claim.
            container.database.focusDao().insertRiskSnapshots(listOf(
                SessionRiskAppSnapshotEntity(session.id, "com.example.old", "本场旧名称"),
                SessionRiskAppSnapshotEntity(session.id, "com.example.unknownlabel", ""),
            ))
            container.focusRepository.replaceRiskApp("com.example.next", "下一场名称")
        }
        container.focusSessionActions.focusStatus.value = FocusStatusUiModel(session.id, "fixture-segment",
            if (full) SessionSegmentType.FOCUS else SessionSegmentType.UNMONITORED,
            if (full) MonitoringCoverage.FULL else MonitoringCoverage.NONE)
        val vm = SessionViewModel(session.id, container.studyWorkflowRepository, noteRepositoryDecorator(container.noteRepository),
            container.sessionManager, focusActions = container.focusSessionActions, learningItems = container.learningItemRepository)
        val fixture = Fixture(container, session.id, vm, rule.activity.window.decorView, mutableStateOf(theme))
        try {
            rule.setContent {
                val density = LocalDensity.current
                val view = LocalView.current
                SideEffect { fixture.view = view }
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    MirraTheme(fixture.theme.value) { Box(Modifier.width(width.dp)) {
                        SessionScreen(vm, { fixture.completed = true }, {}, { fixture.left = true },
                            readingRecordRepository = if (withSnapshot) container.readingRecordRepository else null)
                    } }
                }
            }
            rule.waitUntil(5_000) { vm.session.value != null && vm.canEditLearning && vm.bookName.value == "安静阅读" }
            body(fixture)
        } finally {
            rule.activityRule.scenario.close()
            vm.viewModelScope.cancel()
            container.close()
        }
    }

    private fun capture(name: String) {
        if (arguments("v111Screenshots") == "true") { rule.waitForIdle(); captureReadingRecordEvidence(name) }
    }
    private fun arguments(key: String) = InstrumentationRegistry.getArguments().getString(key)

    private companion object { const val NOTE_ROTATION_FAILURE = "受控笔记保存失败，请重试" }
}
