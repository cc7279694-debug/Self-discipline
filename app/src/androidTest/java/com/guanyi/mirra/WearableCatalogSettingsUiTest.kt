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

/** Native Compose fixture with no real SDK gateway, account or user's database. */
class WearableCatalogSettingsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private class Settings : WearableSettingsStore {
        private var companion = true
        private var catalog = false
        private var ids = emptyList<String>()
        override val enabled get() = companion
        override val catalogEnabled get() = catalog
        override val catalogSelectedIds get() = ids
        override fun setEnabled(value: Boolean) { companion = value }
        override fun setCatalogEnabled(value: Boolean) { catalog = value }
        override fun setCatalogSelectedIds(value: List<String>) { ids = value }
    }
    private val settings = Settings()
    private lateinit var coordinator: WearableCoordinator
    @Before fun setup() {
        val transport = object : WearTransport {
            override suspend fun connect(requestAuthorization: Boolean) = WearConnection("fixture", 1, true)
            override suspend fun send(nodeId: String, payload: ByteArray) = Unit
            override fun setListener(listener: WearTransportListener?) = Unit
            override suspend fun close() = Unit
        }
        coordinator = WearableCoordinator(scope, transport, settings)
        runBlocking { coordinator.bindSource(WearableSnapshotSource { WearableSnapshot(WearablePhase.IDLE) }, catalog = object : WearableCatalogSource {
            override suspend fun read(selectedIds: List<String>) = WearableBookCatalog(books = emptyList())
            override suspend fun choices() = listOf(WearableCatalogChoice("main", "手机主线书籍", true),
                WearableCatalogChoice("extra", "用户选择的另一书籍", false))
        }) }
        rule.setContent { MirraTheme { WearableSettingsScreen(coordinator, {}) } }
    }
    @After fun close() { rule.activityRule.scenario.close(); runBlocking { coordinator.close() }; scope.cancel() }
    @Test fun newCatalogDataRequiresExplicitDisclosureAndCancellationKeepsOldReadonlyScope() {
        rule.onNodeWithTag("wearable-catalog-toggle").performScrollTo().performClick()
        rule.onNodeWithText("允许同步离线书籍目录").assertIsDisplayed()
        captureReadingRecordEvidence("05c-catalog-disclosure")
        rule.onNodeWithText("暂不开启").performClick(); rule.waitForIdle()
        assertFalse(settings.catalogEnabled)
        rule.onNodeWithTag("wearable-catalog-toggle").performScrollTo().performClick()
        rule.onNodeWithTag("wearable-catalog-confirm").performClick()
        rule.waitUntil(5_000) { settings.catalogEnabled }
        assertTrue(settings.enabled)
        assertFalse(settings.controlEnabled)
    }
    @Test fun mainlineCannotBeDeselectedAndSavingExtraSelectionChangesOnlyLocalSettings() {
        coordinator.setCatalogEnabledFromUser(true)
        rule.onNodeWithTag("wearable-catalog-select").performScrollTo().performClick()
        rule.waitUntil(5_000) { coordinator.state.value.catalogChoices.size == 2 }
        rule.onNodeWithTag("wearable-catalog-book-main").assertIsOn().assertIsNotEnabled()
        rule.onNodeWithTag("wearable-catalog-book-extra").performClick()
        captureReadingRecordEvidence("05c-catalog-selection")
        rule.onNodeWithText("保存选择").performClick()
        rule.waitUntil(5_000) { settings.catalogSelectedIds == listOf("extra") }
        assertEquals("NOT_SENT", coordinator.state.value.catalogStatus)
        assertNull(coordinator.state.value.lastCatalogAt)
    }
}
