package com.guanyi.mirra.feature.session

import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.repository.*
import com.guanyi.mirra.domain.SessionManager
import com.guanyi.mirra.domain.monitoring.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionFocusViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val calls = mutableListOf<Pair<String, List<Any?>>>()
    private val status = MutableStateFlow(FocusStatusUiModel("s", "seg", SessionSegmentType.FOCUS, MonitoringCoverage.FULL))
    private val prompt = MutableStateFlow<InterventionUiModel?>(InterventionUiModel("s", "p", "e", "seg", "risk", 5_000, 5_000, AllowanceReason.REPLY))
    private var result = FocusActionResult.SUCCESS
    private var tick = 0L
    private inline fun <reified T> proxy(noinline block: (String, List<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> block(method.name, args?.toList().orEmpty()) } as T
    private fun vm(currentSession: StudySessionEntity? = null): SessionViewModel {
        val actions = proxy<FocusSessionActions> { name, args -> when (name) {
            "getFocusStatus" -> status
            "getIntervention" -> prompt
            else -> { calls += name to args; if (name == "refresh") Unit else result }
        } }
        return SessionViewModel("s", proxy<StudyWorkflowRepository> { _, _ -> flowOf(currentSession) },
            proxy<NoteRepository> { _, _ -> flowOf(emptyList<NoteEntity>()) },
            proxy<SessionManager> { name, args -> calls += name to args; Unit }, focusActions = actions,
            clockSample = { tick++; ClockSample(10_000 + tick, tick) })
    }
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    @Test fun editingFortyToFortyTwoKeepsIntermediateTextWithoutSavingBackwardProgress() = runTest(dispatcher) {
        val session = StudySessionEntity("s", "book", "intent", 1_000, null, null, 40, 40, null, null, null, 1)
        val vm = vm(session)
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.session.collect {} }
            runCurrent()
            vm.syncCurrentPage(40)
            vm.updatePage("")
            vm.updatePage("4")
            runCurrent()
            assertEquals("4", vm.currentPageText)
            assertEquals("40", vm.draftPage)
            assertFalse(calls.any { it.first == "updatePage" })
            vm.updatePage("42")
            runCurrent()
            assertEquals("42", vm.currentPageText)
            assertEquals("42", vm.draftPage)
            assertEquals(listOf("s", 42), calls.single { it.first == "updatePage" }.second.take(2))
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun positivePageRequiresAllThreeConditions() {
        for (visible in listOf(false, true)) for (resumed in listOf(false, true)) for (focused in listOf(false, true)) {
            assertEquals(visible && resumed && focused, sessionHasPositivePageEvidence(visible, resumed, focused))
        }
    }

    @Test fun pairedSampleIsSharedByRefreshAndEvidenceAndUsesRealVisibility() = runTest(dispatcher) {
        val vm = vm()
        vm.observeFocusEvidence(false, true)
        val refresh = calls.first { it.first == "refresh" }
        val evidence = calls.first { it.first == "observeEvidence" }
        assertSame(refresh.second[1], evidence.second[1])
        assertEquals(listOf(false, true), evidence.second.subList(2, 4))
        calls.clear()
        vm.observeFocusEvidence(true, false)
        assertEquals(listOf(true, false), calls.last().second.subList(2, 4))
        vm.viewModelScope.cancel()
    }
    @Test fun breakAndAllowanceCommandsUseCurrentIdentityAndNullDuration() = runTest(dispatcher) {
        val vm = vm()
        vm.startBreak(5); runCurrent()
        assertEquals(300_000L, calls.first { it.first == "startBreak" }.second[2])
        vm.startBreak(10); vm.finishBreak(); vm.grantAllowance(); vm.extendAllowance(); vm.finishAllowance(); runCurrent()
        assertEquals(600_000L, calls.last { it.first == "startBreak" }.second[2])
        val grant = calls.first { it.first == "grantAllowance" }.second
        assertEquals(listOf("s", "seg", "p", "risk", AllowanceReason.REPLY, null), grant.take(6))
        assertEquals("seg", calls.first { it.first == "finishBreak" }.second[1])
        assertTrue(calls.any { it.first == "extendAllowance" })
        assertTrue(calls.any { it.first == "finishAllowance" })
        vm.viewModelScope.cancel()
    }
    @Test fun panelVisibilityAndReturnOnlyDelegateWithoutClearingNoteDraft() = runTest(dispatcher) {
        val vm = vm()
        vm.changeContent("still writing"); vm.changePage("42")
        vm.selectAllowanceReason(AllowanceReason.RESEARCH)
        vm.setPromptVisible(false); vm.dismissPrompt(); vm.returnToStudy(); runCurrent()
        assertEquals("still writing", vm.draftContent)
        assertEquals("42", vm.draftPage)
        assertEquals(false, calls.first { it.first == "setPromptVisible" }.second[2])
        assertTrue(calls.any { it.first == "returnToStudy" })
        vm.viewModelScope.cancel()
    }
    @Test fun errorsRefreshExpiredAndConflictButNeverFabricateSuccess() = runTest(dispatcher) {
        val vm = vm()
        for ((value, message) in listOf(FocusActionResult.EXPIRED to "当前状态已变化", FocusActionResult.CONFLICT to "操作暂未生效，请稍后重试", FocusActionResult.SAVE_FAILED to "保存失败，请重试")) {
            calls.clear(); result = value; vm.finishBreak(); runCurrent()
            assertEquals(message, vm.focusError)
            assertEquals(value != FocusActionResult.SAVE_FAILED, calls.any { it.first == "refresh" })
        }
        result = FocusActionResult.SUCCESS; vm.finishBreak(); runCurrent(); assertNull(vm.focusError)
        vm.viewModelScope.cancel()
    }
    @Test fun failedExtensionRefreshesWithoutChangingDisplayedDeadline() = runTest(dispatcher) {
        val vm = vm()
        try {
            status.value = status.value.copy(type = SessionSegmentType.TEMPORARY_ALLOWANCE, remainingMillis = 123_000, canExtend = true)
            result = FocusActionResult.SAVE_FAILED
            vm.extendAllowance(); runCurrent()
            assertEquals("保存失败，请重试", vm.focusError)
            assertTrue(calls.any { it.first == "refresh" })
            assertEquals(123_000L, vm.focusStatus.value.remainingMillis)
        } finally { vm.viewModelScope.cancel() }
    }
}
