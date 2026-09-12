package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.LearningItemEntity

object FirstActionResolver {
    fun resolve(item: LearningItemEntity): String {
        val stored = item.firstAction.trim()
        return if (stored.isEmpty() || isLegacyGenerated(stored, item.name)) {
            defaultFor(item.name, item.currentPage)
        } else {
            stored
        }
    }

    fun defaultFor(name: String, currentPage: Int): String =
        "拿起《${name.trim()}》，翻到第 $currentPage 页。"

    private fun isLegacyGenerated(action: String, name: String): Boolean =
        Regex("^拿起《${Regex.escape(name.trim())}》，翻到第 \\d+ 页。$").matches(action)
}
