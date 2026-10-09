package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.LearningItemEntity

object FirstActionResolver {
    fun resolve(item: LearningItemEntity): String {
        val stored = item.firstAction.trim()
        return stored.takeIf { validationError(it) == null && !isLegacyGenerated(it) }.orEmpty()
    }

    fun needsConfirmation(item: LearningItemEntity): Boolean = resolve(item).isEmpty()

    /** Existing v4 Intents freeze the item's action through the active-workflow mutation guard. */
    fun forActiveIntent(item: LearningItemEntity): String {
        val stored = item.firstAction.trim()
        return if (stored.isEmpty() || isLegacyGenerated(stored)) defaultFor(item.name, item.currentPage) else stored
    }

    /** A suggestion is not a choice: callers must wait for the user to explicitly select it. */
    fun suggestionFor(currentPage: Int): String = "翻到第 $currentPage 页，读这一页的第一段。"

    fun defaultFor(name: String, currentPage: Int): String =
        "拿起《${name.trim()}》，翻到第 $currentPage 页。"

    fun requireChosen(action: String?): String {
        val clean = action?.trim().orEmpty()
        require(validationError(clean) == null) { checkNotNull(validationError(clean)) }
        require(!isLegacyGenerated(clean)) { "请明确选择建议动作，或填写现在就能做的第一步" }
        return clean
    }

    fun validationError(action: String): String? {
        val clean = action.trim()
        if (clean.isEmpty() || clean.none { !it.isWhitespace() && Character.getType(it) != Character.FORMAT.toInt() }) {
            return "请填写或选择一个第一步动作"
        }
        if (clean.length > 160) return "请把第一步写成 160 字以内的简单动作"
        if (clean.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) {
            return "动作中不能包含不可见或控制字符"
        }
        if (clean.none(Char::isLetter)) return "请写出现在就能做的一步，不能只填数字或符号"
        if (isLegacyGenerated(clean)) return "请明确选择建议动作，或填写现在就能做的第一步"
        val compact = clean.replace(Regex("[\\s。.!！?？]"), "")
        if (obviousGoal.matches(compact)) return "这是学习目标，请改成现在就能做的一步，例如翻到当前页，读第一段"
        return null
    }

    private fun isLegacyGenerated(action: String): Boolean =
        Regex("^拿起《[^》]+》，翻到第\\s*\\d+\\s*页。?$").matches(action)

    // A bounded guard for obvious goal statements, not semantic proof of every free-text action.
    private val obviousGoal = Regex(
        "^(?:(?:我(?:要|想要|想)?|今天(?:要)?|本次(?:要)?|希望|计划))?" +
            "(?:完成(?:这|此|整|一)?本书|读完(?:这|此|整|一)?本书|学完(?:这|此|整|一)?本书|" +
            "(?:读完|学完)《[^》]+》|" +
            "(?:读|阅读|读完|看|看完)(?:\\d+(?:\\.\\d+)?|[一二三四五六七八九十百千万半两]+)(?:页|分钟|小时|分|个章节|章|节)|" +
            "(?:提高|提升)(?:阅读|学习|记忆|理解|专注)(?:能力|水平|效率|成绩|速度)|" +
            "提升自己|学会.*|掌握.*|坚持阅读.*|好好学习.*|认真学习.*|开始学习|开始阅读|学习|阅读|读书)$",
    )
}
