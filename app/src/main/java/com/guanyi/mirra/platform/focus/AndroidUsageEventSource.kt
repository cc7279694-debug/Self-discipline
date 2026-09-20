package com.guanyi.mirra.platform.focus

import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.os.Build
import com.guanyi.mirra.domain.monitoring.UsageEventFact
import com.guanyi.mirra.domain.monitoring.UsageEventKind

class AndroidUsageEventSource(private val usageStats: UsageStatsManager) : UsageEventSource {
    override fun query(startWallMillis: Long, endWallMillis: Long): List<UsageEventFact>? {
        val events = if (Build.VERSION.SDK_INT >= 35) {
            usageStats.queryEvents(UsageEventsQuery.Builder(startWallMillis, endWallMillis)
                .setEventTypes(
                    UsageEvents.Event.ACTIVITY_RESUMED,
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED,
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                    UsageEvents.Event.KEYGUARD_SHOWN,
                ).build())
        } else usageStats.queryEvents(startWallMillis, endWallMillis)
        if (events == null) return null
        val result = ArrayList<UsageEventFact>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            mapEvent(event.eventType, event.timeStamp, event.packageName, event.className)?.let(result::add)
        }
        return result
    }

    companion object {
        fun mapEvent(type: Int, atWall: Long, packageName: String?, className: String?): UsageEventFact? {
            val kind = when (type) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventKind.ACTIVITY_RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageEventKind.ACTIVITY_PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> UsageEventKind.ACTIVITY_STOPPED
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEventKind.SCREEN_OFF
                UsageEvents.Event.KEYGUARD_SHOWN -> UsageEventKind.DEVICE_LOCKED
                else -> return null // unlock/USER_PRESENT is not a distraction fact
            }
            return UsageEventFact(atWall, kind, packageName, className)
        }
    }
}
