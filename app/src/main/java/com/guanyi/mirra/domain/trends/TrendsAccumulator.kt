package com.guanyi.mirra.domain.trends

import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.entity.FocusEventEntity
import com.guanyi.mirra.data.local.entity.FocusEventType
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.SegmentInterval
import com.guanyi.mirra.domain.SegmentTimelinePolicy
import com.guanyi.mirra.domain.SessionTimelineValidator
import com.guanyi.mirra.domain.TimelineTrust
import java.time.Instant
import java.time.LocalDate

/**
 * Each source row is supplied once by the repository's keyset scan. Raw pages are never retained.
 * Exact medians retain only scalar durations; per-session association maps are discarded immediately.
 */
class TrendsAccumulator internal constructor(
    private val range: TrendsRange,
    private val time: AnalyticsTimeContext,
    private val windows: TrendsWindows,
    private val timelineValidator: SessionTimelineValidator,
) {
    private val current = PeriodAccumulator(time.now.toEpochMilli(), timelineValidator)
    private val previous = windows.previous?.let { PeriodAccumulator(time.now.toEpochMilli(), timelineValidator) }
    private val daily = sortedMapOf<LocalDate, PeriodAccumulator>()

    fun addStartPage(sources: List<StartTrendSource>) {
        for (source in sources) {
            periodFor(source.intent.createdAt)?.addStart(source)
            dailyFor(source.intent.createdAt)?.addStart(source)
        }
    }

    fun addSessionPage(sources: List<SessionTrendSource>) {
        for (source in sources) {
            val session = source.session
            val endedAt = session.endedAt ?: continue
            if (session.activeSlot != null || source.context?.closeoutState == FocusCloseoutState.PENDING) continue
            periodFor(endedAt)?.addSession(source)
            dailyFor(endedAt)?.addSession(source)
        }
    }

    fun finish(): TrendsSnapshot {
        val currentResult = current.finish()
        val previousResult = previous?.finish()
        return TrendsSnapshot(range, time, windows, currentResult, previousResult,
            previousResult?.let { compare(currentResult, it) },
            normalReading = current.finishReading(),
            previousNormalReading = previous?.finishReading(),
            daily = finishDaily())
    }

    private fun dailyFor(timestamp: Long): PeriodAccumulator? {
        if (!windows.current.contains(timestamp)) return null
        val date = Instant.ofEpochMilli(timestamp).atZone(time.zoneId).toLocalDate()
        return daily.getOrPut(date) { PeriodAccumulator(time.now.toEpochMilli(), timelineValidator) }
    }

    private fun finishDaily(): List<DailyTrendsPoint> {
        val dates = range.days?.let { days ->
            val today = time.now.atZone(time.zoneId).toLocalDate()
            List(days) { index -> today.minusDays(days - 1L - index) }
        } ?: daily.keys.toList()
        // ALL keeps only recorded dates: an ancient damaged fact must not allocate every intervening day.
        return dates.map { date ->
            val day = daily[date] ?: PeriodAccumulator(time.now.toEpochMilli(), timelineValidator)
            DailyTrendsPoint(date, day.finishReading(), day.finish())
        }
    }

    private fun periodFor(timestamp: Long): PeriodAccumulator? = when {
        windows.current.contains(timestamp) -> current
        windows.previous?.contains(timestamp) == true -> previous
        else -> null
    }

    private fun compare(current: TrendsPeriod, previous: TrendsPeriod) = TrendsComparison(
        convertedCountDelta = current.start.convertedCount - previous.start.convertedCount,
        resolvedIntentCountDelta = current.start.conversion.denominator - previous.start.conversion.denominator,
        conversionPercentagePointDelta = current.start.conversion.value?.let { currentRate ->
            previous.start.conversion.value?.let { previousRate -> (currentRate - previousRate) * 100.0 }
        },
        stableConfirmedCountDelta = current.start.stableConfirmedCount - previous.start.stableConfirmedCount,
        trustedSessionCountDelta = current.maintain.trustedSessionCount - previous.maintain.trustedSessionCount,
        unavailableSessionCountDelta = current.maintain.unavailableSessionCount - previous.maintain.unavailableSessionCount,
        effectiveFocusMillisDelta = difference(current.maintain.effectiveFocusMillis, previous.maintain.effectiveFocusMillis),
        deepFocusMillisDelta = difference(current.maintain.deepFocusMillis, previous.maintain.deepFocusMillis),
        distractionCountDelta = current.maintain.distractionCount - previous.maintain.distractionCount,
        recoveryAttemptCountDelta = current.recover.attemptCount - previous.recover.attemptCount,
        recoverySuccessCountDelta = current.recover.successCount - previous.recover.successCount,
        recoveryUnknownCountDelta = current.recover.unknownCount - previous.recover.unknownCount,
    )

    private fun difference(current: Long?, previous: Long?): Long? =
        if (current == null || previous == null) null else try {
            Math.subtractExact(current, previous)
        } catch (_: ArithmeticException) { null }
}

