package com.guanyi.mirra.feature.profile

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.repository.ReadingAnalyticsRepository
import com.guanyi.mirra.data.repository.TrendsRepository
import com.guanyi.mirra.domain.trends.TrendsRange
import com.guanyi.mirra.domain.trends.TrendsSnapshot
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.AnalyticsTimeProvider
import com.guanyi.mirra.domain.ReadingAnalyticsService
import com.guanyi.mirra.feature.knowledge.formatDuration
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    val sessionCount: Int = 0,
    val totalDurationText: String = "0 分钟",
    val pagesRead: Long = 0,
    val noteCount: Int = 0,
    val comparisonText: String? = null,
    val isEmpty: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val trends: TrendsSnapshot? = null,
    val trendsError: String? = null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileViewModel(
    private val repository: ReadingAnalyticsRepository,
    private val analyticsService: ReadingAnalyticsService,
    private val timeProvider: AnalyticsTimeProvider,
    private val dndActions: DndUserActions? = null,
    private val trendsRepository: TrendsRepository? = null,
) : ViewModel() {
    private data class Refresh(val generation: Long, val time: AnalyticsTimeContext)
    private val timeContext = MutableStateFlow(Refresh(0L, timeProvider.snapshot()))

    val uiState = timeContext.flatMapLatest { request ->
        val time = request.time
        val boundaries = boundaries(time)
        repository.observeFourteenDaySource(
            fromInclusive = boundaries.previousStart,
            currentPeriodStart = boundaries.currentStart,
            toExclusive = boundaries.toExclusive,
        ).mapLatest { source ->
            // Invalidate the chart with the reactive reading facts; never combine a fresh
            // summary with a cached old trend. Navigation/resume also requests a new time.
            val overview = try {
                trendsRepository?.load(TrendsRange.SEVEN_DAYS, time)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
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
                trends = overview,
                trendsError = if (overview == null && trendsRepository != null) "暂时无法读取启动与恢复记录" else null,
            )
        }
    }.catch {
        emit(ProfileUiState(isEmpty = true, error = "暂时无法读取阅读摘要"))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState(isLoading = true))

    val dndState: StateFlow<DndSettingsUiState>? = dndActions?.state

    fun refreshTimeContext() {
        timeContext.value = Refresh(timeContext.value.generation + 1L, timeProvider.snapshot())
    }

    fun refreshDnd() {
        viewModelScope.launch { dndActions?.refresh() }
    }

    fun setDndEnabled(enabled: Boolean) {
        viewModelScope.launch { dndActions?.setEnabled(enabled) }
    }

    fun retryDndApply() {
        viewModelScope.launch { dndActions?.retryApply() }
    }

    fun retryDndRelease() {
        viewModelScope.launch { dndActions?.retryRelease() }
    }

    fun dndSettingsIntent(): Intent? = dndActions?.settingsIntent()

    private fun boundaries(time: AnalyticsTimeContext): PeriodBoundaries {
        val today = time.now.atZone(time.zoneId).toLocalDate()
        val previousStart = today.minusDays(13).atStartOfDay(time.zoneId).toInstant().toEpochMilli()
        val currentStart = today.minusDays(6).atStartOfDay(time.zoneId).toInstant().toEpochMilli()
        val nowMillis = time.now.toEpochMilli()
        return PeriodBoundaries(previousStart, currentStart, if (nowMillis == Long.MAX_VALUE) nowMillis else nowMillis + 1L)
    }

    private fun comparisonText(current: Duration, previous: Duration): String {
        val delta = current.minus(previous)
        val deltaMinutes = delta.toMinutes()
        return when {
            deltaMinutes > 0 -> "阅读时间比前 7 天多 $deltaMinutes 分钟"
            deltaMinutes < 0 -> "阅读时间比前 7 天少 ${-deltaMinutes} 分钟"
            delta.isZero -> "阅读时间与前 7 天相同"
            delta.isNegative -> "阅读时间比前 7 天稍少（不足 1 分钟）"
            else -> "阅读时间比前 7 天稍多（不足 1 分钟）"
        }
    }

    private data class PeriodBoundaries(
        val previousStart: Long,
        val currentStart: Long,
        val toExclusive: Long,
    )
}
