package com.guanyi.mirra.feature.session

import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.local.model.CloseoutSnapshot
import com.guanyi.mirra.data.repository.NoteRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.domain.SessionFinishResult
import com.guanyi.mirra.domain.SessionManager
import com.guanyi.mirra.domain.monitoring.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Suspended repository reads model Room return dispatch, without wall-clock delays. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCloseoutObserverRaceTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<SessionViewModel>()

    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun raceTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { models.forEach { it.viewModelScope.cancel() } }
    }

    @Test fun delayedPendingSnapshotCannotFlashRetryAfterOwnedCompletion() = raceTest {
        assertOwnedCompletionSurvivesDelayedPendingRead(snapshotReadFails = false)
    }

    @Test fun delayedPendingSnapshotFailureCannotFlashRetryAfterOwnedCompletion() = raceTest {
        assertOwnedCompletionSurvivesDelayedPendingRead(snapshotReadFails = true)
    }

    private suspend fun TestScope.assertOwnedCompletionSurvivesDelayedPendingRead(snapshotReadFails: Boolean) {
        val f = Fixture()
        val vm = f.vm()
        val states = mutableListOf<SessionFinishUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.finishUi.toList(states) }
        runCurrent()
        f.snapshotGate = CompletableDeferred()
        f.snapshotReadFails = snapshotReadFails
        var navigationCalls = 0
        vm.requestFinishConfirmation(); runCurrent()
        vm.changeEndPage("42")
        vm.confirmFinish { navigationCalls++ }; runCurrent()

        // A is durable; the observer is suspended reading its frozen snapshot; B is still owned.
        assertTrue(f.snapshotReadStarted.isCompleted)
        assertEquals(FocusCloseoutState.PENDING, f.state.value)
        assertEquals(SessionFinishUiState.Saving, vm.finishUi.value)
        assertFalse(vm.canEditLearning)
        vm.retryFinish { navigationCalls++ }; runCurrent()
        assertEquals(0, f.retryCalls)

        // B completes while the PENDING observer has not returned. No retry is necessary.
        f.finishGate.complete(Unit); runCurrent()
        assertEquals(SessionFinishUiState.Completed("s"), vm.finishUi.value)
        assertEquals(1, navigationCalls)
        f.snapshotGate!!.complete(Unit); runCurrent()

        // Catch even a one-emission SaveFailed that the queued COMPLETED emission then hides.
        assertFalse("Successful owned closeout emitted retry UI: $states",
            states.any { it is SessionFinishUiState.SaveFailed })
        assertEquals(SessionFinishUiState.Completed("s"), vm.finishUi.value)
        assertEquals(1, f.finishCalls)
        assertEquals(0, f.retryCalls)
        assertEquals(1, f.clockCalls)
        assertEquals(1, navigationCalls)
        assertFalse(vm.canEditLearning)
    }

    @Test fun actualStageBFailureStaysClosedUntilExplicitRetryWithoutResampling() = raceTest {
        val f = Fixture(); val vm = f.vm(); runCurrent()
        f.failStageB = true
        vm.requestFinishConfirmation(); runCurrent()
        vm.changeEndPage("42"); vm.confirmFinish {}; runCurrent()
        f.finishGate.complete(Unit); runCurrent()
        assertTrue((vm.finishUi.value as SessionFinishUiState.SaveFailed).logicallyClosed)
        assertEquals(CloseoutSnapshot("s", 2_000, 42), vm.closeoutSnapshot)
        assertEquals(FocusCloseoutState.PENDING, f.state.value)
        vm.cancelFinishConfirmation(); vm.changeContent("blocked"); vm.requestFinishConfirmation()
        runCurrent()
        assertTrue(vm.finishUi.value is SessionFinishUiState.SaveFailed)
        assertEquals("", vm.draftContent)
        assertFalse(vm.canEditLearning)
        assertEquals(0, f.retryCalls)

        f.failStageB = false
        vm.retryFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Completed("s"), vm.finishUi.value)
        assertEquals(1, f.finishCalls)
        assertEquals(1, f.retryCalls)
        assertEquals(1, f.clockCalls)
        assertEquals(2_000L, f.current.value!!.endedAt)
        assertEquals(42, f.current.value!!.endPage)
    }

    @Test fun recreatedUnownedPendingOffersExplicitRetryAndCannotResumeReading() = raceTest {
        val f = Fixture(FocusCloseoutState.PENDING); val vm = f.vm(); runCurrent()
        assertTrue((vm.finishUi.value as SessionFinishUiState.SaveFailed).logicallyClosed)
        assertEquals(CloseoutSnapshot("s", 2_000, 42), vm.closeoutSnapshot)
        vm.cancelFinishConfirmation(); vm.changeContent("blocked"); vm.requestFinishConfirmation()
        runCurrent()
        assertTrue(vm.finishUi.value is SessionFinishUiState.SaveFailed)
        assertFalse(vm.canEditLearning)
        assertEquals("", vm.draftContent)
        assertEquals(0, f.finishCalls)
        assertEquals(0, f.retryCalls)
        vm.retryFinish {}; runCurrent()
        assertEquals(SessionFinishUiState.Completed("s"), vm.finishUi.value)
        assertEquals(0, f.clockCalls)
        assertEquals(1, f.retryCalls)
    }

    @Test fun unknownStateNeverClaimsLogicalClosureOrAutomaticallyRetries() = raceTest {
        val f = Fixture(null); val vm = f.vm(); runCurrent()
        assertFalse((vm.finishUi.value as SessionFinishUiState.SaveFailed).logicallyClosed)
        assertNull(vm.closeoutSnapshot)
        assertFalse(vm.canEditLearning)
        assertEquals(0, f.finishCalls)
        assertEquals(0, f.retryCalls)
        assertEquals(0, f.clockCalls)
    }

    private inline fun <reified T> unsupported(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected ${method.name}") } as T

    private inner class Fixture(initial: FocusCloseoutState? = FocusCloseoutState.ACTIVE) {
        val state = MutableStateFlow(initial)
        val current = MutableStateFlow<StudySessionEntity?>(StudySessionEntity(
            "s", "book", "intent", 1_000, null, null, 40, 40, null, null, null, 1,
        ))
        var snapshot: CloseoutSnapshot? = if (initial == FocusCloseoutState.PENDING)
            CloseoutSnapshot("s", 2_000, 42) else null
        val finishGate = CompletableDeferred<Unit>()
        val snapshotReadStarted = CompletableDeferred<Unit>()
        var snapshotGate: CompletableDeferred<Unit>? = null
        var snapshotReadFails = false
        var failStageB = false
        var finishCalls = 0
        var retryCalls = 0
        var clockCalls = 0
        val workflow = object : StudyWorkflowRepository by unsupported() {
            override fun observeSession(id: String) = current
            override fun observeCloseoutState(sessionId: String) = state
            override suspend fun getCloseoutState(sessionId: String) = state.value
            override suspend fun getCloseoutSnapshot(sessionId: String): CloseoutSnapshot? {
                val captured = snapshot
                snapshotReadStarted.complete(Unit)
                snapshotGate?.await()
                if (snapshotReadFails) error("snapshot read failed")
                return captured
            }
        }
        val notes = object : NoteRepository by unsupported() {
            override fun observeForSession(sessionId: String) = flowOf(emptyList<NoteEntity>())
        }
        val manager = object : SessionManager by unsupported() {
            override suspend fun finish(sessionId: String, endPage: Int, sample: ClockSample): SessionFinishResult {
                finishCalls++
                snapshot = CloseoutSnapshot(sessionId, sample.wallNowMillis, endPage)
                state.value = FocusCloseoutState.PENDING
                finishGate.await()
                return complete()
            }
            override suspend fun retryPendingFinish(sessionId: String): SessionFinishResult {
                retryCalls++
                return complete()
            }
            private fun complete(): SessionFinishResult {
                if (failStageB) return SessionFinishResult.PendingRetry("s")
                val ended = current.value!!.copy(endedAt = snapshot!!.closeoutStartedAt,
                    endPage = snapshot!!.requestedEndPage, endType = SessionEndType.NORMAL, activeSlot = null)
                current.value = ended
                state.value = FocusCloseoutState.COMPLETED
                return SessionFinishResult.Completed(ended)
            }
        }
        val focus: FocusSessionActions = Proxy.newProxyInstance(FocusSessionActions::class.java.classLoader,
            arrayOf(FocusSessionActions::class.java)) { _, method, _ -> when (method.name) {
                "getFocusStatus" -> MutableStateFlow(FocusStatusUiModel("s", "seg", SessionSegmentType.FOCUS,
                    MonitoringCoverage.FULL))
                "getIntervention" -> MutableStateFlow<InterventionUiModel?>(null)
                "refresh" -> Unit
                "observeEvidence" -> FocusActionResult.SUCCESS
                else -> error("Unexpected focus ${method.name}")
            } } as FocusSessionActions
        fun vm() = SessionViewModel("s", workflow, notes, manager, focusActions = focus,
            clockSample = { clockCalls++; ClockSample(2_000, 777) }).also { models += it }
    }
}
