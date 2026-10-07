package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.feature.session.*
import com.guanyi.mirra.domain.SessionFinishResult
import com.guanyi.mirra.domain.SessionManager
import com.guanyi.mirra.domain.monitoring.ClockSample
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import com.guanyi.mirra.data.repository.NoteRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SessionCloseoutUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun start(c: TestAppContainer) = runBlocking {
        val item = c.learningItemRepository.create("结束确认测试", 320, currentPage = 40)
        val intent = c.studyWorkflowRepository.createIntent(item.id)
        c.studyWorkflowRepository.startSession(intent.id, 40)
    }
    private fun vm(c: TestAppContainer, id: String) = SessionViewModel(id, c.studyWorkflowRepository,
        c.noteRepository, c.sessionManager, focusActions = c.focusSessionActions,
        learningItems = c.learningItemRepository)

    @Test fun firstClickOpensConfirmationWithoutWaitingForDraftSave() {
        val c = TestAppContainer(ApplicationProvider.getApplicationContext()); val s = start(c)
        val allowNoteSave = CompletableDeferred<Unit>()
        val notes = object : NoteRepository by c.noteRepository {
            override suspend fun save(learningItemId: String, sessionId: String?, content: String,
                pageNumber: Int?, semanticType: NoteSemanticType, id: String?): NoteEntity {
                allowNoteSave.await()
                return c.noteRepository.save(learningItemId, sessionId, content, pageNumber, semanticType, id)
            }
        }
        val vm = SessionViewModel(s.id, c.studyWorkflowRepository, notes, c.sessionManager,
            focusActions = c.focusSessionActions, learningItems = c.learningItemRepository)
        try {
            rule.setContent { MirraTheme { SessionScreen(vm, {}, {}, {}) } }
            rule.waitUntil(5_000) { vm.currentPageText == "40" }
            rule.runOnIdle { vm.changeContent("确认前的最后一笔") }
            rule.onNodeWithText("结束本次阅读").performScrollTo().performClick()
            rule.onNodeWithText("结束本次阅读？").assertExists()
            assertEquals(FocusCloseoutState.ACTIVE, runBlocking { c.studyWorkflowRepository.getCloseoutState(s.id) })
            assertTrue(runBlocking { c.noteRepository.observeForSession(s.id).first() }.isEmpty())
            assertEquals("确认前的最后一笔", vm.draftContent)
            rule.onNodeWithText("继续阅读").performClick()
            rule.onNodeWithText("结束本次阅读？").assertDoesNotExist()
            assertNull(runBlocking { c.database.sessionDao().get(s.id) }!!.endedAt)
        } finally {
            allowNoteSave.complete(Unit)
            rule.activityRule.scenario.close(); vm.viewModelScope.cancel(); c.close()
        }
    }
    @Test fun finalNoteFailureKeepsTemporaryEndPageAndRetrySamplesAfterFlush() {
        val c = TestAppContainer(ApplicationProvider.getApplicationContext()); val s = start(c)
        val originalSegments = runBlocking { c.database.focusDao().listSegments(s.id) }
        val retryNoteStarted = CompletableDeferred<Unit>(); val allowRetryNoteSave = CompletableDeferred<Unit>()
        var failNote = true
        var sampleWallNow = s.startedAt + 2_000
        val finishSamples = mutableListOf<ClockSample>()
        val notes = object : NoteRepository by c.noteRepository {
            override suspend fun save(learningItemId: String, sessionId: String?, content: String,
                pageNumber: Int?, semanticType: NoteSemanticType, id: String?): NoteEntity {
                check(!failNote) { "笔记保存失败，请重试" }
                retryNoteStarted.complete(Unit)
                allowRetryNoteSave.await()
                return c.noteRepository.save(learningItemId, sessionId, content, pageNumber, semanticType, id)
            }
        }
        val manager = object : SessionManager by c.sessionManager {
            override suspend fun finish(sessionId: String, endPage: Int, sample: ClockSample): SessionFinishResult {
                val saved = c.noteRepository.observeForSession(sessionId).first().single()
                assertEquals("结束前保留的最后一笔", saved.content)
                finishSamples += sample
                return c.sessionManager.finish(sessionId, endPage, sample)
            }
        }
        val vm = SessionViewModel(s.id, c.studyWorkflowRepository, notes, manager,
            focusActions = c.focusSessionActions, learningItems = c.learningItemRepository,
            clockSample = { ClockSample(sampleWallNow, 8_000) })
        var completed = false
        try {
            rule.setContent { MirraTheme { SessionScreen(vm, { completed = true }, {}, {}) } }
            rule.waitUntil(5_000) { vm.currentPageText == "40" }
            rule.runOnIdle {
                vm.changeContent("结束前保留的最后一笔")
                vm.requestFinishConfirmation()
            }
            rule.onNodeWithText("结束本次阅读？").assertExists()
            rule.onNode(hasSetTextAction() and hasText("结束页码")).performTextReplacement("45")
            rule.onNodeWithTag("confirm-session-finish").performClick()
            rule.waitUntil(5_000) { vm.finishUi.value == SessionFinishUiState.Confirming("45") && vm.error != null }
            rule.onNodeWithText("笔记保存失败，请重试").assertExists()
            rule.onNode(hasSetTextAction() and hasText("结束页码")).assertTextContains("45")
            rule.runOnIdle { assertFalse(completed); assertEquals("结束前保留的最后一笔", vm.draftContent) }
            val active = runBlocking { c.database.sessionDao().get(s.id) }!!
            val context = runBlocking { c.database.focusDao().getContext(s.id) }!!
            assertEquals(40, active.currentPage); assertNull(active.endedAt)
            assertEquals(40, runBlocking { c.database.learningItemDao().get(s.learningItemId) }!!.currentPage)
            assertEquals(FocusCloseoutState.ACTIVE, context.closeoutState)
            assertNull(context.closeoutStartedAt); assertNull(context.requestedEndPage)
            assertEquals(originalSegments, runBlocking { c.database.focusDao().listSegments(s.id) })
            assertTrue(finishSamples.isEmpty())
            assertTrue(runBlocking { c.noteRepository.observeForSession(s.id).first() }.isEmpty())

            rule.runOnIdle { failNote = false }
            rule.onNodeWithTag("confirm-session-finish").performClick()
            rule.waitUntil(5_000) { retryNoteStarted.isCompleted }
            rule.onNodeWithText("正在保存本次阅读…").assertExists()
            rule.onNodeWithText("阅读已结束").assertDoesNotExist()
            rule.runOnIdle {
                assertEquals(SessionFinishUiState.Saving, vm.finishUi.value)
                assertFalse(completed); assertTrue(finishSamples.isEmpty())
                sampleWallNow = s.startedAt + 8_000
            }
            assertEquals(FocusCloseoutState.ACTIVE, runBlocking { c.studyWorkflowRepository.getCloseoutState(s.id) })
            assertEquals(originalSegments, runBlocking { c.database.focusDao().listSegments(s.id) })
            allowRetryNoteSave.complete(Unit)
            rule.waitUntil(5_000) { completed }
            val ended = runBlocking { c.database.sessionDao().get(s.id) }!!
            val closed = runBlocking { c.database.focusDao().getContext(s.id) }!!
            val retainedNote = runBlocking { c.noteRepository.observeForSession(s.id).first() }.single()
            assertEquals(listOf(ClockSample(s.startedAt + 8_000, 8_000)), finishSamples)
            assertEquals(s.startedAt + 8_000, ended.endedAt)
            assertEquals(ended.endedAt, closed.closeoutStartedAt)
            assertEquals(FocusCloseoutState.COMPLETED, closed.closeoutState)
            assertEquals(SessionEndType.NORMAL, ended.endType); assertEquals(45, ended.endPage)
            assertEquals(45, runBlocking { c.database.learningItemDao().get(s.learningItemId) }!!.currentPage)
            assertEquals("结束前保留的最后一笔", retainedNote.content); assertEquals(40, retainedNote.pageNumber)
            assertEquals(ended.endedAt, runBlocking { c.database.focusDao().listSegments(s.id) }.single().endedAt)
        } finally {
            allowRetryNoteSave.complete(Unit)
            rule.activityRule.scenario.close(); vm.viewModelScope.cancel(); c.close()
        }
    }
    @Test fun lowerEndPageIsExplicitlyRejectedThenNormalSummaryKeepsMonotonicProgress() {
        val c = TestAppContainer(ApplicationProvider.getApplicationContext()); val s = start(c); val vm = vm(c, s.id)
        var completed = false
        try {
            rule.setContent { MirraTheme { SessionScreen(vm, { completed = true }, {}, {}) } }
            rule.waitUntil(5_000) { vm.currentPageText == "40" }
            rule.onNodeWithText("结束本次阅读").performScrollTo().performClick()
            rule.onNode(hasSetTextAction() and hasText("结束页码")).performTextReplacement("35")
            rule.onNodeWithTag("confirm-session-finish").performClick()
            rule.onNodeWithText("不能低于已经记录的阅读位置").assertExists()
            assertNull(runBlocking { c.database.sessionDao().get(s.id) }!!.endedAt)
            rule.onNode(hasSetTextAction() and hasText("结束页码")).performTextReplacement("321")
            rule.onNodeWithTag("confirm-session-finish").performClick()
            rule.waitUntil(5_000) {
                vm.error == "结束页必须在书籍范围内" &&
                    vm.finishUi.value is SessionFinishUiState.Confirming &&
                    rule.onAllNodes(hasText("结束页必须在书籍范围内")).fetchSemanticsNodes().size == 1
            }
            rule.onNodeWithText("结束页必须在书籍范围内").assertExists()
            assertEquals(FocusCloseoutState.ACTIVE, runBlocking { c.studyWorkflowRepository.getCloseoutState(s.id) })
            rule.onNode(hasSetTextAction() and hasText("结束页码")).performTextReplacement("42")
            rule.onNodeWithTag("confirm-session-finish").performClick()
            rule.waitUntil(5_000) { completed }
            val ended = runBlocking { c.database.sessionDao().get(s.id) }!!
            assertEquals(SessionEndType.NORMAL, ended.endType); assertEquals(42, ended.endPage)
            assertEquals(42, runBlocking { c.database.learningItemDao().get(s.learningItemId) }!!.currentPage)
        } finally { rule.activityRule.scenario.close(); vm.viewModelScope.cancel(); c.close() }
    }
    @Test fun savingBeforeDurablePendingDoesNotClaimReadingEnded() {
        val c = TestAppContainer(ApplicationProvider.getApplicationContext()); val s = start(c)
        val beforeBegin = CompletableDeferred<Unit>(); val allowBegin = CompletableDeferred<Unit>()
        val manager = object : SessionManager by c.sessionManager {
            override suspend fun finish(sessionId: String, endPage: Int, sample: ClockSample): SessionFinishResult {
                beforeBegin.complete(Unit)
                allowBegin.await()
                return c.sessionManager.finish(sessionId, endPage, sample)
            }
        }
        val vm = SessionViewModel(s.id, c.studyWorkflowRepository, c.noteRepository, manager,
            focusActions = c.focusSessionActions)
        var completed = false
        try {
            rule.setContent { MirraTheme { SessionScreen(vm, { completed = true }, {}, {}) } }
            rule.waitUntil(5_000) { vm.currentPageText == "40" }
            rule.onNodeWithText("结束本次阅读").performScrollTo().performClick()
            rule.onNodeWithTag("confirm-session-finish").performClick()
            rule.waitUntil(5_000) { beforeBegin.isCompleted }
            assertEquals(FocusCloseoutState.ACTIVE, runBlocking { c.studyWorkflowRepository.getCloseoutState(s.id) })
            assertNull(runBlocking { c.database.sessionDao().get(s.id) }!!.endedAt)
            rule.onNodeWithText("正在保存本次阅读…").assertExists()
            rule.onNodeWithText("阅读已结束").assertDoesNotExist()
            rule.onNodeWithText("快速笔记").assertDoesNotExist()
            rule.runOnIdle { assertFalse(completed) }
            allowBegin.complete(Unit)
            rule.waitUntil(5_000) { completed }
            assertEquals(FocusCloseoutState.COMPLETED, runBlocking { c.studyWorkflowRepository.getCloseoutState(s.id) })
            assertEquals(SessionEndType.NORMAL, runBlocking { c.database.sessionDao().get(s.id) }!!.endType)
        } finally {
            allowBegin.complete(Unit)
            rule.activityRule.scenario.close(); vm.viewModelScope.cancel(); c.close()
        }
    }
    @Test fun recreatedPendingBackCannotResumeAndRetryUsesFrozenDecision() {
        val c = TestAppContainer(ApplicationProvider.getApplicationContext()); val s = start(c)
        val snapshot = runBlocking { c.studyWorkflowRepository.beginCloseout(s.id, 42, s.startedAt + 2_000) }
        val vm = vm(c, s.id); var left = false; var completed = false
        try {
            rule.setContent { MirraTheme { SessionScreen(vm, { completed = true }, {}, { left = true }) } }
            rule.waitUntil(5_000) {
                rule.onAllNodes(hasText("阅读已结束")).fetchSemanticsNodes().size == 1 &&
                    rule.onAllNodes(hasText("重试保存")).fetchSemanticsNodes().size == 1
            }
            rule.onNodeWithText("阅读已结束").assertExists()
            rule.onNodeWithText("重试保存").assertExists()
            rule.onNodeWithText("快速笔记").assertDoesNotExist()
            rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.runOnIdle { assertFalse(left) }
            assertEquals(snapshot, vm.closeoutSnapshot)
            rule.onNodeWithText("重试保存").performClick()
            rule.waitUntil(5_000) { completed }
            assertEquals(snapshot.closeoutStartedAt, runBlocking { c.database.sessionDao().get(s.id) }!!.endedAt)
        } finally { rule.activityRule.scenario.close(); vm.viewModelScope.cancel(); c.close() }
    }
    @Test fun occupiedPendingOverridesRestoredKnowledgeWithoutResumingSession() {
        val c = TestAppContainer(ApplicationProvider.getApplicationContext()); val s = start(c)
        runBlocking { c.studyWorkflowRepository.beginCloseout(s.id, 42, s.startedAt + 2_000) }
        var restored by mutableStateOf(TopLevelDestination.Start)
        try {
            rule.setContent { MirraTheme { MirraApp(c, restored, {}) } }
            rule.waitUntil(5_000) { rule.onAllNodes(hasText("重试保存")).fetchSemanticsNodes().isNotEmpty() }
            rule.runOnIdle { restored = TopLevelDestination.Knowledge }
            rule.onNodeWithText("阅读已结束").assertExists()
            rule.onNodeWithText("重试保存").assertExists()
            rule.onNodeWithText("快速笔记").assertDoesNotExist()
            assertEquals(FocusCloseoutState.PENDING, runBlocking { c.studyWorkflowRepository.getCloseoutState(s.id) })
        } finally { rule.activityRule.scenario.close(); c.close() }
    }
    @Test fun lateInitialCloseoutQualificationStillSeedsFirstNotePage() {
        val c = TestAppContainer(ApplicationProvider.getApplicationContext()); val s = start(c)
        val delayed = object : StudyWorkflowRepository by c.studyWorkflowRepository {
            override fun observeCloseoutState(sessionId: String) = flow {
                delay(100); emit(FocusCloseoutState.ACTIVE)
            }
        }
        val vm = SessionViewModel(s.id, delayed, c.noteRepository, c.sessionManager,
            focusActions = c.focusSessionActions, learningItems = c.learningItemRepository)
        var completed = false
        try {
            rule.setContent { MirraTheme { SessionScreen(vm, { completed = true }, {}, {}) } }
            rule.waitUntil(5_000) { vm.currentPageText == "40" && vm.draftPage == "40" }
            rule.runOnIdle { vm.changeContent("首条笔记继承当前页") }
            rule.onNodeWithText("结束本次阅读").performScrollTo().performClick()
            rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("confirm-session-finish")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("confirm-session-finish").performClick()
            rule.waitUntil(5_000) { completed }
            assertEquals(SessionEndType.NORMAL, runBlocking { c.database.sessionDao().get(s.id) }!!.endType)
            assertEquals(40, runBlocking { c.noteRepository.observeForSession(s.id).first() }.single().pageNumber)
        } finally { rule.activityRule.scenario.close(); vm.viewModelScope.cancel(); c.close() }
    }
}
