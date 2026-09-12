package com.guanyi.mirra.data.local.model

import androidx.room.Embedded
import com.guanyi.mirra.data.local.entity.TopicEntity

data class TopicListItem(
    @Embedded val topic: TopicEntity,
    val noteCount: Int,
)
