package com.guanyi.mirra.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.storage.DefaultImageStorageService
import com.guanyi.mirra.data.storage.ManagedImagePath
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageStorageServiceTest {
    private lateinit var context: Context
    private lateinit var service: DefaultImageStorageService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearManagedFiles()
        service = DefaultImageStorageService(context)
    }

    @After
    fun tearDown() = clearManagedFiles()

    @Test
    fun cameraImageIsRotatedCompressedAndMovedToPrivateRelativePath() = runTest {
        val target = service.createCameraTarget()
        val source = context.filesDir.resolve(target.tempPath)
        writeJpeg(source, 4_000, 2_000)
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val stored = service.importCameraTarget(target)

        assertTrue(ManagedImagePath.isValid(stored.localPath))
        assertEquals(1_280, stored.width)
        assertEquals(2_560, stored.height)
        assertTrue(service.resolveFinal(stored.localPath).length() > 0)
        assertFalse(source.exists())
    }

    @Test
    fun galleryUriIsCopiedAndDoesNotBecomePersistedPath() = runTest {
        val target = service.createCameraTarget()
        val source = context.filesDir.resolve(target.tempPath)
        writeJpeg(source, 320, 240)

        val stored = service.importUri(target.uri)

        assertTrue(stored.localPath.startsWith("images/"))
        assertFalse(stored.localPath.contains("content:"))
        service.discardCameraTarget(target)
    }

    @Test
    fun generatedPathCollisionNeverOverwritesExistingImage() = runTest {
        val ids = ArrayDeque(
            listOf(
                "123e4567-e89b-12d3-a456-426614174010",
                "123e4567-e89b-12d3-a456-426614174011",
                "123e4567-e89b-12d3-a456-426614174012",
                "123e4567-e89b-12d3-a456-426614174011",
            ),
        )
        val collisionService = DefaultImageStorageService(context) { ids.removeFirst() }
        val firstTarget = collisionService.createCameraTarget()
        writeJpeg(context.filesDir.resolve(firstTarget.tempPath), 120, 80)
        val first = collisionService.importCameraTarget(firstTarget)
        val firstBytes = collisionService.resolveFinal(first.localPath).readBytes()

        val secondTarget = collisionService.createCameraTarget()
        writeJpeg(context.filesDir.resolve(secondTarget.tempPath), 80, 120)
        assertFails { collisionService.importCameraTarget(secondTarget) }

        assertArrayEquals(firstBytes, collisionService.resolveFinal(first.localPath).readBytes())
    }

    @Test
    fun allExifOrientationsProduceExpectedFinalDimensions() = runTest {
        val orientations = listOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )
        orientations.forEach { orientation ->
            val target = service.createCameraTarget()
            val source = context.filesDir.resolve(target.tempPath)
            writeJpeg(source, 120, 80)
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }

            val stored = service.importCameraTarget(target)
            val swapsAxes = orientation in listOf(
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_TRANSVERSE,
                ExifInterface.ORIENTATION_ROTATE_270,
            )
            assertEquals(if (swapsAxes) 80 else 120, stored.width)
            assertEquals(if (swapsAxes) 120 else 80, stored.height)
        }
    }

    @Test
    fun pngAndWebpInputsAreNormalizedToJpeg() = runTest {
        listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP_LOSSLESS).forEach { format ->
            val target = service.createCameraTarget()
            val source = context.filesDir.resolve(target.tempPath)
            val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
            source.outputStream().use { bitmap.compress(format, 90, it) }
            bitmap.recycle()

            val stored = service.importCameraTarget(target)
            val bytes = service.resolveFinal(stored.localPath).inputStream().use { input -> ByteArray(2).also { input.read(it) } }
            assertEquals(0xFF, bytes[0].toInt() and 0xFF)
            assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        }
    }

    @Test
    fun emptyOrCorruptCameraTempFailsAndIsRemoved() = runTest {
        val empty = service.createCameraTarget()
        assertFails { service.importCameraTarget(empty) }
        assertFalse(context.filesDir.resolve(empty.tempPath).exists())

        val corrupt = service.createCameraTarget()
        context.filesDir.resolve(corrupt.tempPath).writeBytes(byteArrayOf(1, 2, 3))
        assertFails { service.importCameraTarget(corrupt) }
        assertFalse(context.filesDir.resolve(corrupt.tempPath).exists())
    }

    @Test
    fun cancelCameraDeletesUnusedTemp() = runTest {
        val target = service.createCameraTarget()
        assertTrue(context.filesDir.resolve(target.tempPath).exists())

        service.discardCameraTarget(target)

        assertFalse(context.filesDir.resolve(target.tempPath).exists())
    }

    @Test
    fun cleanupRestoresReferencedTrashAndDeletesOnlyExpiredUnreferencedFiles() = runTest {
        val now = 100_000_000L
        val age = DefaultImageStorageService.CLEANUP_AGE_MILLIS + 1
        val referenced = "images/123e4567-e89b-12d3-a456-426614174000.jpg"
        val orphan = "images/123e4567-e89b-12d3-a456-426614174001.jpg"
        val fresh = "images/123e4567-e89b-12d3-a456-426614174002.jpg"
        val trash = context.filesDir.resolve("image-work/trash/${referenced.substringAfterLast('/')}").apply {
            parentFile?.mkdirs(); writeBytes(byteArrayOf(1)); setLastModified(now - age)
        }
        val deletedTrash = context.filesDir.resolve("image-work/trash/123e4567-e89b-12d3-a456-426614174099.jpg").apply {
            writeBytes(byteArrayOf(9)); setLastModified(now - age)
        }
        val unknownTrash = context.filesDir.resolve("image-work/trash/not-managed.bin").apply {
            writeBytes(byteArrayOf(8)); setLastModified(now - age)
        }
        val orphanFile = service.resolveFinal(orphan).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(2)); setLastModified(now - age) }
        val freshFile = service.resolveFinal(fresh).apply { writeBytes(byteArrayOf(3)); setLastModified(now) }
        val temp = context.filesDir.resolve("image-work/import/old.source").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(4)); setLastModified(now - age) }

        val report = service.cleanup(setOf(referenced), now)

        assertTrue(service.resolveFinal(referenced).exists())
        assertFalse(trash.exists())
        assertFalse(deletedTrash.exists())
        assertTrue(unknownTrash.exists())
        assertFalse(orphanFile.exists())
        assertTrue(freshFile.exists())
        assertFalse(temp.exists())
        assertEquals(1, report.restored)
        assertEquals(1, report.deletedOrphans)
        assertEquals(1, report.deletedTemp)
        assertEquals(1, report.deletedTrash)
    }

    private fun writeJpeg(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).apply {
            eraseColor(Color.WHITE)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected failure")
        } catch (failure: AssertionError) {
            throw failure
        } catch (_: Throwable) {
            // Expected.
        }
    }

    private fun clearManagedFiles() {
        context.filesDir.resolve("images").deleteRecursively()
        context.filesDir.resolve("image-work").deleteRecursively()
    }
}
