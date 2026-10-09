package com.guanyi.mirra.feature.start

import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.local.model.RecentReadingSnapshot
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FirstActionEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun close() = Dispatchers.resetMain()

    @Test fun savingPinsTheChosenBookAndEditorUntilTheOriginalStartCompletes() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val first = item("first")
        val second = item("second")
        val sources = MutableStateFlow(listOf(first, second))
        val active = MutableStateFlow<StudyIntentEntity?>(null)
        val items = object : LearningItemRepository by unsupported(LearningItemRepository::class.java) {
            override fun observeAll() = sources
            override suspend fun updateFirstAction(id: String, firstAction: String): LearningItemEntity {
                gate.await()
                val updated = sources.value.single { it.id == id }.copy(firstAction = firstAction)
                sources.value = sources.value.map { if (it.id == id) updated else it }
                return updated
            }
        }
        val workflow = object : StudyWorkflowRepository by unsupported(StudyWorkflowRepository::class.java) {
            override fun observeActiveIntent() = active
            override fun observeActiveSession() = flowOf<StudySessionEntity?>(null)
            override fun observeLatestNormalReading(learningItemId: String) = flowOf<RecentReadingSnapshot?>(null)
            override suspend fun createIntent(learningItemId: String, setAsMainline: Boolean): StudyIntentEntity =
                StudyIntentEntity("new-intent", learningItemId, 1_000, null, null, null, null, 1).also { active.value = it }
        }
        val vm = StartViewModel(items, workflow, clock = { 1_000 })
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            runCurrent()
            vm.selectItem(first.id); runCurrent()
            vm.begin {}; runCurrent()
            assertNull(active.value)
            assertEquals(first.id, vm.uiState.value.firstActionEditor?.item?.id)
            vm.saveFirstAction("翻到当前页，读第一段。") {}; runCurrent()
            assertTrue(vm.uiState.value.isSubmitting)
            vm.selectItem(second.id)
            vm.setSelectedAsMainline(true)
            vm.begin {}
            vm.dismissFirstActionEditor()
            runCurrent()
            val chosen = vm.uiState.value.content as StartContentState.ChooseInProgress
            assertEquals(first.id, chosen.selectedItemId)
            assertFalse(chosen.setSelectedAsMainline)
            assertEquals(first.id, vm.uiState.value.firstActionEditor?.item?.id)
            assertNull(active.value)
            gate.complete(Unit); runCurrent()
            assertEquals(first.id, active.value?.learningItemId)
            assertEquals("翻到当前页，读第一段。", sources.value.single { it.id == first.id }.firstAction)
            assertEquals("", sources.value.single { it.id == second.id }.firstAction)
            assertNull(vm.uiState.value.firstActionEditor)
        } finally { vm.viewModelScope.cancel() }
    }

    private fun item(id: String) = LearningItemEntity(id, id, LearningItemStatus.IN_PROGRESS, 100, 1, null, "", 1, 1, null)
    private fun <T : Any> unsupported(type: Class<T>): T = requireNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) {
        _, method, _ -> error("Unexpected ${method.name}")
    }))
}
