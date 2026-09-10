package com.guanyi.mirra

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationContextTest {
    @Test
    fun applicationUsesMirraPackage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("com.guanyi.mirra", context.packageName)
    }
}
