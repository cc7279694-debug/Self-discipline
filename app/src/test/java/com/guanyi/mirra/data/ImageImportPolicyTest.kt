package com.guanyi.mirra.data

import com.guanyi.mirra.data.storage.ImageImportPolicy
import com.guanyi.mirra.data.storage.ManagedImagePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageImportPolicyTest {
    @Test
    fun pickerFallbackCannotSendMoreThanTwentyImagesIntoPipeline() {
        val result = ImageImportPolicy.limitSelection((1..25).toList())

        assertEquals((1..20).toList(), result.accepted)
        assertEquals(5, result.rejectedCount)
    }

    @Test
    fun targetSizeKeepsAspectRatioWithoutUpscaling() {
        assertEquals(2560 to 1280, ImageImportPolicy.targetSize(6000, 3000))
        assertEquals(1200 to 800, ImageImportPolicy.targetSize(1200, 800))
        assertEquals(1280 to 2560, ImageImportPolicy.targetSize(2000, 4000))
    }

    @Test
    fun sampleSizeAvoidsDecodingHugeImageAtFullResolution() {
        assertEquals(2, ImageImportPolicy.sampleSize(8000, 6000))
        assertEquals(1, ImageImportPolicy.sampleSize(3000, 2000))
    }

    @Test
    fun onlyUuidJpegRelativePathsAreManaged() {
        assertTrue(ManagedImagePath.isValid("images/123e4567-e89b-12d3-a456-426614174000.jpg"))
        assertFalse(ManagedImagePath.isValid("content://picker/image"))
        assertFalse(ManagedImagePath.isValid("images/../secret.jpg"))
        assertFalse(ManagedImagePath.isValid("C:/private/photo.jpg"))
    }
}
