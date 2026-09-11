package com.guanyi.mirra.data.storage

import kotlin.math.roundToInt

data class LimitedSelection<T>(
    val accepted: List<T>,
    val rejectedCount: Int,
)

object ImageImportPolicy {
    const val MAX_SELECTION = 20
    const val MAX_EDGE = 2_560
    const val JPEG_QUALITY = 88

    fun <T> limitSelection(items: List<T>): LimitedSelection<T> = LimitedSelection(
        accepted = items.take(MAX_SELECTION),
        rejectedCount = (items.size - MAX_SELECTION).coerceAtLeast(0),
    )

    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "图片尺寸无效" }
        val longest = maxOf(width, height)
        if (longest <= MAX_EDGE) return width to height
        val ratio = MAX_EDGE.toDouble() / longest
        return (width * ratio).roundToInt().coerceAtLeast(1) to
            (height * ratio).roundToInt().coerceAtLeast(1)
    }

    fun sampleSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "图片尺寸无效" }
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= MAX_EDGE) sample *= 2
        return sample
    }
}

object ManagedImagePath {
    private val pattern = Regex(
        "^images/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jpg$",
    )

    fun isValid(path: String): Boolean = pattern.matches(path)
}
