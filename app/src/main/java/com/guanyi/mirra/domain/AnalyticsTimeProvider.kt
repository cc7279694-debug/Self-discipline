package com.guanyi.mirra.domain

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

data class AnalyticsTimeContext(
    val now: Instant,
    val zoneId: ZoneId,
)

class AnalyticsTimeProvider(
    private val clock: Clock = Clock.systemUTC(),
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
) {
    fun snapshot(): AnalyticsTimeContext = AnalyticsTimeContext(clock.instant(), zoneProvider())
}
