package com.guanyi.mirra.domain.trends

import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.SessionTimelineValidator

class TrendsService(private val timelineValidator: SessionTimelineValidator = SessionTimelineValidator()) {
    fun windows(range: TrendsRange, time: AnalyticsTimeContext): TrendsWindows {
        val now = time.now.toEpochMilli()
        val days = range.days ?: return TrendsWindows(TrendsWindow(null, now), null)
        val today = time.now.atZone(time.zoneId).toLocalDate()
        val currentStart = today.minusDays(days - 1L).atStartOfDay(time.zoneId).toInstant().toEpochMilli()
        val previousStart = today.minusDays(days * 2L - 1L).atStartOfDay(time.zoneId).toInstant().toEpochMilli()
        return TrendsWindows(
            current = TrendsWindow(currentStart, now),
            previous = TrendsWindow(previousStart, Math.subtractExact(currentStart, 1), currentStart),
        )
    }

    fun accumulator(range: TrendsRange, time: AnalyticsTimeContext): TrendsAccumulator =
        TrendsAccumulator(range, time, windows(range, time), timelineValidator)
}
