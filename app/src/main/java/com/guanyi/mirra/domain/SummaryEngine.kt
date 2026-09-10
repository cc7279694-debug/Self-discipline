package com.guanyi.mirra.domain

interface SummaryEngine {
    fun create(
        startPage: Int,
        endPage: Int,
        durationMillis: Long,
        noteCount: Int,
    ): String
}

class RuleBasedSummaryEngine : SummaryEngine {
    override fun create(
        startPage: Int,
        endPage: Int,
        durationMillis: Long,
        noteCount: Int,
    ): String {
        val pages = if (startPage == endPage) "本次阅读第 $startPage 页" else "本次阅读 $startPage–$endPage 页"
        val minutes = durationMillis.coerceAtLeast(0L) / 60_000L
        val duration = if (minutes == 0L) "用时不足 1 分钟" else "用时 $minutes 分钟"
        val notes = if (noteCount == 0) "未记录笔记" else "共记录 $noteCount 条笔记"
        return "$pages，$duration，$notes。"
    }
}
