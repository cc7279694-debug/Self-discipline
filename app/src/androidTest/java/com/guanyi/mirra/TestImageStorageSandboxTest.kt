package com.guanyi.mirra

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.storage.DefaultImageStorageService
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestImageStorageSandboxTest {
    @Test
    fun testContainerCloseNeverDeletesSiblingManagedFiles() = withControlledBase { context ->
        val bytes = byteArrayOf(7, 11, 19)
        val sentinel = context.filesDir.resolve("images/sentinel.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }
        val container = TestAppContainer(context)
        val ownedFile = container.imageRepository.displayFile(IMAGE_PATH).apply { writeBytes(byteArrayOf(23)) }
        container.close()

        assertTrue("Closing a test container must retain sibling managed files", sentinel.isFile)
        assertArrayEquals(bytes, sentinel.readBytes())
        assertFalse("Only the container's own image files may be removed", ownedFile.exists())
        assertFalse("The container UUID root must also be removed", ownedFile.parentFile!!.parentFile!!.exists())
    }

    @Test
    fun cleanupGuardRejectsParentSiblingAndOutsideWithoutDeletingAnything() = withControlledBase { context ->
        val parent = context.filesDir.resolve("image-work/camera/mirra-test-sandboxes")
        val sentinel = context.filesDir.resolve("images/sentinel.jpg").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(31))
        }
        listOf(parent, parent.parentFile!!, sentinel.parentFile!!).forEach { rejected ->
            assertThrows(IllegalArgumentException::class.java) {
                TestImageStorageSandbox.requireOwnedRoot(rejected, parent)
            }
        }
        assertArrayEquals(byteArrayOf(31), sentinel.readBytes())
    }

    @Test
    fun applicationContextAndRelativeResolverCannotEscapeSandbox() = withControlledBase { context ->
        TestImageStorageSandbox(context).use { sandbox ->
            assertEquals(sandbox.root, sandbox.context.applicationContext.filesDir)
            assertEquals(context.packageName, sandbox.context.packageName)
            listOf("", "../sibling.jpg", "/absolute.jpg", ".").forEach { path ->
                assertThrows(IllegalArgumentException::class.java) { sandbox.resolve(path) }
            }
            assertTrue(sandbox.resolve("image-work/camera/test.jpg").path.startsWith(sandbox.root.path + File.separator))
        }
    }

    @Test
    fun twoTestContainersUseDistinctImageRoots() = withControlledBase { context ->
        val first = TestAppContainer(context)
        val second = TestAppContainer(context)
        try {
            val firstFile = first.imageRepository.displayFile(IMAGE_PATH).apply { writeBytes(byteArrayOf(1)) }
            val secondFile = second.imageRepository.displayFile(IMAGE_PATH).apply { writeBytes(byteArrayOf(2)) }
            assertNotEquals(firstFile.canonicalPath, secondFile.canonicalPath)
            first.close()
            assertFalse(firstFile.exists())
            assertArrayEquals(byteArrayOf(2), secondFile.readBytes())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun cameraUrisInTwoSandboxesRemainAccessibleThroughRealFileProvider() = runBlocking {
        val target = ApplicationProvider.getApplicationContext<Context>()
        TestImageStorageSandbox(target).use { first ->
            TestImageStorageSandbox(target).use { second ->
                val firstTarget = DefaultImageStorageService(first.context).createCameraTarget()
                val secondTarget = DefaultImageStorageService(second.context).createCameraTarget()
                target.contentResolver.openOutputStream(firstTarget.uri)!!.use { it.write(byteArrayOf(41)) }
                target.contentResolver.openOutputStream(secondTarget.uri)!!.use { it.write(byteArrayOf(43)) }
                assertArrayEquals(byteArrayOf(41), first.resolve(firstTarget.tempPath).readBytes())
                assertArrayEquals(byteArrayOf(43), second.resolve(secondTarget.tempPath).readBytes())
                assertFalse(target.filesDir.resolve(firstTarget.tempPath).exists())
                assertFalse(target.filesDir.resolve(secondTarget.tempPath).exists())
            }
        }
    }

    private fun withControlledBase(block: (Context) -> Unit) {
        val target = ApplicationProvider.getApplicationContext<Context>()
        val parent = target.cacheDir.resolve("mirra-storage-isolation-regressions").canonicalFile
        val root = parent.resolve(UUID.randomUUID().toString()).apply { check(mkdirs()) }
        val context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
        try {
            block(context)
        } finally {
            check(root.canonicalPath.startsWith(parent.path + File.separator))
            check(root.deleteRecursively())
        }
    }

    private companion object {
        const val IMAGE_PATH = "images/123e4567-e89b-12d3-a456-426614174000.jpg"
    }
}
