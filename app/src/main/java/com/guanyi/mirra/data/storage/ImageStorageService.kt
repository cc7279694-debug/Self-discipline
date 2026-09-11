package com.guanyi.mirra.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StoredImage(
    val localPath: String,
    val width: Int,
    val height: Int,
    val fileSize: Long,
)

data class CameraTarget(
    val tempPath: String,
    val uri: Uri,
)

data class TrashedFile(
    val originalPath: String,
    val trashPath: String,
)

data class CleanupReport(
    val restored: Int = 0,
    val deletedTemp: Int = 0,
    val deletedTrash: Int = 0,
    val deletedOrphans: Int = 0,
    val failures: Int = 0,
)

interface ImageStorageService {
    suspend fun importUri(source: Uri): StoredImage
    suspend fun createCameraTarget(): CameraTarget
    suspend fun importCameraTarget(target: CameraTarget): StoredImage
    suspend fun discardCameraTarget(target: CameraTarget)
    suspend fun moveToTrash(localPath: String): TrashedFile?
    suspend fun restoreFromTrash(file: TrashedFile)
    suspend fun purgeTrash(file: TrashedFile)
    suspend fun deleteFinal(localPath: String)
    suspend fun cleanup(referencedPaths: Set<String>, now: Long): CleanupReport
    fun resolveFinal(localPath: String): File
}

