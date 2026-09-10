package com.guanyi.mirra.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
enum class TopLevelDestination(
    val storageValue: String,
    val label: String,
    val compactLabel: String,
) : NavKey {
    Start(storageValue = "start", label = "开始", compactLabel = "始"),
    Knowledge(storageValue = "knowledge", label = "知识", compactLabel = "知"),
    Profile(storageValue = "profile", label = "我的", compactLabel = "我");

    companion object {
        fun fromStorageValue(value: String?): TopLevelDestination =
            entries.firstOrNull { it.storageValue == value } ?: Start
    }
}
