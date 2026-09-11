package com.guanyi.mirra.data.repository

import android.net.Uri
import androidx.room.withTransaction
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.ImageAssetEntity
import com.guanyi.mirra.data.local.model.ImageListItem
import com.guanyi.mirra.data.storage.CameraTarget
import com.guanyi.mirra.data.storage.CleanupReport
import com.guanyi.mirra.data.storage.ImageImportPolicy
import com.guanyi.mirra.data.storage.ImageStorageService
import com.guanyi.mirra.data.storage.StoredImage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class ImportBatchResult(
    val added: List<ImageAssetEntity>,
    val failedCount: Int,
    val rejectedCount: Int,
)

interface ImageRepository {
    fun observeForNote(noteId: String): Flow<List<ImageAssetEntity>>
    fun observeAll(): Flow<List<ImageListItem>>
    suspend fun get(imageId: String): ImageAssetEntity?
    suspend fun importFromGallery(noteId: String, uris: List<Uri>): ImportBatchResult
    suspend fun createCameraTarget(): CameraTarget
    suspend fun completeCameraImport(noteId: String, target: CameraTarget): ImageAssetEntity
    suspend fun cancelCameraTarget(target: CameraTarget)
    suspend fun updateCaption(imageId: String, caption: String?)
    suspend fun deleteImage(imageId: String)
    suspend fun reconcileStorage(): CleanupReport
    fun displayFile(localPath: String): File
}

class DefaultImageRepository(
    private val database: MirraDatabase,
    private val storage: ImageStorageService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ImageRepository {
    private val dao = database.imageAssetDao()

    override fun observeForNote(noteId: String) = dao.observeForNote(noteId)
    override fun observeAll() = dao.observeAllWithSource()
    override suspend fun get(imageId: String) = dao.get(imageId)

    override suspend fun importFromGallery(noteId: String, uris: List<Uri>): ImportBatchResult {
        val limited = ImageImportPolicy.limitSelection(uris)
        val added = mutableListOf<ImageAssetEntity>()
        var failures = 0
        limited.accepted.forEach { uri ->
            try {
                added += importOne(noteId) { storage.importUri(uri) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failures += 1
            }
        }
        return ImportBatchResult(added, failures, limited.rejectedCount)
    }

    override suspend fun createCameraTarget() = storage.createCameraTarget()

    override suspend fun completeCameraImport(noteId: String, target: CameraTarget): ImageAssetEntity =
        importOne(noteId) { storage.importCameraTarget(target) }

    override suspend fun cancelCameraTarget(target: CameraTarget) = storage.discardCameraTarget(target)

    override suspend fun updateCaption(imageId: String, caption: String?) {
        val normalized = caption?.trim()?.takeIf(String::isNotEmpty)
        database.withTransaction {
            checkNotNull(dao.get(imageId)) { "图片不存在" }
            check(dao.updateCaption(imageId, normalized) == 1) { "图片已被删除" }
        }
    }

    override suspend fun deleteImage(imageId: String) {
        val image = checkNotNull(dao.get(imageId)) { "图片不存在" }
        val trash = storage.moveToTrash(image.localPath)
        try {
            database.withTransaction {
                checkNotNull(dao.get(imageId)) { "图片已被删除" }
                check(dao.delete(imageId) == 1) { "图片已被删除" }
            }
        } catch (failure: Throwable) {
            if (trash != null) withContext(NonCancellable) { runCatching { storage.restoreFromTrash(trash) } }
            throw failure
        }
        if (trash != null) runCatching { storage.purgeTrash(trash) }
    }

    override suspend fun reconcileStorage(): CleanupReport =
        storage.cleanup(dao.listAllPaths().toSet(), clock())

    override fun displayFile(localPath: String) = storage.resolveFinal(localPath)

    private suspend fun importOne(
        noteId: String,
        createFile: suspend () -> StoredImage,
    ): ImageAssetEntity {
        val stored = createFile()
        val image = ImageAssetEntity(
            id = newId(),
            noteId = noteId,
            localPath = stored.localPath,
            caption = null,
            width = stored.width,
            height = stored.height,
            fileSize = stored.fileSize,
            createdAt = clock(),
        )
        try {
            database.withTransaction {
                checkNotNull(database.noteDao().get(noteId)) { "Note 不存在" }
                dao.insert(image)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { storage.deleteFinal(stored.localPath) } }
            throw failure
        }
        return image
    }
}
