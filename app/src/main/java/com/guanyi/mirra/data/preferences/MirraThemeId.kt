package com.guanyi.mirra.data.preferences

enum class MirraThemeId(val storageValue: String) {
    BLUE("blue"),
    MONO("mono"),
    NIGHT("night");

    companion object {
        fun fromStorageValue(value: String?): MirraThemeId =
            entries.firstOrNull { it.storageValue == value } ?: BLUE
    }
}
