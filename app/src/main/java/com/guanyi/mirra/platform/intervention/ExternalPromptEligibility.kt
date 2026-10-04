package com.guanyi.mirra.platform.intervention

import com.guanyi.mirra.domain.monitoring.InterventionUiModel
import com.guanyi.mirra.platform.focus.MonitorSnapshot

internal fun externalPromptEligible(prompt: InterventionUiModel?, monitor: MonitorSnapshot,
    elapsedNow: Long, interactive: Boolean, locked: Boolean): Boolean {
    // The frozen controller owns Prompt validity. SystemUI / risk exit is not dismissal:
    // cancelling here would remove a notification while the user opens the drawer to tap it.
    // Query continuity remains a separate safety prerequisite, never a claim of Focus.
    val lastQuery = monitor.lastSuccessfulQueryElapsed
    return prompt != null && !prompt.dismissed && monitor.running &&
        lastQuery != null && elapsedNow - lastQuery in 0 until 6_000 && interactive && !locked
}
