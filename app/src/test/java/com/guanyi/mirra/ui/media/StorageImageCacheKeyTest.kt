package com.guanyi.mirra.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StorageImageCacheKeyTest {
    @Test fun replacingGenerationSeparatesTheSameImagePath() {
        val path = "/private/images/same-image.jpg"
        assertNotEquals(storageImageCacheKey("old-owner", path), storageImageCacheKey("new-owner", path))
    }

    @Test fun oneGenerationReusesItsOwnImageButNotAnotherPath() {
        val key = storageImageCacheKey("same-owner", "/private/images/first.jpg")
        assertEquals(key, storageImageCacheKey("same-owner", "/private/images/first.jpg"))
        assertNotEquals(key, storageImageCacheKey("same-owner", "/private/images/second.jpg"))
    }
}
