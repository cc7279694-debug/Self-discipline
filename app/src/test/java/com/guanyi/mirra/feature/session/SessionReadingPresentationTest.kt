package com.guanyi.mirra.feature.session

import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.local.model.CloseoutSnapshot
import com.guanyi.mirra.feature.profile.LearningProtectionMonitoringUiState
import com.guanyi.mirra.feature.profile.LearningProtectionServiceStatus
import org.junit.Assert.*
import org.junit.Test

class SessionReadingPresentationTest {
    private val session = StudySessionEntity("s", "book", "intent", 1_000, null, null,
        40, 40, null, null, null, 1)

    @Test fun secondsAndHourBoundariesNeverWrap() {
        assertEquals("00:00", formatReadingElapsed(0))
        assertEquals("00:59", formatReadingElapsed(59_999))
        assertEquals("01:00", formatReadingElapsed(60_000))
        assertEquals("59:59", formatReadingElapsed(3_599_999))
        assertEquals("1:00:00", formatReadingElapsed(3_600_000))
        assertEquals("123:04:05", formatReadingElapsed(443_045_000))
    }

    @Test fun absentOrInvalidTimeIsNotInventedZero() {
        assertEquals("—", formatReadingElapsed(null))
        assertEquals("—", formatReadingElapsed(-1))
        assertNull(readingElapsedMillis(null, null, 2_000, false))
        assertNull(readingElapsedMillis(session, null, 999, false))
        assertNull(readingElapsedMillis(session.copy(startedAt = Long.MIN_VALUE), null, Long.MAX_VALUE, false))
    }

    @Test fun onlyActiveReadingUsesExistingLiveClock() {
        assertEquals(59_000L, readingElapsedMillis(session, null, 60_000, false))
        assertNull(readingElapsedMillis(session, null, 60_000, true))
    }

    @Test fun pendingAndCompletedUseTheSameFrozenBoundaryNotUiTime() {
        val pending = CloseoutSnapshot("s", 61_000, 42)
        assertEquals(60_000L, readingElapsedMillis(session, pending, 99_000, true))
        assertEquals(60_000L, readingElapsedMillis(session, pending, 199_000, true))
        assertEquals(60_000L, readingElapsedMillis(session.copy(endedAt = 61_000, activeSlot = null), null, 199_000, true))
    }

    @Test fun anotherSessionsSnapshotCannotFreezeOrSupplyTime() {
        val stale = CloseoutSnapshot("another", 61_000, 42)
        assertNull(readingElapsedMillis(session, stale, 99_000, true))
        assertEquals(98_000L, readingElapsedMillis(session, stale, 99_000, false))
    }

    @Test fun invalidOrContradictoryFrozenBoundaryIsUnavailable() {
        assertNull(readingElapsedMillis(session, CloseoutSnapshot("s", 500, 42), 99_000, true))
        assertNull(readingElapsedMillis(session.copy(endedAt = 62_000), CloseoutSnapshot("s", 61_000, 42), 99_000, true))
    }

    @Test fun fullWithoutCurrentReadyProofDoesNotMeanHealthy() {
        LearningProtectionServiceStatus.entries.filter { it != LearningProtectionServiceStatus.READY }.forEach {
            assertNotEquals("监测正常", sessionMonitoringHeadline(LearningProtectionMonitoringUiState(
                activeSessionId = "s", coverage = MonitoringCoverage.FULL, serviceStatus = it)))
        }
        assertEquals("正在确认监测状态", sessionMonitoringHeadline(LearningProtectionMonitoringUiState(
            activeSessionId = "s", coverage = MonitoringCoverage.FULL,
            serviceStatus = LearningProtectionServiceStatus.NOT_CONFIRMED)))
    }

    @Test fun partialAndNoneCannotBecomeHealthyThroughPermissionOrReadyChanges() {
        for (status in LearningProtectionServiceStatus.entries) {
            assertEquals("监测已中断", sessionMonitoringHeadline(LearningProtectionMonitoringUiState(
                activeSessionId = "s", coverage = MonitoringCoverage.PARTIAL, serviceStatus = status)))
            assertEquals("本次未开启监测", sessionMonitoringHeadline(LearningProtectionMonitoringUiState(
                activeSessionId = "s", coverage = MonitoringCoverage.NONE, serviceStatus = status)))
        }
    }

    @Test fun onlyCurrentFullAndReadyProjectionCanSayHealthy() {
        assertEquals("监测正常", sessionMonitoringHeadline(LearningProtectionMonitoringUiState(
            activeSessionId = "s", coverage = MonitoringCoverage.FULL,
            serviceStatus = LearningProtectionServiceStatus.READY)))
        assertEquals("正在确认监测状态", sessionMonitoringHeadline(LearningProtectionMonitoringUiState(
            coverage = MonitoringCoverage.FULL, serviceStatus = LearningProtectionServiceStatus.READY)))
    }
}
