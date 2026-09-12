package com.guanyi.mirra.domain

import java.text.Normalizer
import java.util.Locale

object TopicNameNormalizer {
    fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("[\\s\\p{Z}]+"), " ")

    fun comparisonKey(raw: String): String = normalize(raw).lowercase(Locale.ROOT)
}
