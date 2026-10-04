package com.guanyi.mirra.feature.profile

import com.guanyi.mirra.data.preferences.*
import com.guanyi.mirra.domain.intervention.DeliveryCapabilities
import com.guanyi.mirra.navigation.TopLevelDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CrossAppInterventionUserActionsTest {
    private class Preferences : AppPreferencesRepository {
        override val lastDestination = MutableStateFlow(TopLevelDestination.Start)
        override val themeId = MutableStateFlow(MirraThemeId.BLUE)
        override val dndEnabled = MutableStateFlow(false)
        override val crossAppInterventionEnabled = MutableStateFlow(false)
        override suspend fun setLastDestination(destination: TopLevelDestination) = Unit
        override suspend fun setThemeId(themeId: MirraThemeId) = Unit
        override suspend fun setDndEnabled(enabled: Boolean) = Unit
        override suspend fun setCrossAppInterventionEnabled(enabled: Boolean) { crossAppInterventionEnabled.value = enabled }
    }
    @Test fun activeSessionPreferenceDoesNotAlterSnapshot() = runTest {
        val p = Preferences()
        val a = DefaultCrossAppInterventionUserActions(p, { DeliveryCapabilities(37, true, true, true) }, { true }, { false }, { true })
        a.setEnabled(true)
        assertTrue(p.crossAppInterventionEnabled.value)
        assertTrue(a.state.value.enabled)
        assertTrue(a.state.value.activeSession)
        assertFalse(a.state.value.snapshotEnabled)
        assertEquals("悬浮提醒已就绪", a.state.value.status)
    }
    @Test fun permissionReturnRefreshesAvailabilityWithoutChangingPreference() = runTest {
        val p = Preferences().apply { crossAppInterventionEnabled.value = true }
        var caps = DeliveryCapabilities(37, false, false, true)
        val a = DefaultCrossAppInterventionUserActions(p, { caps }, { false }, { false }, { true })
        a.refresh(); assertEquals("仅回到 Mirra 后提示", a.state.value.status)
        caps = caps.copy(notificationGranted = true)
        a.refresh(); assertEquals("将使用通知提醒", a.state.value.status)
        caps = caps.copy(overlayGranted = true)
        a.refresh(); assertEquals("悬浮提醒已就绪", a.state.value.status)
        assertTrue(p.crossAppInterventionEnabled.value)
    }
    @Test fun channelDisabledAndOldApiNeverClaimOverlayReady() = runTest {
        val p = Preferences().apply { crossAppInterventionEnabled.value = true }
        val a = DefaultCrossAppInterventionUserActions(p, { DeliveryCapabilities(25, true, true, false) }, { false }, { false }, { false })
        a.refresh(); assertEquals("仅回到 Mirra 后提示", a.state.value.status)
        assertFalse(a.state.value.capabilities.overlayAvailable)
    }
    @Test fun turningPreferenceOffLeavesCurrentEnabledSnapshotIntact() = runTest {
        val p = Preferences().apply { crossAppInterventionEnabled.value = true }
        val a = DefaultCrossAppInterventionUserActions(p, { DeliveryCapabilities(37, false, true, true) }, { true }, { true }, { true })
        a.setEnabled(false)
        assertFalse(a.state.value.enabled)
        assertTrue(a.state.value.snapshotEnabled)
        assertFalse(p.crossAppInterventionEnabled.value)
    }
}
