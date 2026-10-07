package com.guanyi.mirra.feature.session

import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.local.model.CloseoutSnapshot
import com.guanyi.mirra.data.repository.*
import com.guanyi.mirra.domain.*
import com.guanyi.mirra.domain.monitoring.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCloseoutViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<SessionViewModel>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private fun closeoutTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { models.forEach { it.viewModelScope.cancel() } }
    }
    private inline fun <reified T> unsupported(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${method.name}") } as T

    private inner class Fixture(initial: FocusCloseoutState = FocusCloseoutState.ACTIVE) {
        val state = MutableStateFlow<FocusCloseoutState?>(initial)
        var closeoutFlow: Flow<FocusCloseoutState?> = state
        val current = MutableStateFlow<StudySessionEntity?>(StudySessionEntity("s", "book", "intent", 1_000,
            null, null, 40, 40, null, null, null, 1))
        var snapshot: CloseoutSnapshot? = if (initial == FocusCloseoutState.PENDING) CloseoutSnapshot("s", 2_000, 42) else null
        var snapshotFailure = false
        var noteBarrier: CompletableDeferred<Unit>? = null
        var pageBarrier: CompletableDeferred<Unit>? = null
        var finishBarrier: CompletableDeferred<Unit>? = null
        var refreshBarrier: CompletableDeferred<Unit>? = null
        var failNote = false; var failPage = false; var failBegin = false; var failComplete = false
        var cancelNote = false
        var clockCalls = 0; var finishes = 0; var retries = 0; var evidenceCalls = 0; var actions = 0
        var sample = ClockSample(2_000, 777)
        val saved = mutableListOf<NoteEntity>()
        val pageAttempts = mutableListOf<Int>()
        val boundaries = mutableListOf<ClockSample>()
        val workflow = object : StudyWorkflowRepository by unsupported() {
            override fun observeSession(id: String) = current
            override fun observeCloseoutState(sessionId: String) = closeoutFlow
            override suspend fun getCloseoutState(sessionId: String) = state.value
            override suspend fun getCloseoutSnapshot(sessionId: String): CloseoutSnapshot? {
                if (snapshotFailure) error("snapshot corrupt")
                return snapshot
            }
        }
        val notes = object : NoteRepository by unsupported() {
            override fun observeForSession(sessionId: String) = flowOf(emptyList<NoteEntity>())
            override suspend fun save(learningItemId: String, sessionId: String?, content: String,
                pageNumber: Int?, semanticType: NoteSemanticType, id: String?): NoteEntity {
                noteBarrier?.await()
                if (cancelNote) throw CancellationException("save interrupted")
                if (failNote) error("note failed")
                return NoteEntity(id!!, learningItemId, sessionId, semanticType, content, pageNumber, 1_000, 1_000)
                    .also { saved += it }
            }
        }
        val manager = object : SessionManager by unsupported() {
            override suspend fun updatePage(sessionId: String, page: Int) {
                pageAttempts += page
                pageBarrier?.await()
                if (failPage) error("page failed")
                current.value = current.value!!.copy(currentPage = maxOf(page, current.value!!.currentPage))
            }
            override suspend fun finish(sessionId: String, endPage: Int, sample: ClockSample): SessionFinishResult {
                finishes++; boundaries += sample
                if (failBegin) error("A failed")
                snapshot = CloseoutSnapshot(sessionId, sample.wallNowMillis, endPage)
                state.value = FocusCloseoutState.PENDING
                finishBarrier?.await()
                return complete()
            }
            override suspend fun retryPendingFinish(sessionId: String): SessionFinishResult { retries++; return complete() }
            private fun complete(): SessionFinishResult {
                if (failComplete) return SessionFinishResult.PendingRetry("s")
                val ended = current.value!!.copy(endedAt = snapshot!!.closeoutStartedAt,
                    endPage = snapshot!!.requestedEndPage, endType = SessionEndType.NORMAL, activeSlot = null)
                current.value = ended; state.value = FocusCloseoutState.COMPLETED
                return SessionFinishResult.Completed(ended)
            }
        }
        private val baseFocus: FocusSessionActions = Proxy.newProxyInstance(FocusSessionActions::class.java.classLoader,
            arrayOf(FocusSessionActions::class.java)) { _, method, _ -> when (method.name) {
                "getFocusStatus" -> MutableStateFlow(FocusStatusUiModel("s", "seg", SessionSegmentType.FOCUS, MonitoringCoverage.FULL))
                "getIntervention" -> MutableStateFlow<InterventionUiModel?>(null)
                "refresh" -> { evidenceCalls++; Unit }
                "observeEvidence" -> { evidenceCalls++; FocusActionResult.SUCCESS }
                else -> { actions++; FocusActionResult.SUCCESS }
            } } as FocusSessionActions
        val focus = object : FocusSessionActions by baseFocus {
            override suspend fun refresh(sessionId: String, sample: ClockSample) {
                evidenceCalls++; refreshBarrier?.await()
            }
        }
        fun vm() = SessionViewModel("s", workflow, notes, manager, focusActions = focus,
            clockSample = { clockCalls++; sample }).also { models += it }
    }

    @Test fun confirmationOpensBeforeSavesButFinalConfirmationWaitsForDraftAndPageWrites() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        f.noteBarrier = CompletableDeferred(); f.pageBarrier = CompletableDeferred()
        vm.changeContent("latest note"); advanceTimeBy(501); runCurrent()
        vm.updatePage("42"); runCurrent(); vm.requestFinishConfirmation(); runCurrent()
        assertEquals(SessionFinishUiState.Confirming("40"), vm.finishUi.value)
        assertEquals(0, f.clockCalls); assertEquals(0, f.finishes)
        vm.changeEndPage("42"); vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Saving, vm.finishUi.value)
        f.noteBarrier!!.complete(Unit); runCurrent()
        assertEquals(SessionFinishUiState.Saving, vm.finishUi.value)
        assertEquals(0, f.clockCalls); assertEquals(0, f.finishes)
        f.sample = ClockSample(9_000, 8_000)
        f.pageBarrier!!.complete(Unit); runCurrent()
        assertEquals(SessionFinishUiState.Completed("s"), vm.finishUi.value)
        assertEquals(listOf(ClockSample(9_000, 8_000)), f.boundaries)
    }
    @Test fun latestTextFlushesUnderSameDraftIdWithoutCancellingStartedAutosave() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent()
        f.noteBarrier = CompletableDeferred(); vm.changeContent("old"); advanceTimeBy(501); runCurrent()
        vm.changeContent("latest"); vm.requestFinishConfirmation(); runCurrent()
        assertEquals(SessionFinishUiState.Confirming("40"), vm.finishUi.value)
        vm.confirmFinish {}; runCurrent()
        f.noteBarrier!!.complete(Unit); runCurrent()
        assertEquals("latest", f.saved.last().content)
        assertEquals(1, f.saved.map { it.id }.distinct().size)
        assertTrue(f.saved.any { it.content == "old" })
        assertEquals(SessionFinishUiState.Completed("s"), vm.finishUi.value)
    }
    @Test fun openingConfirmationDoesNotDropNormalDebouncedDraftSave() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent()
        vm.changeContent("draft before confirmation")
        vm.requestFinishConfirmation(); runCurrent()
        assertEquals(SessionFinishUiState.Confirming("40"), vm.finishUi.value)
        assertFalse(vm.canEditLearning)
        vm.changeContent("must not edit behind confirmation")
        advanceTimeBy(501); runCurrent()
        assertEquals("draft before confirmation", f.saved.single().content)
        assertEquals(FocusCloseoutState.ACTIVE, f.state.value)
        assertNull(f.snapshot); assertEquals(0, f.finishes); assertEquals(0, f.clockCalls)
        vm.cancelFinishConfirmation(); runCurrent()
        assertEquals(SessionFinishUiState.Idle, vm.finishUi.value)
        assertEquals("draft before confirmation", vm.draftContent)
    }
    @Test fun lifecycleFlushSavesDraftWhileConfirmationRemainsOpen() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent()
        vm.changeContent("background draft")
        vm.requestFinishConfirmation(); runCurrent()
        vm.changeEndPage("45")
        vm.flushDraft(); runCurrent()
        assertEquals("background draft", f.saved.single().content)
        assertEquals(SessionFinishUiState.Confirming("45"), vm.finishUi.value)
        assertEquals(FocusCloseoutState.ACTIVE, f.state.value)
        assertEquals(40, f.current.value!!.currentPage)
        assertNull(f.snapshot); assertEquals(0, f.finishes); assertEquals(0, f.clockCalls)
        advanceTimeBy(501); runCurrent()
        assertEquals(1, f.saved.size)
    }
    @Test fun draftFlushFailureKeepsSessionActive() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); f.failNote = true
        vm.changeContent("unsaved"); vm.requestFinishConfirmation(); runCurrent()
        assertEquals(SessionFinishUiState.Confirming("40"), vm.finishUi.value)
        assertNull(vm.error); assertTrue(f.saved.isEmpty())
        vm.changeEndPage("45"); vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Confirming("45"), vm.finishUi.value)
        assertNotNull(vm.error); assertEquals(FocusCloseoutState.ACTIVE, f.state.value)
        assertEquals(40, f.current.value!!.currentPage); assertNull(f.current.value!!.endedAt)
        assertNull(f.snapshot); assertTrue(f.pageAttempts.isEmpty()); assertEquals("unsaved", vm.draftContent)
        assertEquals(0, f.clockCalls); assertEquals(0, f.finishes)
        f.failNote = false; vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Completed("s"), vm.finishUi.value)
        assertEquals(45, f.snapshot!!.requestedEndPage); assertEquals(1, f.finishes)
    }
    @Test fun failedPageWriteCannotBeMistakenForSuccessfulJobCompletion() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40); f.failPage = true
        vm.updatePage("42"); runCurrent(); vm.requestFinishConfirmation(); runCurrent()
        assertEquals(SessionFinishUiState.Confirming("40"), vm.finishUi.value)
        vm.changeEndPage("42"); vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Confirming("42"), vm.finishUi.value)
        assertNotNull(vm.error); assertEquals(0, f.clockCalls)
    }
    @Test fun noteFailureDoesNotRetryUnpersistedCurrentPageDuringFinalConfirmation() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        f.failPage = true; vm.updatePage("42"); runCurrent()
        vm.changeContent("unsaved final draft"); vm.requestFinishConfirmation(); runCurrent()
        vm.changeEndPage("45"); vm.confirmFinish {}; runCurrent() // Surface the prior failed write.
        f.failPage = false; f.failNote = true
        vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Confirming("45"), vm.finishUi.value)
        assertEquals(listOf(42), f.pageAttempts)
        assertEquals(40, f.current.value!!.currentPage)
        assertEquals(FocusCloseoutState.ACTIVE, f.state.value)
        assertEquals(0, f.finishes); assertEquals(0, f.clockCalls)
    }
    @Test fun finalConfirmationRevalidatesProgressThatAdvancedDuringDraftFlush() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        f.noteBarrier = CompletableDeferred()
        vm.changeContent("last note"); vm.requestFinishConfirmation(); runCurrent()
        vm.changeEndPage("42"); vm.confirmFinish {}; runCurrent()
        f.current.value = f.current.value!!.copy(currentPage = 44)
        f.noteBarrier!!.complete(Unit); runCurrent()
        assertEquals(SessionFinishUiState.Confirming("42"), vm.finishUi.value)
        assertEquals("不能低于已经记录的阅读位置", vm.error)
        assertEquals(FocusCloseoutState.ACTIVE, f.state.value)
        assertEquals(0, f.clockCalls); assertEquals(0, f.finishes)
    }
    @Test fun transientPageWriteFailureCanRetryFinishWithoutEditingPageAgain() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        vm.changePage("35"); vm.changeContent("old page note"); advanceTimeBy(501); runCurrent()
        val draftId = f.saved.single().id
        f.failPage = true; vm.updatePage("42"); runCurrent()
        vm.requestFinishConfirmation(); runCurrent()
        vm.changeEndPage("42"); vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Confirming("42"), vm.finishUi.value)
        assertEquals(40, f.current.value!!.currentPage); assertEquals("42", vm.currentPageText)
        assertNotNull(vm.error); assertEquals(0, f.finishes); assertEquals(0, f.clockCalls)

        f.failPage = false; f.pageBarrier = CompletableDeferred()
        vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Saving, vm.finishUi.value)
        assertEquals(40, f.current.value!!.currentPage)
        f.pageBarrier!!.complete(Unit); runCurrent()
        assertEquals(SessionFinishUiState.Completed("s"), vm.finishUi.value)
        assertEquals(42, f.current.value!!.currentPage); assertNull(vm.error)
        assertEquals(listOf(42, 42), f.pageAttempts)
        assertEquals(setOf(draftId), f.saved.map { it.id }.toSet())
        assertTrue(f.saved.all { it.pageNumber == 35 && it.content == "old page note" })
        assertEquals(1, f.finishes); assertEquals(1, f.clockCalls)
    }
    @Test fun repeatedPageWriteFailureStillBlocksFinalConfirmation() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        f.failPage = true; vm.updatePage("42"); runCurrent()
        vm.requestFinishConfirmation(); runCurrent()
        vm.changeEndPage("42"); vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Confirming("42"), vm.finishUi.value)
        vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Confirming("42"), vm.finishUi.value)
        assertEquals(listOf(42, 42), f.pageAttempts)
        assertEquals(40, f.current.value!!.currentPage); assertEquals("42", vm.currentPageText)
        assertNotNull(vm.error); assertEquals(FocusCloseoutState.ACTIVE, f.state.value)
        assertEquals(0, f.finishes); assertEquals(0, f.clockCalls)
    }
    @Test fun durablePageAlreadyMatchesInputClearsObsoletePageWriteFailure() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        f.failPage = true; vm.updatePage("42"); runCurrent()
        vm.requestFinishConfirmation(); runCurrent()
        vm.changeEndPage("42"); vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Confirming("42"), vm.finishUi.value)
        f.current.value = f.current.value!!.copy(currentPage = 42); runCurrent()
        vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Completed("s"), vm.finishUi.value)
        assertNull(vm.error); assertEquals(listOf(42), f.pageAttempts)
        assertEquals(1, f.finishes); assertEquals(1, f.clockCalls)
    }
    @Test fun temporarySmallerPageInputClearsObsoleteFailureWithoutRegressingProgress() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        f.failPage = true; vm.updatePage("42"); runCurrent(); vm.updatePage("4")
        vm.requestFinishConfirmation(); runCurrent()
        vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Confirming("40"), vm.finishUi.value)
        assertNotNull(vm.error); assertEquals(40, f.current.value!!.currentPage)
        vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Completed("s"), vm.finishUi.value)
        assertEquals(40, f.current.value!!.currentPage); assertEquals("4", vm.currentPageText)
        assertNull(vm.error); assertEquals(listOf(42), f.pageAttempts)
        assertEquals(1, f.finishes); assertEquals(1, f.clockCalls)
    }
    @Test fun invalidPageInputCannotHideRetainedPageWriteFailure() = closeoutTest {
        for (input in listOf("", "0", "2147483648")) {
            val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
            f.failPage = true; vm.updatePage("42"); runCurrent(); vm.updatePage(input)
            vm.requestFinishConfirmation(); runCurrent(); vm.confirmFinish {}; runCurrent(); vm.confirmFinish {}; runCurrent()
            assertEquals(SessionFinishUiState.Confirming("40"), vm.finishUi.value)
            assertNotNull(vm.error); assertEquals(40, f.current.value!!.currentPage)
            assertEquals(listOf(42), f.pageAttempts); assertEquals(FocusCloseoutState.ACTIVE, f.state.value)
            assertEquals(0, f.finishes); assertEquals(0, f.clockCalls)
        }
    }
    @Test fun partialPageInputDuringFinishKeepsDurablePageAndOldNote() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        vm.updatePage("4"); runCurrent()
        assertEquals("4", vm.currentPageText); assertEquals(40, f.current.value!!.currentPage)
        vm.updatePage("42"); runCurrent()
        vm.changePage("35"); vm.changeContent("old page note")
        vm.updatePage("4"); vm.requestFinishConfirmation(); runCurrent()
        assertEquals(SessionFinishUiState.Confirming("42"), vm.finishUi.value)
        assertEquals(42, f.current.value!!.currentPage); assertEquals(listOf(42), f.pageAttempts)
        assertTrue(f.saved.isEmpty())
        vm.confirmFinish {}; runCurrent()
        assertEquals(35, f.saved.single().pageNumber)
        assertEquals(1, f.finishes); assertEquals(1, f.clockCalls)
    }
    @Test fun continueReadingDoesNotCaptureEndSampleOrFinishAndReopensEditing() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        vm.requestFinishConfirmation(); runCurrent(); vm.changeContent("blocked")
        assertEquals("", vm.draftContent)
        vm.cancelFinishConfirmation(); vm.changeContent("allowed")
        assertEquals("allowed", vm.draftContent); assertEquals(0, f.clockCalls); assertEquals(0, f.finishes)
    }
    @Test fun finalConfirmationCapturesExactlyOneClockSampleAndRejectsDoubleTap() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        vm.requestFinishConfirmation(); runCurrent(); vm.changeEndPage("42")
        vm.confirmFinish {}; vm.confirmFinish {}; runCurrent()
        assertEquals(1, f.clockCalls); assertEquals(1, f.finishes)
        assertEquals(ClockSample(2_000, 777), f.boundaries.single())
        assertEquals(SessionFinishUiState.Completed("s"), vm.finishUi.value)
    }
    @Test fun lowerEndPageShowsExplicitErrorWithoutCallingFinish() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        vm.requestFinishConfirmation(); runCurrent(); vm.changeEndPage("35"); vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Confirming("35"), vm.finishUi.value)
        assertEquals("不能低于已经记录的阅读位置", vm.error)
        assertEquals(0, f.finishes); assertEquals(0, f.clockCalls)
    }
    @Test fun pendingDuringCompleteRemainsSavingAndDoesNotAllowParallelRetry() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        f.finishBarrier = CompletableDeferred(); vm.requestFinishConfirmation(); runCurrent()
        vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Saving, vm.finishUi.value)
        vm.retryFinish {}; runCurrent(); assertEquals(0, f.retries)
        f.finishBarrier!!.complete(Unit); runCurrent()
        assertEquals(SessionFinishUiState.Completed("s"), vm.finishUi.value)
    }
    @Test fun recreatedPendingOnlyOffersRetryAndUsesFrozenBoundaryAndPage() = closeoutTest {
        val f = Fixture(FocusCloseoutState.PENDING); val vm = f.vm(); runCurrent()
        assertTrue((vm.finishUi.value as SessionFinishUiState.SaveFailed).logicallyClosed)
        assertEquals(CloseoutSnapshot("s", 2_000, 42), vm.closeoutSnapshot)
        advanceTimeBy(10_000); runCurrent(); assertEquals(2_000L, vm.closeoutSnapshot!!.closeoutStartedAt)
        var navigated = false
        vm.changeContent("no"); vm.updatePage("70"); vm.flushDraft(); vm.leave { navigated = true }
        vm.startBreak(5); vm.reportEvidence(false, true); vm.requestFinishConfirmation(); runCurrent()
        assertFalse(navigated); assertEquals("", vm.draftContent)
        assertTrue(f.saved.isEmpty()); assertEquals(0, f.actions); assertEquals(0, f.evidenceCalls)
        vm.retryFinish {}; runCurrent()
        assertEquals(1, f.retries); assertEquals(0, f.clockCalls); assertEquals(0, f.finishes)
        assertEquals(2_000L, f.current.value!!.endedAt)
    }
    @Test fun corruptPendingSnapshotNeverFallsBackToCurrentTimeOrActiveReading() = closeoutTest {
        val f = Fixture(FocusCloseoutState.PENDING); f.snapshotFailure = true
        val vm = f.vm(); runCurrent()
        assertTrue((vm.finishUi.value as SessionFinishUiState.SaveFailed).logicallyClosed)
        assertNull(vm.closeoutSnapshot); assertEquals(0, f.clockCalls)
        vm.changeContent("blocked"); assertEquals("", vm.draftContent)
    }
    @Test fun failedCompleteRetriesOnlyOriginalSnapshotWithoutResampling() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        f.failComplete = true; vm.requestFinishConfirmation(); runCurrent(); vm.confirmFinish {}; runCurrent()
        assertTrue((vm.finishUi.value as SessionFinishUiState.SaveFailed).logicallyClosed)
        val frozen = f.snapshot; vm.cancelFinishConfirmation(); runCurrent()
        assertTrue(vm.finishUi.value is SessionFinishUiState.SaveFailed)
        f.failComplete = false; vm.retryFinish {}; runCurrent()
        assertEquals(frozen, f.snapshot); assertEquals(1, f.clockCalls); assertEquals(1, f.finishes); assertEquals(1, f.retries)
    }
    @Test fun failedBeginRetainsConfirmationAndAllowsContinuing() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        f.failBegin = true; vm.requestFinishConfirmation(); runCurrent(); vm.confirmFinish {}; runCurrent()
        assertTrue(vm.finishUi.value is SessionFinishUiState.Confirming)
        assertEquals(FocusCloseoutState.ACTIVE, f.state.value)
        vm.cancelFinishConfirmation(); assertEquals(SessionFinishUiState.Idle, vm.finishUi.value)
    }
    @Test fun repeatedActiveHeartbeatCannotUnlockEditingDuringDraftFlush() = closeoutTest {
        val f = Fixture(); val events = MutableSharedFlow<FocusCloseoutState?>(replay = 1)
        events.tryEmit(FocusCloseoutState.ACTIVE); f.closeoutFlow = events
        val vm = f.vm(); runCurrent(); f.noteBarrier = CompletableDeferred()
        vm.changeContent("last draft"); vm.requestFinishConfirmation(); runCurrent()
        vm.confirmFinish {}; runCurrent()
        events.emit(FocusCloseoutState.ACTIVE); runCurrent()
        assertEquals(SessionFinishUiState.Saving, vm.finishUi.value)
        vm.changeContent("must not replace"); vm.requestFinishConfirmation(); runCurrent()
        assertEquals("last draft", vm.draftContent); assertEquals(0, f.finishes)
        f.noteBarrier!!.complete(Unit); runCurrent()
        assertEquals(SessionFinishUiState.Completed("s"), vm.finishUi.value)
    }
    @Test fun nextDraftRotationCompletesBeforeCloseoutAndCompletionNavigatesOnlyOnce() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        f.noteBarrier = CompletableDeferred(); vm.changeContent("first draft"); vm.saveAndContinue(); runCurrent()
        vm.requestFinishConfirmation(); runCurrent()
        assertEquals(SessionFinishUiState.Confirming("40"), vm.finishUi.value)
        var completions = 0
        vm.confirmFinish { completions++ }; runCurrent()
        assertEquals(SessionFinishUiState.Saving, vm.finishUi.value)
        f.noteBarrier!!.complete(Unit); runCurrent()
        assertEquals("", vm.draftContent); assertEquals(1, f.saved.size)
        vm.dispatchCompletion { completions++ }; vm.retryFinish { completions++ }
        vm.flushDraft(); vm.refreshFocusState(); runCurrent()
        assertEquals(1, completions); assertEquals(1, f.clockCalls); assertEquals(0, f.evidenceCalls)
    }
    @Test fun pendingFlowReadFailureCannotRevokeKnownLogicalClosure() = closeoutTest {
        val f = Fixture(FocusCloseoutState.PENDING)
        f.closeoutFlow = flow { emit(FocusCloseoutState.PENDING); yield(); error("read failed") }
        val vm = f.vm(); runCurrent()
        assertTrue((vm.finishUi.value as SessionFinishUiState.SaveFailed).logicallyClosed)
        assertEquals(CloseoutSnapshot("s", 2_000, 42), vm.closeoutSnapshot)
        vm.changeContent("blocked"); vm.reportEvidence(false, true); runCurrent()
        assertEquals("", vm.draftContent); assertEquals(0, f.evidenceCalls)
    }
    @Test fun failedPageCanBeCorrectedBeforeNewConfirmationAttempt() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); vm.syncCurrentPage(40)
        f.failPage = true; vm.updatePage("42"); runCurrent(); vm.requestFinishConfirmation(); runCurrent()
        vm.changeEndPage("42"); vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Confirming("42"), vm.finishUi.value)
        vm.cancelFinishConfirmation()
        f.failPage = false; vm.updatePage("43"); runCurrent(); vm.requestFinishConfirmation(); runCurrent()
        assertEquals(SessionFinishUiState.Confirming("43"), vm.finishUi.value)
    }
    @Test fun cancelledChildSaveDoesNotStrandActiveConfirmationAndCanRetry() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent(); f.cancelNote = true
        vm.changeContent("keep draft"); advanceTimeBy(501); runCurrent()
        vm.requestFinishConfirmation(); runCurrent()
        vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Confirming("40"), vm.finishUi.value)
        assertEquals("keep draft", vm.draftContent); assertNotNull(vm.error); assertEquals(0, f.clockCalls)
        f.cancelNote = false; vm.confirmFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Completed("s"), vm.finishUi.value)
        assertEquals("keep draft", f.saved.last().content)
    }
    @Test fun recreationDuringCompleteUsesCurrentNavigationCallbackNotDisposedActivity() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent()
        f.finishBarrier = CompletableDeferred(); vm.requestFinishConfirmation(); runCurrent()
        var oldCalls = 0; var newCalls = 0
        val old: (String) -> Unit = { oldCalls++ }; val current: (String) -> Unit = { newCalls++ }
        vm.confirmFinish(old); runCurrent()
        vm.releaseCompletionCallback(old); vm.dispatchCompletion(current)
        f.finishBarrier!!.complete(Unit); runCurrent()
        vm.dispatchCompletion(current)
        assertEquals(0, oldCalls); assertEquals(1, newCalls); assertEquals(1, f.clockCalls)
    }
    @Test fun unknownCloseoutReadCannotClaimReadingEndedAfterFailedRetry() = closeoutTest {
        val f = Fixture(); f.state.value = null
        val vm = f.vm(); runCurrent(); vm.retryFinish {}; runCurrent()
        assertFalse((vm.finishUi.value as SessionFinishUiState.SaveFailed).logicallyClosed)
        assertNull(vm.closeoutSnapshot); assertFalse(vm.canEditLearning); assertEquals(0, f.clockCalls)
    }
    @Test fun evidenceRefreshQueuedBeforeFinalConfirmationCannotSubmitAfterSavingGate() = closeoutTest {
        val f = Fixture(); val vm = f.vm(); runCurrent()
        f.refreshBarrier = CompletableDeferred(); vm.reportEvidence(false, true); runCurrent()
        assertEquals(1, f.evidenceCalls)
        vm.requestFinishConfirmation(); runCurrent(); f.finishBarrier = CompletableDeferred()
        vm.confirmFinish {}; runCurrent()
        f.refreshBarrier!!.complete(Unit); runCurrent()
        assertEquals(1, f.evidenceCalls); assertEquals(SessionFinishUiState.Saving, vm.finishUi.value)
        f.finishBarrier!!.complete(Unit); runCurrent()
    }
}
