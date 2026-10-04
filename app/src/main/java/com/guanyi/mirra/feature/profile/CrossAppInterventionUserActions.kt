package com.guanyi.mirra.feature.profile

import com.guanyi.mirra.data.preferences.AppPreferencesRepository
import com.guanyi.mirra.domain.intervention.DeliveryCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException

data class CrossAppInterventionSettingsState(val enabled: Boolean = false,
    val capabilities: DeliveryCapabilities = DeliveryCapabilities(23, false, false, false),
    val activeSession: Boolean = false, val snapshotEnabled: Boolean = false,
    val appVisible: Boolean = false, val error: String? = null) {
    val status: String get() = when {
        !enabled -> "关闭"
        capabilities.overlayAvailable -> "悬浮提醒已就绪"
        capabilities.notificationAvailable -> "将使用通知提醒"
        else -> "仅回到 Mirra 后提示"
    }
}

interface CrossAppInterventionUserActions {
    val state: StateFlow<CrossAppInterventionSettingsState>
    suspend fun refresh()
    suspend fun setEnabled(enabled: Boolean)
}

class DefaultCrossAppInterventionUserActions(
    private val preferences: AppPreferencesRepository,
    private val capabilityProvider: () -> DeliveryCapabilities,
    private val activeSessionProvider: suspend () -> Boolean,
    private val snapshotProvider: () -> Boolean,
    private val visibleProvider: () -> Boolean,
) : CrossAppInterventionUserActions {
    private val mutable = MutableStateFlow(CrossAppInterventionSettingsState())
    override val state: StateFlow<CrossAppInterventionSettingsState> = mutable
    override suspend fun refresh() {
        try {
            mutable.value = CrossAppInterventionSettingsState(preferences.crossAppInterventionEnabled.first(),
                capabilityProvider(), activeSessionProvider(), snapshotProvider(), visibleProvider())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutable.value = mutable.value.copy(error = "提醒状态暂不可用") }
    }
    override suspend fun setEnabled(enabled: Boolean) {
        try { preferences.setCrossAppInterventionEnabled(enabled); refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutable.value = mutable.value.copy(error = "设置保存失败，请重试") }
    }
}
