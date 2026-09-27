package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.domain.MonitoredStartPort
import com.guanyi.mirra.feature.session.PreparationScreen
import com.guanyi.mirra.feature.session.PreparationViewModel
import com.guanyi.mirra.platform.focus.MonitoringReadyLease
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Compose proof that the visible handshake blocks re-entry until READY evidence arrives. */
class ModuleThreeBPreparationHandshakeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val readyGate = CompletableDeferred<MonitoringReadyLease>()
    private var starts = 0
    private lateinit var container: TestAppContainer

    @Before fun setUp() {
        val monitor = object : MonitoredStartPort {
            override suspend fun preflight() = true
            override fun start(): String { starts++; return "compose-generation" }
            override suspend fun awaitReady(generation: String, timeoutMillis: Long) = readyGate.await()
            override fun verify(lease: MonitoringReadyLease) = true
            override suspend fun verifyAfterCommit(lease: MonitoringReadyLease) = true
            override fun bind(sessionId: String, generation: String) = true
            override fun stopUnbound(generation: String) = Unit
            override fun nowWall() = System.currentTimeMillis()
        }
        container = TestAppContainer(ApplicationProvider.getApplicationContext(), monitor)
    }

    @After fun tearDown() {
        composeRule.activityRule.scenario.close()
        container.close()
    }

    @Test fun loadingIsVisibleAndRepeatedTapDoesNotStartAnotherMonitor() {
        val item = runBlocking { container.learningItemRepository.create("握手测试", 100, 1) }
        val intent = runBlocking { container.studyWorkflowRepository.createIntent(item.id) }
        val started = AtomicReference<String?>(null)
        val viewModel = PreparationViewModel(intent.id, container.studyWorkflowRepository,
            container.learningItemRepository, container.sessionStartCoordinator)
        composeRule.setContent {
            PreparationScreen(viewModel, onStarted = { started.set(it) }, onAbandoned = {}, onBack = {})
        }
        composeRule.waitUntil(5_000) { viewModel.uiState.value.item != null }
        composeRule.onNodeWithText("我已拿起书，开始阅读").performClick()
        composeRule.waitUntil(5_000) { viewModel.starting }
        composeRule.onNodeWithText("正在准备本次学习…").assertExists()
        composeRule.onNodeWithText("我已拿起书，开始阅读").assertIsNotEnabled()
        viewModel.start { _, _ -> }
        assertEquals(1, starts)

        val now = System.currentTimeMillis()
        readyGate.complete(MonitoringReadyLease("compose-generation", now, 1_000, 1, now, 1_000,
            0, false, false))
        composeRule.waitUntil(5_000) { started.get() != null }
        assertEquals(1, starts)
        assertEquals(1, runBlocking { container.database.sessionDao().listAll().size })
    }
}