private fun TrendsWindow.contains(timestamp: Long): Boolean =
    (startInclusive == null || timestamp >= startInclusive) && timestamp <= endInclusive &&
        (endExclusive == null || timestamp < endExclusive)

private class PeriodAccumulator(
    private val now: Long,
    private val timelineValidator: SessionTimelineValidator,
) {
    private val recoveryTimelinePolicy = SegmentTimelinePolicy()
    private var converted = 0L
    private var abandoned = 0L
    private var timeout = 0L
    private var open = 0L
    private var startDataIssues = 0L
    private var stableConfirmed = 0L
    private var stableUnconfirmed = 0L
    private var stableDataIssues = 0L
    private val startLatencies = mutableListOf<Long>()
    private val stableLatencies = mutableListOf<Long>()
    private var endedSessions = 0L
    private var trustedSessions = 0L
    private var unavailableSessions = 0L
    private val effectiveFocus = MillisSum()
    private val deepFocus = MillisSum()
    private var distractions = 0L
    private val distractionDuration = MillisSum()
    private var recoveryAttempts = 0L
    private var recoverySuccesses = 0L
    private var recoveryInterruptions = 0L
    private var recoveryUnknown = 0L
    private var plannedReturns = 0L
    private var invalidOrigins = 0L
    private val recoveryLatencies = mutableListOf<Long>()
    private val reading = ReadingDurationAccumulator()

    fun addStart(source: StartTrendSource) {
        val intent = source.intent
        when (intent.outcome) {
            IntentOutcome.ABANDONED -> { abandoned++; return }
            IntentOutcome.TIMEOUT -> { timeout++; return }
            null -> { open++; return }
            IntentOutcome.CONVERTED -> converted++
        }
        val session = source.linkedSessions.singleOrNull()?.takeIf {
            it.intentId == intent.id && it.learningItemId == intent.learningItemId
        }
        if (session == null) {
            startDataIssues++
            stableDataIssues++
            return
        }
        val latency = elapsed(intent.createdAt, session.startedAt)?.takeIf { session.startedAt <= now }
        if (latency == null) startDataIssues++ else startLatencies.add(latency)
        val stableAt = session.stableStartedAt
        if (stableAt == null) {
            stableUnconfirmed++
            return
        }
        val stableLatency = elapsed(session.startedAt, stableAt)?.takeIf {
            session.startedAt <= now && stableAt <= now && (session.endedAt == null || stableAt <= session.endedAt)
        }
        if (stableLatency == null) stableDataIssues++ else {
            stableConfirmed++
            stableLatencies.add(stableLatency)
        }
    }

    fun addSession(source: SessionTrendSource) {
        reading.add(source.session)
        endedSessions++
        val analysis = timelineValidator.analyze(source.session, source.context, source.segments)
        if (analysis.trust == TimelineTrust.COMPLETE_TRUSTED) {
            trustedSessions++
            // The frozen validator owns the FOCUS + DEEP_FOCUS formula.
            effectiveFocus.add(requireNotNull(analysis.effectiveFocusMillis))
            for (segment in source.segments) {
                when (segment.type) {
                    SessionSegmentType.DEEP_FOCUS -> deepFocus.add(requireNotNull(elapsed(segment.startedAt, requireNotNull(segment.endedAt))))
                    SessionSegmentType.DISTRACTION -> {
                        distractions++
                        distractionDuration.add(requireNotNull(elapsed(segment.startedAt, requireNotNull(segment.endedAt))))
                    }
                    else -> Unit
                }
            }
        } else unavailableSessions++

        val session = source.session
        if (session.endType != SessionEndType.NORMAL || requireNotNull(session.endedAt) <= session.startedAt) return
        addRecoveries(source)
    }

    private fun addRecoveries(source: SessionTrendSource) {
        val segmentsById = source.segments.groupBy { it.id }
        val eventsById = source.events.groupBy { it.id }
        // Identical replayed rows are idempotent; disagreeing payloads for one ID are damaged facts.
        val consistentEvents = eventsById.values.filter { it.distinct().size == 1 }.map { it.first() }
        for (recovery in source.segments.filter { it.type == SessionSegmentType.RECOVERY }.distinctBy { it.id }) {
            val origin = segmentsById[recovery.relatedSegmentId]?.singleOrNull()
            if (origin == null || origin.sessionId != source.session.id || recovery.sessionId != source.session.id) {
                invalidOrigins++
                continue
            }
            when (origin.type) {
                SessionSegmentType.BREAK, SessionSegmentType.TEMPORARY_ALLOWANCE -> { plannedReturns++; continue }
                SessionSegmentType.DISTRACTION -> recoveryAttempts++
                else -> { invalidOrigins++; continue }
            }
            val recoveryEnd = recovery.endedAt
            val successors = source.segments.filter { it.id != recovery.id && it.startedAt == recoveryEnd }
            val successor = successors.singleOrNull()
            val relevant: (FocusEventEntity) -> Boolean = { event ->
                (event.type == FocusEventType.RECOVERY_SUCCEEDED || event.type == FocusEventType.RECOVERY_INTERRUPTED) &&
                    (event.segmentId == recovery.id || (successor != null && event.segmentId == successor.id))
            }
            val damagedEvent = eventsById.values.any { rows -> rows.distinct().size > 1 && rows.any(relevant) }
            val relevantEvents = consistentEvents.filter(relevant)
            val event = relevantEvents.singleOrNull()
            val legalEpisode = segmentsById[recovery.id]?.size == 1 &&
                validSegment(origin, source) && validSegment(recovery, source) &&
                origin.endedAt == recovery.startedAt && successor != null && validSegment(successor, source) &&
                source.context?.sessionId?.let { it == source.session.id } != false
            val outcomeBoundary = event?.occurredAt ?: recoveryEnd
            if (!legalEpisode || damagedEvent || event == null || outcomeBoundary == null ||
                !hasLocalRecoveryProof(source, origin.startedAt, outcomeBoundary)
            ) {
                recoveryUnknown++
                continue
            }
            val next = requireNotNull(successor)
            when {
                event.sessionId != source.session.id -> recoveryUnknown++
                event.type == FocusEventType.RECOVERY_SUCCEEDED && event.segmentId == next.id &&
                    next.type == SessionSegmentType.FOCUS && event.occurredAt == recoveryEnd -> {
                    recoverySuccesses++
                    recoveryLatencies.add(requireNotNull(elapsed(recovery.startedAt, requireNotNull(recoveryEnd))))
                }
                event.type == FocusEventType.RECOVERY_INTERRUPTED && event.segmentId == recovery.id &&
                    next.type == SessionSegmentType.DISTRACTION && event.occurredAt >= next.startedAt &&
                    event.occurredAt <= requireNotNull(source.session.endedAt) -> recoveryInterruptions++
                else -> recoveryUnknown++
            }
        }
    }

    private fun hasLocalRecoveryProof(source: SessionTrendSource, startedAt: Long, endedAt: Long): Boolean {
        val context = source.context ?: return false
        if (context.sessionId != source.session.id || endedAt <= startedAt) return false
        when (context.monitoringStatus) {
            MonitoringCoverage.NONE -> return false
            MonitoringCoverage.PARTIAL -> if (context.monitoringLostAt == null) return false
            MonitoringCoverage.FULL -> Unit
        }
        if (context.monitoringLostAt?.let { it <= endedAt } == true) return false

        // Validate only this observed episode. Later loss or damaged later history cannot erase it.
        val localSegments = source.segments.filter {
            it.startedAt < endedAt && (it.endedAt == null || it.endedAt > startedAt)
        }
        if (localSegments.any { it.type == SessionSegmentType.UNMONITORED || !validSegment(it, source) } ||
            localSegments.map { it.id }.distinct().size != localSegments.size
        ) return false
        val intervals = localSegments.sortedWith(compareBy<SessionSegmentEntity> { it.startedAt }.thenBy { it.id })
            .map { SegmentInterval(it.type, maxOf(it.startedAt, startedAt), minOf(requireNotNull(it.endedAt), endedAt)) }
        return try {
            recoveryTimelinePolicy.requireValid(startedAt, endedAt, intervals)
            true
        } catch (_: IllegalArgumentException) { false }
    }

    private fun validSegment(segment: SessionSegmentEntity, source: SessionTrendSource): Boolean {
        val endedAt = segment.endedAt ?: return false
        return segment.sessionId == source.session.id && segment.activeSlot == null &&
            segment.startedAt >= source.session.startedAt && endedAt <= requireNotNull(source.session.endedAt) &&
            endedAt > segment.startedAt && elapsed(segment.startedAt, endedAt) != null
    }

    fun finish() = TrendsPeriod(
        start = StartTrends(converted, abandoned, timeout, open,
            fraction(converted, Math.addExact(Math.addExact(converted, abandoned), timeout)),
            startLatencies.size.toLong(), median(startLatencies), startDataIssues,
            stableConfirmed, stableUnconfirmed, stableDataIssues, median(stableLatencies)),
        maintain = MaintainTrends(endedSessions, trustedSessions, unavailableSessions,
            effectiveFocus.value.takeIf { trustedSessions > 0 },
            deepFocus.value.takeIf { trustedSessions > 0 }, distractions,
            distractionDuration.value.takeIf { trustedSessions > 0 },
            effectiveFocus.overflow || deepFocus.overflow || distractionDuration.overflow),
        recover = RecoverTrends(recoveryAttempts, recoverySuccesses, recoveryInterruptions, recoveryUnknown,
            plannedReturns, invalidOrigins, fraction(recoverySuccesses, Math.addExact(recoverySuccesses, recoveryInterruptions)),
            median(recoveryLatencies)),
    )

    fun finishReading(): ReadingDurationTrends = reading.finish()
}

