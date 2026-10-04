package com.guanyi.mirra.domain.intervention

import com.guanyi.mirra.domain.monitoring.InterventionUiModel
import org.junit.Assert.*
import org.junit.Test

class PresentationEpisodeTest {
    private val original = InterventionUiModel("session", "token", "token", "distraction", "risk", 0, 0)
    @Test fun sameEpisodeRecoveryResolvesCurrentSegmentBeforeCloseAndReceipt() {
        val recovery = original.copy(segmentId = "recovery")
        assertSame(recovery, currentPresentationEpisode(original, recovery))
    }
    @Test fun unchangedEpisodeStillResolves() {
        assertSame(original, currentPresentationEpisode(original, original))
    }
    @Test fun dismissedAbsentOrDifferentEpisodeNeverResolves() {
        assertNull(currentPresentationEpisode(original, null))
        assertNull(currentPresentationEpisode(original, original.copy(dismissed = true)))
        assertNull(currentPresentationEpisode(original, original.copy(promptToken = "new", eventId = "new")))
        assertNull(currentPresentationEpisode(original, original.copy(sessionId = "other")))
        assertNull(currentPresentationEpisode(original, original.copy(eventId = "other")))
        assertNull(currentPresentationEpisode(original, original.copy(packageName = "other")))
    }
}
