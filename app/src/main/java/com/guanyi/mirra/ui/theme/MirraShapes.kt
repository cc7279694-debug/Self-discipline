package com.guanyi.mirra.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

@Immutable
data class MirraShapes(
    val small: CornerBasedShape = RoundedCornerShape(14.dp),
    val medium: CornerBasedShape = RoundedCornerShape(20.dp),
    val large: CornerBasedShape = RoundedCornerShape(28.dp),
    val extraLarge: CornerBasedShape = RoundedCornerShape(32.dp),
    val pill: CornerBasedShape = RoundedCornerShape(50),
)