private class ReadingDurationAccumulator {
    private var qualifiedSessions = 0L
    private var dataIssues = 0L
    private var individualOverflow = false
    private val duration = MillisSum()

    fun add(session: StudySessionEntity) {
        if (session.endType != null && session.endType != SessionEndType.NORMAL) return
        val endedAt = requireNotNull(session.endedAt)
        // Same normal/end-page/positive-elapsed qualification as the frozen Phase 2 reading summary.
        // Snapshot cutoff, active-slot and pending-closeout filtering belong to the outer cohort scan.
        if (session.endType != SessionEndType.NORMAL || session.endPage == null || endedAt <= session.startedAt) {
            dataIssues++
            return
        }
        val millis = elapsed(session.startedAt, endedAt)
        if (millis == null) {
            dataIssues++
            individualOverflow = true
            return
        }
        qualifiedSessions++
        duration.add(millis)
    }

    fun finish() = ReadingDurationTrends(
        sessionCount = qualifiedSessions,
        totalDurationMillis = duration.value.takeUnless { individualOverflow || (qualifiedSessions == 0L && dataIssues > 0L) },
        dataIssueCount = dataIssues,
        durationOverflow = individualOverflow || duration.overflow,
    )
}

private fun elapsed(start: Long, end: Long): Long? {
    if (end < start) return null
    return try { Math.subtractExact(end, start) } catch (_: ArithmeticException) { null }
}

private fun fraction(numerator: Long, denominator: Long) =
    TrendFraction(numerator, denominator, if (denominator == 0L) null else numerator.toDouble() / denominator.toDouble())

private fun median(samples: MutableList<Long>): Double? {
    if (samples.isEmpty()) return null
    samples.sort()
    val middle = samples.size / 2
    if (samples.size % 2 != 0) return samples[middle].toDouble()
    val lower = samples[middle - 1]
    val upper = samples[middle]
    return lower.toDouble() + (upper - lower).toDouble() / 2.0
}

private class MillisSum {
    var value: Long? = 0L
        private set
    var overflow: Boolean = false
        private set

    fun add(millis: Long) {
        val previous = value ?: return
        value = try { Math.addExact(previous, millis) } catch (_: ArithmeticException) {
            overflow = true
            null
        }
    }
}
