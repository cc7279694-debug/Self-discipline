package com.guanyi.mirra.domain.intervention

import com.guanyi.mirra.domain.monitoring.InterventionUiModel

/** Resolve the same live episode before callbacks; Room/binding validation still follows. */
internal fun currentPresentationEpisode(original: InterventionUiModel,
    current: InterventionUiModel?): InterventionUiModel? = current?.takeIf {
    !it.dismissed && it.sessionId == original.sessionId && it.promptToken == original.promptToken &&
        it.eventId == original.eventId && it.packageName == original.packageName
}