class DefaultImageStorageService(
    context: Context,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ImageStorageService {
    private val appContext = context.applicationContext
    private val filesRoot = appContext.filesDir
    private val imagesDir = filesRoot.resolve("images")
    private val workDir = filesRoot.resolve("image-work")
    private val importDir = workDir.resolve("import")
    private val cameraDir = workDir.resolve("camera")
    private val trashDir = workDir.resolve("trash")

    init {
        listOf(imagesDir, importDir, cameraDir, trashDir).forEach(File::mkdirs)
    }

    override suspend fun importUri(source: Uri): StoredImage = withContext(Dispatchers.IO) {
        val temp = importDir.resolve("${newId()}.source")
        try {
            appContext.contentResolver.openInputStream(source).use { input ->
                checkNotNull(input) { "无法读取图片" }
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            processTemp(temp)
        } finally {
            temp.delete()
        }
    }

    override suspend fun createCameraTarget(): CameraTarget = withContext(Dispatchers.IO) {
        val file = cameraDir.resolve("${newId()}.jpg")
        if (!file.createNewFile()) error("无法创建相机临时文件")
        CameraTarget(
            tempPath = "image-work/camera/${file.name}",
            uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file),
        )
    }

    override suspend fun importCameraTarget(target: CameraTarget): StoredImage = withContext(Dispatchers.IO) {
        val file = resolveCamera(target.tempPath)
        try {
            require(file.isFile && file.length() > 0L) { "相机未生成有效图片" }
            processTemp(file)
        } finally {
            file.delete()
        }
    }

    override suspend fun discardCameraTarget(target: CameraTarget) = withContext(Dispatchers.IO) {
        resolveCamera(target.tempPath).delete()
        Unit
    }

    override suspend fun moveToTrash(localPath: String): TrashedFile? = withContext(Dispatchers.IO) {
        val source = resolveFinal(localPath)
        if (!source.exists()) return@withContext null
        val target = trashDir.resolve(source.name)
        if (target.exists()) throw IOException("图片回收区存在冲突")
        move(source, target)
        TrashedFile(localPath, "image-work/trash/${target.name}")
    }

    override suspend fun restoreFromTrash(file: TrashedFile) = withContext(Dispatchers.IO) {
        val original = resolveFinal(file.originalPath)
        val trash = resolveTrash(file.trashPath)
        when {
            original.exists() && trash.exists() -> {
                if (!trash.delete()) throw IOException("无法清理重复回收文件")
            }
            original.exists() -> Unit
            trash.exists() -> move(trash, original)
            else -> throw IOException("待恢复图片不存在")
        }
    }

    override suspend fun purgeTrash(file: TrashedFile) = withContext(Dispatchers.IO) {
        val trash = resolveTrash(file.trashPath)
        if (trash.exists() && !trash.delete()) throw IOException("无法清理回收文件")
    }

    override suspend fun deleteFinal(localPath: String) = withContext(Dispatchers.IO) {
        val file = resolveFinal(localPath)
        if (file.exists() && !file.delete()) throw IOException("无法清理图片文件")
    }

    override suspend fun cleanup(referencedPaths: Set<String>, now: Long): CleanupReport = withContext(Dispatchers.IO) {
        val safeReferences = referencedPaths.filter(ManagedImagePath::isValid).toSet()
        var restored = 0
        var deletedTemp = 0
        var deletedTrash = 0
        var deletedOrphans = 0
        var failures = 0

        trashDir.listFiles().orEmpty().filter(File::isFile).forEach { trash ->
            val originalPath = "images/${trash.name}"
            if (!ManagedImagePath.isValid(originalPath)) return@forEach
            try {
                val original = imagesDir.resolve(trash.name)
                if (originalPath in safeReferences && !original.exists()) {
                    move(trash, original)
                    restored += 1
                } else if (isExpired(trash, now) && trash.delete()) {
                    deletedTrash += 1
                }
            } catch (_: Throwable) {
                failures += 1
            }
        }

        listOf(importDir, cameraDir).forEach { directory ->
            directory.listFiles().orEmpty().filter { it.isFile && isExpired(it, now) }.forEach {
                if (it.delete()) deletedTemp += 1 else failures += 1
            }
        }

        imagesDir.listFiles().orEmpty().filter(File::isFile).forEach { image ->
            val path = "images/${image.name}"
            if (ManagedImagePath.isValid(path) && path !in safeReferences && isExpired(image, now)) {
                if (image.delete()) deletedOrphans += 1 else failures += 1
            }
        }
        CleanupReport(restored, deletedTemp, deletedTrash, deletedOrphans, failures)
    }

    override fun resolveFinal(localPath: String): File {
        require(ManagedImagePath.isValid(localPath)) { "图片路径无效" }
        val file = filesRoot.resolve(localPath).canonicalFile
        require(file.parentFile == imagesDir.canonicalFile) { "图片路径越界" }
        return file
    }

    private fun processTemp(temp: File): StoredImage {
        require(temp.isFile && temp.length() > 0L) { "图片为空" }
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeFile(temp.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType?.startsWith("image/") == true) {
            "图片无法解码"
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageImportPolicy.sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = checkNotNull(BitmapFactory.decodeFile(temp.path, options)) { "图片无法解码" }
        val oriented = applyOrientation(decoded, readOrientation(temp))
        if (oriented !== decoded) decoded.recycle()
        val (targetWidth, targetHeight) = ImageImportPolicy.targetSize(oriented.width, oriented.height)
        val resized = if (targetWidth != oriented.width || targetHeight != oriented.height) {
            Bitmap.createScaledBitmap(oriented, targetWidth, targetHeight, true).also { oriented.recycle() }
        } else {
            oriented
        }
        val flattened = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(resized, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (flattened !== resized) resized.recycle()

        val id = newId()
        val part = importDir.resolve("$id.jpg.part")
        val finalFile = imagesDir.resolve("$id.jpg")
        var completed = false
        var movedToFinal = false
        try {
            FileOutputStream(part).use { output ->
                check(flattened.compress(Bitmap.CompressFormat.JPEG, ImageImportPolicy.JPEG_QUALITY, output)) {
                    "图片压缩失败"
                }
                output.fd.sync()
            }
            require(part.length() > 0L) { "图片压缩失败" }
            move(part, finalFile)
            movedToFinal = true
            val verify = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
            BitmapFactory.decodeFile(finalFile.path, verify)
            if (verify.outWidth != targetWidth || verify.outHeight != targetHeight) {
                error("图片写入校验失败")
            }
            completed = true
            return StoredImage("images/${finalFile.name}", targetWidth, targetHeight, finalFile.length())
        } finally {
            flattened.recycle()
            part.delete()
            if (!completed && movedToFinal) finalFile.delete()
        }
    }

    private fun readOrientation(file: File): Int = runCatching {
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun resolveCamera(path: String): File {
        require(Regex("^image-work/camera/[0-9a-fA-F-]{36}\\.jpg$").matches(path)) { "相机临时路径无效" }
        val file = filesRoot.resolve(path).canonicalFile
        require(file.parentFile == cameraDir.canonicalFile) { "相机临时路径越界" }
        return file
    }

    private fun resolveTrash(path: String): File {
        require(Regex("^image-work/trash/[0-9a-fA-F-]{36}\\.jpg$").matches(path)) { "回收路径无效" }
        val file = filesRoot.resolve(path).canonicalFile
        require(file.parentFile == trashDir.canonicalFile) { "回收路径越界" }
        return file
    }

    private fun move(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) throw IOException("目标图片文件已存在")
        if (!source.renameTo(target)) throw IOException("图片文件移动失败")
    }

    private fun isExpired(file: File, now: Long): Boolean = now - file.lastModified() >= CLEANUP_AGE_MILLIS

    companion object {
        const val CLEANUP_AGE_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
