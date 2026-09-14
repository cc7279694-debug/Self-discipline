package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentTimelinePolicyTest {
    private val policy = SegmentTimelinePolicy()

    @Test fun `continuous full timeline is trusted`() {
        assertTrue(policy.isFullyTrusted(0, 20, listOf(segment(0, 10), segment(10, 20)), MonitoringCoverage.FULL))
    }

    @Test fun `gap overlap zero duration and session overflow are invalid`() {
        listOf(
            listOf(segment(0, 9), segment(10, 20)),
            listOf(segment(0, 11), segment(10, 20)),
            listOf(segment(0, 0)),
            listOf(segment(-1, 20)),
            listOf(segment(0, 21)),
        ).forEach { timeline -> assertFails { policy.requireValid(0, 20, timeline) } }
    }

    @Test fun `unmonitored or partial coverage can never be fully trusted`() {
        assertTrue(!policy.isFullyTrusted(0, 20, listOf(segment(0, 20, SessionSegmentType.UNMONITORED)), MonitoringCoverage.FULL))
        assertTrue(!policy.isFullyTrusted(0, 20, listOf(segment(0, 20)), MonitoringCoverage.PARTIAL))
    }

    private fun segment(start: Long, end: Long, type: SessionSegmentType = SessionSegmentType.FOCUS) =
        SegmentInterval(type, start, end)

    private fun assertFails(block: () -> Unit) {
        try { block(); throw AssertionError("Expected failure") } catch (_: IllegalArgumentException) {}
    }
}
