package com.guanyi.mirra.feature.session

import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.data.repository.StudyWorkflowRepository
import com.guanyi.mirra.domain.MonitoredStartPort
import com.guanyi.mirra.domain.MonitoredStartStore
import com.guanyi.mirra.domain.SessionStartCoordinator
import com.guanyi.mirra.domain.IntentExpiryPolicy
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PreparationFirstActionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun close() = Dispatchers.resetMain()

    @Test fun legacyBlankCannotBecomeSessionThroughCompletionConfirmation() = invalidActionCannotStart("")

    @Test fun legacyGeneratedActionCannotBecomeSessionThroughCompletionConfirmation() =
        invalidActionCannotStart("拿起《旧书》，翻到第 9 页。")

    @Test fun completionReadsPersistedActionInsteadOfAcceptingStaleVisibleChoice() =
        invalidActionCannotStart("把书放到桌上，读第一段。", persistedAction = "")

    @Test fun changedValidPersistedActionRequiresFreshVisibleConfirmation() =
        invalidActionCannotStart("把书放到桌上，读第一段。", persistedAction = "站起来，翻开书。")

    @Test fun expiredConcreteActionConfirmationReachesFrozenStoreAndPersistsTimeoutWithoutSession() = runTest(dispatcher) {
        val now = 2_000_000L
        val item = LearningItemEntity("item", "旧书", LearningItemStatus.IN_PROGRESS, 100, 9, null,
            "把书放到桌上，读第一段。", 1, 1, null)
        val intent = StudyIntentEntity("intent", item.id, 1, 2, null, null, null, 1)
        val facts = MutableStateFlow(intent)
        val items = object : LearningItemRepository by unsupported(LearningItemRepository::class.java) {
            override fun observeAll() = MutableStateFlow(listOf(item))
            override suspend fun get(id: String) = item.takeIf { it.id == id }
        }
        val workflow = object : StudyWorkflowRepository by unsupported(StudyWorkflowRepository::class.java) {
            override fun observeActiveIntent() = facts.map { it.takeIf { row -> row.activeSlot == 1 } }
            override suspend fun markTransitioned(intentId: String) = Unit
        }
        val created = mutableListOf<StudySessionEntity>()
        val store = object : MonitoredStartStore by unsupported(MonitoredStartStore::class.java) {
            override suspend fun findActiveForIntent(intentId: String): StudySessionEntity? = null
            override suspend fun startUnmonitored(intentId: String, page: Int): StudySessionEntity {
                val active = facts.value
                if (IntentExpiryPolicy().isExpired(active.createdAt, now)) {
                    facts.value = active.copy(endedAt = now, outcome = IntentOutcome.TIMEOUT, activeSlot = null)
                    throw IllegalStateException("本次启动已超时")
                }
                return StudySessionEntity("session", item.id, intentId, now, null, null,
                    page, page, null, null, null, 1).also(created::add)
            }
        }
        val monitor = object : MonitoredStartPort by unsupported(MonitoredStartPort::class.java) {
            override suspend fun preflight() = false
        }
        val vm = PreparationViewModel(intent.id, workflow, items, SessionStartCoordinator(store, monitor), clock = { now })
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            runCurrent()
            vm.start { _, _ -> }; runCurrent()
            assertEquals("The existing store must own the expiry settlement", IntentOutcome.TIMEOUT, facts.value.outcome)
            assertEquals(intent.createdAt, facts.value.createdAt)
            assertEquals(intent.transitionedAt, facts.value.transitionedAt)
            assertEquals(now, facts.value.endedAt)
            assertTrue(created.isEmpty())
            assertNotNull(vm.error)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun expiredValidConcreteActionRemainsConfirmableForExistingTimeoutSettlement() {
        val item = LearningItemEntity("item", "旧书", LearningItemStatus.IN_PROGRESS, 100, 9, null,
            "把书放到桌上，读第一段。", 1, 1, null)
        val intent = StudyIntentEntity("intent", item.id, 1, 2, null, null, null, 1)
        assertTrue(PreparationUiState(intent, item, waitingMillis = 2_000_000, isExpired = true).canConfirm)
    }

    @Test fun waitUsesOriginalIntentCreationAfterTransitionAndViewModelRecreation() = runTest(dispatcher) {
        val item = LearningItemEntity("item", "书", LearningItemStatus.IN_PROGRESS, 100, 9, null,
            "把书放到桌上。", 1, 1, null)
        val intent = StudyIntentEntity("intent", item.id, 315_000, 400_000, null, null, null, 1)
        val active = MutableStateFlow<StudyIntentEntity?>(intent)
        val items = object : LearningItemRepository by unsupported(LearningItemRepository::class.java) {
            override fun observeAll() = MutableStateFlow(listOf(item))
        }
        val workflow = object : StudyWorkflowRepository by unsupported(StudyWorkflowRepository::class.java) {
            override fun observeActiveIntent() = active
            override suspend fun markTransitioned(intentId: String) = Unit
        }
        val vm = PreparationViewModel(intent.id, workflow, items,
            SessionStartCoordinator(unsupported(MonitoredStartStore::class.java), unsupported(MonitoredStartPort::class.java)),
            clock = { 500_000 + testScheduler.currentTime })
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            runCurrent()
            assertEquals(185_000L, vm.uiState.value.waitingMillis)
            assertFalse(vm.uiState.value.isExpired)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(186_000L, vm.uiState.value.waitingMillis)
            assertEquals(intent, active.value)
        } finally { vm.viewModelScope.cancel() }
        val recreated = PreparationViewModel(intent.id, workflow, items,
            SessionStartCoordinator(unsupported(MonitoredStartStore::class.java), unsupported(MonitoredStartPort::class.java)),
            clock = { 700_000 })
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recreated.uiState.collect {} }
            runCurrent()
            assertEquals(intent, recreated.uiState.value.intent)
            assertEquals(385_000L, recreated.uiState.value.waitingMillis)
            assertEquals(intent, active.value)
        } finally { recreated.viewModelScope.cancel() }
    }

    @Test fun editingAndSavingBlockCompletionUntilTheChosenActionIsPersisted() = runTest(dispatcher) {
        val item = LearningItemEntity("item", "书", LearningItemStatus.IN_PROGRESS, 100, 9, null,
            "把书放到桌上。", 1, 1, null)
        val intent = StudyIntentEntity("intent", item.id, 1_000, 1_050, null, null, null, 1)
        val active = MutableStateFlow<StudyIntentEntity?>(intent)
        val sources = MutableStateFlow(listOf(item))
        val save = CompletableDeferred<Unit>()
        val items = object : LearningItemRepository by unsupported(LearningItemRepository::class.java) {
            override fun observeAll() = sources
            override suspend fun get(id: String) = sources.value.singleOrNull { it.id == id }
            override suspend fun updatePreparationFirstAction(intentId: String, firstAction: String): LearningItemEntity {
                assertEquals(intent.id, intentId)
                save.await()
                return sources.value.single().copy(firstAction = firstAction).also { sources.value = listOf(it) }
            }
        }
        val workflow = object : StudyWorkflowRepository by unsupported(StudyWorkflowRepository::class.java) {
            override fun observeActiveIntent() = active
            override suspend fun markTransitioned(intentId: String) = Unit
        }
        val created = mutableListOf<StudySessionEntity>()
        val store = object : MonitoredStartStore by unsupported(MonitoredStartStore::class.java) {
            override suspend fun findActiveForIntent(intentId: String): StudySessionEntity? = null
            override suspend fun startUnmonitored(intentId: String, page: Int) = StudySessionEntity(
                "session", item.id, intentId, 2_000, null, null, page, page, null, null, null, 1,
            ).also(created::add)
        }
        val monitor = object : MonitoredStartPort by unsupported(MonitoredStartPort::class.java) {
            override suspend fun preflight() = false
        }
        val vm = PreparationViewModel(intent.id, workflow, items, SessionStartCoordinator(store, monitor), clock = { 1_100 })
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            runCurrent()
            vm.editFirstAction()
            vm.start { _, _ -> }; runCurrent()
            assertTrue(created.isEmpty())
            val chosen = "翻到第 9 页，读第一段。"
            vm.saveFirstAction(chosen); runCurrent()
            assertTrue(vm.savingFirstAction)
            vm.dismissFirstActionEditor()
            vm.start { _, _ -> }; runCurrent()
            assertTrue(vm.editingFirstAction)
            assertTrue(created.isEmpty())
            assertEquals(intent, active.value)
            save.complete(Unit); runCurrent()
            assertFalse(vm.editingFirstAction)
            assertFalse(vm.savingFirstAction)
            assertEquals(chosen, vm.uiState.value.firstAction)
            assertTrue(created.isEmpty())
            vm.start { _, _ -> }; runCurrent()
            assertEquals(intent.id, created.single().intentId)
            assertEquals(9, created.single().startPage)
        } finally { vm.viewModelScope.cancel() }
    }

    private fun invalidActionCannotStart(
        action: String,
        persistedAction: String = action,
        createdAt: Long = System.currentTimeMillis(),
    ) = runTest(dispatcher) {
        val item = LearningItemEntity("item", "旧书", LearningItemStatus.IN_PROGRESS, 100, 9, null, action, 1, 1, null)
        val intent = StudyIntentEntity("intent", item.id, createdAt, createdAt + 1, null, null, null, 1)
        val active = MutableStateFlow<StudyIntentEntity?>(intent)
        val items = object : LearningItemRepository by unsupported(LearningItemRepository::class.java) {
            override fun observeAll() = MutableStateFlow(listOf(item))
            override suspend fun get(id: String) = item.copy(firstAction = persistedAction).takeIf { it.id == id }
        }
        val workflow = object : StudyWorkflowRepository by unsupported(StudyWorkflowRepository::class.java) {
            override fun observeActiveIntent() = active
            override suspend fun markTransitioned(intentId: String) = Unit
        }
        val created = mutableListOf<StudySessionEntity>()
        val store = object : MonitoredStartStore by unsupported(MonitoredStartStore::class.java) {
            override suspend fun findActiveForIntent(intentId: String): StudySessionEntity? = null
            override suspend fun startUnmonitored(intentId: String, page: Int) = StudySessionEntity(
                "session", item.id, intentId, 3, null, null, page, page, null, null, null, 1,
            ).also(created::add)
        }
        val monitor = object : MonitoredStartPort by unsupported(MonitoredStartPort::class.java) {
            override suspend fun preflight() = false
        }
        val vm = PreparationViewModel(intent.id, workflow, items, SessionStartCoordinator(store, monitor))
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            runCurrent()
            vm.start { _, _ -> }; runCurrent()
            assertEquals("A legacy placeholder must not create a reading fact", emptyList<StudySessionEntity>(), created)
            assertNotNull("Repair must explain the missing concrete action", vm.error)
            assertEquals(intent, active.value)
        } finally { vm.viewModelScope.cancel() }
    }

    private fun <T : Any> unsupported(type: Class<T>): T = requireNotNull(type.cast(Proxy.newProxyInstance(
        type.classLoader, arrayOf(type),
    ) { _, method, _ -> error("Unexpected ${method.name}") }))
}
