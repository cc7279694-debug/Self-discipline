package com.guanyi.mirra.feature.start

import com.guanyi.mirra.data.local.model.RecentReadingSnapshot
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class StartRecentReadingTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 9, 19)
    private val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun recentReadingUsesActualDurationAndNonInclusivePageProgress() {
        val recent = snapshot(today.minusDays(1), 146, 164, 42)
        assertEquals("昨天 · 42 分钟 · 18 页", formatRecentReading(recent, now, zone))
    }

    @Test
    fun noPageProgressStaysZeroAndOlderDateIsExplicit() {
        val recent = snapshot(today.minusDays(4), 146, 146, 10)
        assertEquals("9月15日 · 10 分钟 · 0 页", formatRecentReading(recent, now, zone))
    }

    private fun snapshot(date: LocalDate, startPage: Int, endPage: Int, minutes: Long) = RecentReadingSnapshot(
        sessionId = "session",
        learningItemId = "book",
        startedAt = date.atStartOfDay(zone).toInstant().toEpochMilli(),
        endedAt = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
        durationMillis = minutes * 60_000L,
        startPage = startPage,
        endPage = endPage,
        noteCount = 0,
    )
}
