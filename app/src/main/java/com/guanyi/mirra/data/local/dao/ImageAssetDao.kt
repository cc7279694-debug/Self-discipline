package com.guanyi.mirra.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.guanyi.mirra.data.local.entity.ImageAssetEntity
import com.guanyi.mirra.data.local.model.ImageListItem
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageAssetDao {
    @Insert
    suspend fun insert(image: ImageAssetEntity)

    @Query("SELECT * FROM image_assets WHERE id = :imageId")
    suspend fun get(imageId: String): ImageAssetEntity?

    @Query("SELECT * FROM image_assets WHERE noteId = :noteId ORDER BY createdAt, id")
    fun observeForNote(noteId: String): Flow<List<ImageAssetEntity>>

    @Query("SELECT * FROM image_assets WHERE noteId = :noteId ORDER BY createdAt, id")
    suspend fun listForNote(noteId: String): List<ImageAssetEntity>

    @Query("SELECT localPath FROM image_assets")
    suspend fun listAllPaths(): List<String>

    @Query("SELECT * FROM image_assets ORDER BY createdAt, id")
    suspend fun listAll(): List<ImageAssetEntity>

    @Query(
        """
        SELECT image_assets.*, notes.pageNumber AS notePageNumber,
               notes.learningItemId AS learningItemId, learning_items.name AS learningItemName
        FROM image_assets
        INNER JOIN notes ON notes.id = image_assets.noteId
        INNER JOIN learning_items ON learning_items.id = notes.learningItemId
        ORDER BY image_assets.createdAt DESC, image_assets.id DESC
        """,
    )
    fun observeAllWithSource(): Flow<List<ImageListItem>>

    @Query("UPDATE image_assets SET caption = :caption WHERE id = :imageId")
    suspend fun updateCaption(imageId: String, caption: String?): Int

    @Query("DELETE FROM image_assets WHERE id = :imageId")
    suspend fun delete(imageId: String): Int

}
