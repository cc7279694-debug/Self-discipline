package com.guanyi.mirra.domain.insights

import com.guanyi.mirra.data.local.model.EffectiveReadingSource
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.EffectiveReadingService
import java.math.BigInteger
import java.time.DateTimeException
import java.time.Instant

/** Read-only comparison; frozen effective qualification owns monitoring and timeline eligibility. */
class ReadingPaceInsightService(private val effectiveReading: EffectiveReadingService = EffectiveReadingService()) {
    fun analyze(itemId: String, source: EffectiveReadingSource, time: AnalyticsTimeContext): ReadingPaceInsightResult = guarded {
        val today = time.now.atZone(time.zoneId).toLocalDate()
        val start = today.minusDays(89)
        val sessions = source.sessions.filter { session ->
            val endedAt = session.endedAt ?: return@filter false
            val ended = Instant.ofEpochMilli(endedAt)
            session.learningItemId == itemId && ended <= time.now && ended.atZone(time.zoneId).toLocalDate() in start..today
        }
        val ids = sessions.map { it.id }.toSet()
        val segmentIds = HashSet<String>()
        // Never let associateBy silently collapse corrupt duplicated durable identities.
        if (source.sessions.count { it.id in ids } != ids.size ||
            sessions.map { it.intentId }.toSet().size != sessions.size ||
            source.contexts.any { (key, context) -> context.sessionId in ids && key != context.sessionId } ||
            source.segments.any { (key, segments) -> segments.any { it.sessionId in ids && key != it.sessionId } } ||
            sessions.any { session ->
                source.contexts[session.id]?.sessionId?.let { it != session.id } == true ||
                    source.segments[session.id].orEmpty().any { it.sessionId != session.id || !segmentIds.add(it.id) }
            }
        ) return@guarded unavailable(ReadingPaceInsightUnavailableReason.INVALID_ASSOCIATION)
        val bounded = EffectiveReadingSource(sessions, source.contexts.filterKeys { it in ids }, source.segments.filterKeys { it in ids })
        val endedById = sessions.associate { it.id to requireNotNull(it.endedAt) }
        val qualified = effectiveReading.qualify(bounded, time).map { sample ->
            ReadingPaceSample(sample.sessionId, sample.learningItemId,
                Instant.ofEpochMilli(endedById.getValue(sample.sessionId)), sample.pagesRead, sample.effectiveFocusMillis)
        }
        analyzeQualified(itemId, qualified, time)
    }

    /** Pure arithmetic boundary; the public entry point always runs frozen fact qualification first. */
    internal fun analyzeQualified(itemId: String, samples: List<ReadingPaceSample>, time: AnalyticsTimeContext): ReadingPaceInsightResult = guarded {
        val today = time.now.atZone(time.zoneId).toLocalDate()
        val start = today.minusDays(89)
        val bounded = samples.filter {
            it.learningItemId == itemId && it.endedAt <= time.now && it.endedAt.atZone(time.zoneId).toLocalDate() in start..today
        }
        if (bounded.map { it.sessionId }.toSet().size != bounded.size) {
            return@guarded unavailable(ReadingPaceInsightUnavailableReason.INVALID_ASSOCIATION)
        }
        val ordered = bounded.filter { it.pagesRead > 0 && it.effectiveFocusMillis > 0 }
            .sortedWith(compareByDescending<ReadingPaceSample> { it.endedAt }.thenByDescending { it.sessionId })
        val recent = ordered.take(3)
        if (recent.size != 3) return@guarded unavailable(ReadingPaceInsightUnavailableReason.INSUFFICIENT_RECENT_DATA)
        if (recent.any { it.endedAt.atZone(time.zoneId).toLocalDate() < today.minusDays(29) }) {
            return@guarded unavailable(ReadingPaceInsightUnavailableReason.STALE_RECENT_DATA)
        }
        val recentWindow = aggregate(recent)
        if (recentWindow.totalEffectiveFocusMillis < 1_800_000) {
            return@guarded unavailable(ReadingPaceInsightUnavailableReason.INSUFFICIENT_RECENT_DATA)
        }
        val baseline = ordered.drop(3).take(10)
        if (baseline.size < 5) return@guarded unavailable(ReadingPaceInsightUnavailableReason.INSUFFICIENT_BASELINE)
        val baselineWindow = aggregate(baseline)
        if (baselineWindow.totalEffectiveFocusMillis < 3_600_000) {
            return@guarded unavailable(ReadingPaceInsightUnavailableReason.INSUFFICIENT_BASELINE)
        }
        val recentCross = big(recentWindow.totalPagesRead) * big(baselineWindow.totalEffectiveFocusMillis)
        val baselineCross = big(baselineWindow.totalPagesRead) * big(recentWindow.totalEffectiveFocusMillis)
        val ratio = recentCross.toDouble() / baselineCross.toDouble()
        if (!ratio.isFinite() || ratio <= 0 || baselineWindow.effectivePagesPerHour <= 0) {
            return@guarded unavailable(ReadingPaceInsightUnavailableReason.ARITHMETIC_OUT_OF_RANGE)
        }
        fun individualSide(sample: ReadingPaceSample): Int =
            (big(sample.pagesRead) * big(baselineWindow.totalEffectiveFocusMillis))
                .compareTo(big(baselineWindow.totalPagesRead) * big(sample.effectiveFocusMillis))
        // Exact rational comparisons protect inclusive 0.70/1.30 and strict individual sides.
        val direction = when {
            recentCross * big(10) <= baselineCross * big(7) && recent.all { individualSide(it) < 0 } -> ReadingPaceInsightDirection.SLOWER
            recentCross * big(10) >= baselineCross * big(13) && recent.all { individualSide(it) > 0 } -> ReadingPaceInsightDirection.FASTER
            else -> return@guarded unavailable(ReadingPaceInsightUnavailableReason.NO_NOTABLE_CHANGE)
        }
        val change = (recentCross - baselineCross).abs()
        val exceedsHundred = change > baselineCross
        // Round the exact relative change to 5% units, half up; >100% uses a capped presentation value.
        val roundedPercent = if (exceedsHundred) 100 else
            ((change * big(40) + baselineCross) / (baselineCross * big(2))).toInt() * 5
        ReadingPaceInsightResult(ReadingPaceInsight(direction, ratio, recentWindow, baselineWindow,
            roundedPercent, exceedsHundred), null)
    }

    private fun aggregate(samples: List<ReadingPaceSample>): ReadingPaceWindowEvidence {
        val pages = samples.fold(0L) { sum, sample -> Math.addExact(sum, sample.pagesRead) }
        val focus = samples.fold(0L) { sum, sample -> Math.addExact(sum, sample.effectiveFocusMillis) }
        val speed = pages.toDouble() * 3_600_000.0 / focus.toDouble()
        if (!speed.isFinite() || speed <= 0) throw ArithmeticException("Reading pace out of range")
        return ReadingPaceWindowEvidence(samples.map { it.sessionId }, pages, focus, speed)
    }

    private inline fun guarded(block: () -> ReadingPaceInsightResult): ReadingPaceInsightResult = try {
        block()
    } catch (_: ArithmeticException) {
        unavailable(ReadingPaceInsightUnavailableReason.ARITHMETIC_OUT_OF_RANGE)
    } catch (_: DateTimeException) {
        unavailable(ReadingPaceInsightUnavailableReason.INVALID_TIMESTAMP)
    }

    private fun unavailable(reason: ReadingPaceInsightUnavailableReason) = ReadingPaceInsightResult(null, reason)
    private fun big(value: Long): BigInteger = BigInteger.valueOf(value)
}
