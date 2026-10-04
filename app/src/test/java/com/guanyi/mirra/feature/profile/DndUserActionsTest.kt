package com.guanyi.mirra.feature.profile

import android.content.Intent
import com.guanyi.mirra.data.local.entity.DndLifecycle
import com.guanyi.mirra.data.preferences.AppPreferencesRepository
import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.domain.DndRecord
import com.guanyi.mirra.navigation.TopLevelDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DndUserActionsTest {
    @Test
    fun defaultPreferenceIsOff() = runTest {
        val fixture = Fixture()
        val actions = fixture.actions()

        actions.refresh()

        assertFalse(actions.state.value.enabled)
        assertEquals(DndPreferenceStatus.OFF, actions.state.value.status)
    }

    @Test
    fun enablingWithoutAccessPersistsAndRequestsAuthorization() = runTest {
        val fixture = Fixture(access = false)
        val actions = fixture.actions()

        actions.setEnabled(true)

        assertTrue(fixture.preferences.value)
        assertEquals(DndPreferenceStatus.NEEDS_ACCESS, actions.state.value.status)
        assertTrue(actions.state.value.versionExplanation.contains("独立的系统勿扰规则"))
    }

    @Test
    fun accessBecomesReadyAfterResumeRefresh() = runTest {
        val fixture = Fixture(access = false)
        val actions = fixture.actions()
        actions.setEnabled(true)

        fixture.access = true
        actions.refresh()

        assertEquals(DndPreferenceStatus.READY, actions.state.value.status)
    }

    @Test
    fun turningOffOnlyChangesPreference() = runTest {
        val fixture = Fixture(access = true)
        val actions = fixture.actions()
        actions.setEnabled(true)
        fixture.applied += "active-session"

        actions.setEnabled(false)

        assertFalse(fixture.preferences.value)
        assertTrue(fixture.applied.isNotEmpty())
        assertEquals(DndPreferenceStatus.OFF, actions.state.value.status)
    }

    @Test
    fun changingPreferenceDuringActiveSessionDoesNotApplyOrReleaseDnd() = runTest {
        val fixture = Fixture(
            access = true,
            active = DndRecord("session-1", true, DndLifecycle.ACTIVE),
        )
        val actions = fixture.actions()

        actions.setEnabled(true)
        actions.setEnabled(false)

        assertTrue(fixture.applied.isEmpty())
        assertEquals(0, fixture.reconcileCalls)
    }

    @Test
    fun activeSessionShowsNextSessionHintAndRetriesApplyOnce() = runTest {
        val fixture = Fixture(
            access = true,
            active = DndRecord("session-1", true, DndLifecycle.APPLY_FAILED),
        )
        val actions = fixture.actions()
        actions.setEnabled(true)
        actions.retryApply()

        assertEquals(listOf("session-1"), fixture.applied)
        assertTrue(actions.state.value.showNextSessionHint)
    }

    @Test
    fun failedCurrentSessionCanRetryAfterPreferenceIsTurnedOff() = runTest {
        val fixture = Fixture(
            access = true,
            active = DndRecord("session-1", true, DndLifecycle.APPLY_FAILED),
        )
        val actions = fixture.actions()

        actions.setEnabled(true)
        actions.setEnabled(false)
        actions.retryApply()

        assertEquals(listOf("session-1"), fixture.applied)
        assertFalse(fixture.preferences.value)
    }

    @Test
    fun changingPreferenceForNotAppliedSessionDoesNotApplyImmediately() = runTest {
        val fixture = Fixture(
            access = true,
            active = DndRecord("session-1", true, DndLifecycle.NOT_APPLIED),
        )
        val actions = fixture.actions()

        actions.setEnabled(false)
        actions.setEnabled(true)

        assertTrue(fixture.applied.isEmpty())
    }

    @Test
    fun pendingReleaseCanBeReconciled() = runTest {
        val fixture = Fixture(access = true, pending = true)
        val actions = fixture.actions()
        actions.refresh()
        actions.retryRelease()

        assertEquals(1, fixture.reconcileCalls)
        assertTrue(actions.state.value.pendingRelease)
    }

    @Test
    fun legacyCopyDoesNotPromiseNotificationListHiding() = runTest {
        val fixture = Fixture(access = true)
        val actions = fixture.actions(apiLevel = 28)
        actions.setEnabled(true)

        assertTrue(actions.state.value.versionExplanation.contains("系统现有的勿扰规则"))
        assertFalse(actions.state.value.versionExplanation.contains("隐藏"))
    }

    @Test
    fun modernCopyMentionsUserManagedRuleOnApi35() = runTest {
        val fixture = Fixture(access = true)
        val actions = fixture.actions(apiLevel = 35)
        actions.setEnabled(true)

        assertTrue(actions.state.value.versionExplanation.contains("调整或停用 Mirra 规则"))
    }

    private class Fixture(
        var access: Boolean = false,
        var active: DndRecord? = null,
        var pending: Boolean = false,
    ) {
        val preferences = FakePreferences()
        val applied = mutableListOf<String>()
        var reconcileCalls = 0

        fun actions(apiLevel: Int = 29) = DefaultDndUserActions(
            preferences = preferences,
            activeRecordProvider = { active },
            pendingReleaseProvider = { pending },
            applyDnd = { applied += it },
            reconcileDnd = { reconcileCalls++ },
            policyAccessProvider = { access },
            apiLevel = apiLevel,
            settingsIntentFactory = { Intent("test.settings") },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private class FakePreferences : AppPreferencesRepository {
        override val crossAppInterventionEnabled = MutableStateFlow(false)
        override suspend fun setCrossAppInterventionEnabled(enabled: Boolean) { crossAppInterventionEnabled.value = enabled }
        private val destination = MutableStateFlow(TopLevelDestination.Start)
        private val theme = MutableStateFlow(MirraThemeId.BLUE)
        private val dnd = MutableStateFlow(false)
        val value get() = dnd.value
        override val lastDestination: Flow<TopLevelDestination> = destination
        override val themeId: Flow<MirraThemeId> = theme
        override val dndEnabled: Flow<Boolean> = dnd
        override suspend fun setLastDestination(destination: TopLevelDestination) { this.destination.value = destination }
        override suspend fun setThemeId(themeId: MirraThemeId) { theme.value = themeId }
        override suspend fun setDndEnabled(enabled: Boolean) { dnd.value = enabled }
    }
}
