package com.guanyi.mirra.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.repository.ReadingAnalyticsRepository
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.AnalyticsTimeProvider
import com.guanyi.mirra.domain.ReadingAnalyticsService
import com.guanyi.mirra.feature.knowledge.formatDuration
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ProfileUiState(
    val sessionCount: Int = 0,
    val totalDurationText: String = "0 分钟",
    val pagesRead: Long = 0,
    val noteCount: Int = 0,
    val comparisonText: String? = null,
    val isEmpty: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileViewModel(
    private val repository: ReadingAnalyticsRepository,
    private val analyticsService: ReadingAnalyticsService,
    private val timeProvider: AnalyticsTimeProvider,
) : ViewModel() {
    private val timeContext = MutableStateFlow(timeProvider.snapshot())

    val uiState = timeContext.flatMapLatest { time ->
        val boundaries = boundaries(time)
        repository.observeFourteenDaySource(
            fromInclusive = boundaries.previousStart,
            currentPeriodStart = boundaries.currentStart,
            toExclusive = boundaries.toExclusive,
        ).map { source ->
            val comparison = analyticsService.buildSevenDayComparison(
                source.sessions,
                source.currentNoteCount,
                source.previousNoteCount,
                time,
            )
            val current = comparison.current
            ProfileUiState(
                sessionCount = current.sessionCount,
                totalDurationText = formatDuration(current.totalDuration),
                pagesRead = current.totalPagesRead,
                noteCount = current.noteCount,
                comparisonText = comparisonText(current.totalDuration, comparison.previous.totalDuration),
                isEmpty = current.sessionCount == 0,
            )
        }
    }.catch {
        emit(ProfileUiState(isEmpty = true, error = "暂时无法读取阅读摘要"))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState(isLoading = true))

    fun refreshTimeContext() {
        timeContext.value = timeProvider.snapshot()
    }

    private fun boundaries(time: AnalyticsTimeContext): PeriodBoundaries {
        val today = time.now.atZone(time.zoneId).toLocalDate()
        val previousStart = today.minusDays(13).atStartOfDay(time.zoneId).toInstant().toEpochMilli()
        val currentStart = today.minusDays(6).atStartOfDay(time.zoneId).toInstant().toEpochMilli()
        val nowMillis = time.now.toEpochMilli()
        return PeriodBoundaries(previousStart, currentStart, if (nowMillis == Long.MAX_VALUE) nowMillis else nowMillis + 1L)
    }

    private fun comparisonText(current: Duration, previous: Duration): String {
        val deltaMinutes = current.minus(previous).toMinutes()
        return when {
            deltaMinutes > 0 -> "阅读时间比前 7 天多 $deltaMinutes 分钟"
            deltaMinutes < 0 -> "阅读时间比前 7 天少 ${-deltaMinutes} 分钟"
            else -> "阅读时间与前 7 天相同"
        }
    }

    private data class PeriodBoundaries(
        val previousStart: Long,
        val currentStart: Long,
        val toExclusive: Long,
    )
}
