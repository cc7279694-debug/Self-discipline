package com.guanyi.mirra.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "image_assets",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("noteId"),
        Index(value = ["localPath"], unique = true),
    ],
)
data class ImageAssetEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val localPath: String,
    val caption: String?,
    val width: Int,
    val height: Int,
    val fileSize: Long,
    val createdAt: Long,
)
