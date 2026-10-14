package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.guanyi.mirra.domain.wearable.*
import com.guanyi.mirra.feature.wearable.WearableSettingsScreen
import com.guanyi.mirra.platform.wearable.*
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Synthetic Compose/consent fixture: no real SDK gateway or user's Room database. */
class WearableRecordSettingsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private class Settings : WearableSettingsStore {
        override val enabled = true
        @Volatile private var consent = false
        override val recordSyncEnabled get() = consent
        override fun setRecordSyncEnabled(value: Boolean) { consent = value }
        override fun setEnabled(value: Boolean) { check(value) }
    }
    private val settings = Settings()
    private val existingRecords = listOf("existing-closed-a", "existing-closed-b")
    private var offers = 0
    private var writes = 0
    private lateinit var coordinator: WearableCoordinator
    @Before fun setup() {
        val transport = object : WearTransport {
            override suspend fun connect(requestAuthorization: Boolean) = WearConnection("fixture", 1, true)
            override suspend fun send(nodeId: String, payload: ByteArray) = Unit
            override fun setListener(listener: WearTransportListener?) = Unit
            override suspend fun close() = Unit
        }
        val receiver = object : WearableRecordReceiver {
            override suspend fun offer(installationId: String, sessionId: String, digest: String, bytes: Int): WearableStoredRecord? { offers++; return null }
            override suspend fun persist(canonical: String, digest: String): WearableRecordReceipt { writes++; error("No record payload in consent fixture") }
            override suspend fun count() = existingRecords.size.toLong()
        }
        coordinator = WearableCoordinator(scope, transport, settings)
        runBlocking { coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) }, records = receiver) }
        rule.setContent { MirraTheme { WearableSettingsScreen(coordinator, {}) } }
    }
    @After fun close() { rule.activityRule.scenario.close(); runBlocking { coordinator.close() }; scope.cancel() }
    @Test fun defaultOffAndDisclosureCancellationNeverAuthorizeRecordReception() {
        rule.onNodeWithTag("wearable-record-toggle").performScrollTo().assertIsOff().performClick()
        rule.onNodeWithText("允许接收腕端完成记录").assertIsDisplayed()
        rule.onNodeWithText("新增接收范围：", substring = true).assertIsDisplayed()
        assertFalse(settings.recordSyncEnabled)
        assertFalse(coordinator.state.value.recordNegotiated)
        assertEquals(0, offers); assertEquals(0, writes)
        rule.onNodeWithText("暂不开启").performClick()
        rule.onNodeWithTag("wearable-record-toggle").performScrollTo().assertIsOff()
        assertFalse(settings.recordSyncEnabled)
    }
    @Test fun explicitConsentPersistsInStoreAndClosingKeepsExistingBusinessRecords() {
        val previous = existingRecords.toList()
        rule.onNodeWithTag("wearable-record-toggle").performScrollTo().performClick()
        rule.onNodeWithTag("wearable-record-confirm").performClick()
        rule.waitUntil(5_000) { settings.recordSyncEnabled }
        rule.onNodeWithTag("wearable-record-toggle").performScrollTo().assertIsOn().performClick()
        rule.waitUntil(5_000) { !settings.recordSyncEnabled }
        rule.onNodeWithTag("wearable-record-toggle").assertIsOff()
        rule.waitUntil(5_000) { coordinator.state.value.importedRecordCount == 2L }
        assertEquals(previous, existingRecords)
        assertEquals(0, offers); assertEquals(0, writes)
        assertTrue(settings.enabled); assertFalse(settings.catalogEnabled); assertFalse(settings.controlEnabled)
    }
}
