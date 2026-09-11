package com.guanyi.mirra.data.local.model

import androidx.room.Embedded
import com.guanyi.mirra.data.local.entity.ImageAssetEntity

data class ImageListItem(
    @Embedded val image: ImageAssetEntity,
    val notePageNumber: Int?,
    val learningItemId: String,
    val learningItemName: String,
)
