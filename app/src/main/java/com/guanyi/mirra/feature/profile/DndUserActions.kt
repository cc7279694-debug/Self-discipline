package com.guanyi.mirra.feature.profile

import android.content.Intent
import com.guanyi.mirra.data.local.entity.DndLifecycle
import com.guanyi.mirra.data.preferences.AppPreferencesRepository
import com.guanyi.mirra.domain.DndRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class DndPreferenceStatus { OFF, READY, NEEDS_ACCESS }

data class DndSettingsUiState(
    val enabled: Boolean = false,
    val policyAccessGranted: Boolean = false,
    val status: DndPreferenceStatus = DndPreferenceStatus.OFF,
    val versionExplanation: String = "",
    val activeSessionId: String? = null,
    val activeLifecycle: DndLifecycle? = null,
    val priorFilterPresent: Boolean = false,
    val mirraRuleId: String? = null,
    val pendingRelease: Boolean = false,
) {
    val showNextSessionHint: Boolean get() = activeSessionId != null
}

/**
 * The only UI/application boundary for user-facing DND actions.
 * It persists the preference and delegates all system effects to the frozen DND core.
 */
interface DndUserActions {
    val state: StateFlow<DndSettingsUiState>
    suspend fun refresh()
    suspend fun setEnabled(enabled: Boolean)
    fun settingsIntent(): Intent
    suspend fun retryApply()
    suspend fun retryRelease()
}

class DefaultDndUserActions(
    private val preferences: AppPreferencesRepository,
    private val activeRecordProvider: suspend () -> DndRecord?,
    private val pendingReleaseProvider: suspend () -> Boolean,
    private val applyDnd: suspend (String) -> Unit,
    private val reconcileDnd: suspend () -> Unit,
    private val policyAccessProvider: () -> Boolean,
    private val apiLevel: Int,
    private val settingsIntentFactory: () -> Intent,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DndUserActions {
    private val _state = MutableStateFlow(DndSettingsUiState())
    override val state: StateFlow<DndSettingsUiState> = _state.asStateFlow()

    override suspend fun refresh() = withContext(ioDispatcher) {
        val enabled = preferences.dndEnabled.first()
        val accessGranted = runCatching { policyAccessProvider() }.getOrDefault(false)
        val active = activeRecordProvider()?.takeIf { it.active }
        _state.value = DndSettingsUiState(
            enabled = enabled,
            policyAccessGranted = accessGranted,
            status = when {
                !enabled -> DndPreferenceStatus.OFF
                accessGranted -> DndPreferenceStatus.READY
                else -> DndPreferenceStatus.NEEDS_ACCESS
            },
            versionExplanation = versionExplanation(apiLevel),
            activeSessionId = active?.sessionId,
            activeLifecycle = active?.lifecycle,
            priorFilterPresent = active?.priorFilter != null,
            mirraRuleId = active?.ruleId,
            pendingRelease = pendingReleaseProvider(),
        )
    }

    override suspend fun setEnabled(enabled: Boolean) {
        preferences.setDndEnabled(enabled)
        refresh()
    }

    override fun settingsIntent(): Intent = settingsIntentFactory()

    override suspend fun retryApply() = withContext(ioDispatcher) {
        val current = _state.value
        val active = activeRecordProvider()?.takeIf { it.active }
        if (current.enabled && active?.lifecycle == DndLifecycle.APPLY_FAILED) {
            applyDnd(active.sessionId)
        }
        refresh()
    }

    override suspend fun retryRelease() = withContext(ioDispatcher) {
        reconcileDnd()
        refresh()
    }

    private fun versionExplanation(api: Int): String = when {
        api in 23..28 -> "使用系统现有的勿扰规则。允许的联系人、应用和通知显示方式取决于你的系统设置。"
        api >= 35 -> "学习时由 Mirra 启用独立的系统勿扰规则，普通消息将受到限制；来电按系统勿扰设置处理。你也可以在系统勿扰设置中调整或停用 Mirra 规则。"
        else -> "学习时由 Mirra 启用独立的系统勿扰规则，普通消息将受到限制；来电按系统勿扰设置处理。"
    }
}

/** Used by tests and preview-only callers that do not provide Android DND capabilities. */
class NoopDndUserActions : DndUserActions {
    private val _state = MutableStateFlow(DndSettingsUiState())
    override val state: StateFlow<DndSettingsUiState> = _state.asStateFlow()
    override suspend fun refresh() = Unit
    override suspend fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled,
            status = if (enabled) DndPreferenceStatus.NEEDS_ACCESS else DndPreferenceStatus.OFF)
    }
    override fun settingsIntent(): Intent = Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS")
    override suspend fun retryApply() = Unit
    override suspend fun retryRelease() = Unit
}
