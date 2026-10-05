package com.guanyi.mirra

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.local.model.ReadingRecordSource
import com.guanyi.mirra.data.repository.ReadingRecordRepository
import com.guanyi.mirra.domain.*
import com.guanyi.mirra.feature.profile.*
import com.guanyi.mirra.feature.session.*
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadingRecordUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val service = ReadingRecordService()
    private fun show(record: ReadingRecordProjection) {
        rule.setContent { MirraTheme { var expanded by remember { mutableStateOf(false) }
            Column { ReadingRecordContent(record, expanded, { expanded = !expanded }) } } }
    }
    @Test fun trustedRecordShowsDurationFocusNotesAndCounts() {
        show(service.project(readingRecordTestSource()))
        for (text in listOf("40 → 58 页 · 18 页", "阅读时长：30 分钟", "有效专注时间：20 分钟", "2 条笔记",
            "休息 1 次 · 临时使用 1 次 · 分心 1 次")) rule.onNodeWithText(text).assertExists()
    }
    @Test fun trustedZeroFocusRendersZeroMinutes() {
        show(service.project(readingRecordTestSource().let { it.copy(segments = listOf(it.segments.first().copy(
            type = SessionSegmentType.BREAK, endedAt = it.session.endedAt))) }))
        rule.onNodeWithText("有效专注时间：0 分钟").assertExists()
    }
    @Test fun partialAndNoneUseHonestChineseMessages() {
        val source = readingRecordTestSource()
        var record by mutableStateOf(service.project(source.copy(context = source.context!!.copy(monitoringStatus = MonitoringCoverage.PARTIAL))))
        rule.setContent { MirraTheme { ReadingRecordContent(record, false, {}) } }
        rule.onNodeWithText("本次手机监测不完整，未生成有效专注时间").assertExists()
        rule.onNodeWithText("有效专注时间：0 分钟").assertDoesNotExist()
        rule.runOnIdle { record = service.project(source.copy(context = source.context!!.copy(monitoringStatus = MonitoringCoverage.NONE))) }
        rule.onNodeWithText("未开启手机监测").assertExists()
    }
    @Test fun invalidStructureUsesRecordIncompleteMessage() {
        val source = readingRecordTestSource()
        show(service.project(source.copy(segments = source.segments.drop(1))))
        rule.onNodeWithText("本次记录不完整，未生成有效专注时间").assertExists()
    }
    @Test fun defaultRecordIsCollapsedAndCanShowRealTimelineWithoutInternalTerms() {
        show(service.project(readingRecordTestSource()))
        rule.onNodeWithText("历史浏览器").assertDoesNotExist()
        rule.onNodeWithText("查看本次记录").performClick()
        rule.onAllNodesWithText("阅读").assertCountEquals(2)
        for (text in listOf("休息", "临时使用 · 历史浏览器", "分心 · 历史浏览器", "正在回到学习")) rule.onNodeWithText(text).assertExists()
        for (text in listOf("FULL", "COMPLETE_TRUSTED", "DEEP_FOCUS", "com.test.risk", "评分")) rule.onNodeWithText(text, substring = true).assertDoesNotExist()
    }
    @Test fun missingRecordOffersReturnNotEndAgain() {
        val vm = recordVm(null); var done = false
        try { rule.setContent { MirraTheme { SessionSummaryScreen(vm, NoopDndUserActions(), { done = true }) } }
            rule.waitUntil(5_000) { rule.onAllNodesWithText("记录不存在").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("完成").performScrollTo().performClick(); assertTrue(done)
            rule.onNodeWithText("结束本次阅读").assertDoesNotExist()
        } finally { rule.activityRule.scenario.close(); vm.viewModelScope.cancel() }
    }
    @Test fun dndWarningOnJustSavedResultUsesReleaseRetryOnly() {
        val source = readingRecordTestSource(); val vm = recordVm(source.copy(context = source.context!!.copy(dndLifecycle = DndLifecycle.RELEASE_FAILED)))
        var retries = 0
        var applies = 0; var preferenceChanges = 0
        var history by mutableStateOf(false)
        val actions = object : DndUserActions by NoopDndUserActions() {
            override suspend fun retryRelease() { retries++ }
            override suspend fun retryApply() { applies++ }
            override suspend fun setEnabled(enabled: Boolean) { preferenceChanges++ }
        }
        try { rule.setContent { MirraTheme {
            if (history) com.guanyi.mirra.feature.knowledge.SessionSearchDetailScreen(vm, {})
            else SessionSummaryScreen(vm, actions, {})
        } }
            rule.waitUntil(5_000) { rule.onAllNodesWithText("Mirra 勿扰状态需要处理").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("重试释放勿扰").performScrollTo().performClick()
            rule.waitUntil(5_000) { retries == 1 }
            rule.runOnIdle { history = true }
            rule.onNodeWithText("Mirra 勿扰状态需要处理").assertDoesNotExist()
            rule.onNodeWithText("重试释放勿扰").assertDoesNotExist()
            assertEquals(0, applies); assertEquals(0, preferenceChanges)
        } finally { rule.activityRule.scenario.close(); vm.viewModelScope.cancel() }
    }
}

internal fun recordVm(source: ReadingRecordSource?) = ReadingRecordViewModel("s", object : ReadingRecordRepository {
    override fun observe(sessionId: String) = flowOf(source)
}, ReadingRecordService())

internal fun readingRecordTestSource(): ReadingRecordSource {
    val start = 1_700_000_000_000L
    val types = listOf(SessionSegmentType.FOCUS, SessionSegmentType.BREAK, SessionSegmentType.TEMPORARY_ALLOWANCE,
        SessionSegmentType.DISTRACTION, SessionSegmentType.RECOVERY, SessionSegmentType.DEEP_FOCUS)
    val minutes = listOf(0, 10, 15, 17, 19, 20, 30)
    return ReadingRecordSource(StudySessionEntity("s", "book", "i", start, null, start + 1_800_000,
        40, 58, 58, SessionEndType.NORMAL, "记录测试总结", null),
        SessionFocusContextEntity(sessionId = "s", monitoringStatus = MonitoringCoverage.FULL, monitoringLostAt = null,
            priorDndInterruptionFilter = null, dndRuleId = null, requestedEndPage = null, closeoutStartedAt = null,
            lastHeartbeatAt = start + 1_800_000, createdAt = start, updatedAt = start + 1_800_000),
        types.mapIndexed { i, type -> SessionSegmentEntity("seg-$i", "s", type, start + minutes[i] * 60_000,
            start + minutes[i + 1] * 60_000, "com.test.risk".takeIf { i in 2..3 }, null, null, relatedSegmentId = null, activeSlot = null) },
        listOf(SessionRiskAppSnapshotEntity("s", "com.test.risk", "历史浏览器")), 2)
}
