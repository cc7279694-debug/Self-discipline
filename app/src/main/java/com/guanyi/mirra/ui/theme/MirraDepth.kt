package com.guanyi.mirra.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class MirraDepth(
    val flat: Dp = 0.dp,
    val subtle: Dp = 2.dp,
    val raised: Dp = 8.dp,
    val floating: Dp = 12.dp,
    val pressed: Dp = 1.dp,
)
