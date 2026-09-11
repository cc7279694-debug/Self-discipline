package com.guanyi.mirra.data.local.model

import androidx.room.Embedded
import com.guanyi.mirra.data.local.entity.NoteEntity

data class NoteListItem(
    @Embedded val note: NoteEntity,
    val learningItemName: String,
)
