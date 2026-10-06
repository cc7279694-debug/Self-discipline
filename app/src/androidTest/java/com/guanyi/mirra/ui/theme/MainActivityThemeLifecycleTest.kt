package com.guanyi.mirra.ui.theme

import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.MainActivity
import com.guanyi.mirra.MirraApplication
import com.guanyi.mirra.data.preferences.MirraThemeId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityThemeLifecycleTest {
    @Test
    fun persistedThemeIsAppliedAfterActivityRecreate() = runBlocking {
        verifyThemeLifecycle()
    }

    @Test
    fun initialNightThemeIsRestoredAfterLifecycleChecks() = runBlocking {
        verifyInitialThemeIsRestored(MirraThemeId.NIGHT)
    }

    @Test
    fun initialBlueThemeIsRestoredAfterLifecycleChecks() = runBlocking {
        verifyInitialThemeIsRestored(MirraThemeId.BLUE)
    }

    private suspend fun verifyInitialThemeIsRestored(initialTheme: MirraThemeId) {
        val preferences = ApplicationProvider.getApplicationContext<MirraApplication>()
            .container.appPreferencesRepository
        val originalTheme = preferences.themeId.first()
        try {
            preferences.setThemeId(initialTheme)
            verifyThemeLifecycle()
            assertEquals(initialTheme, preferences.themeId.first())
        } finally {
            // Preparing the regression's initial state must not contaminate installed preferences.
            preferences.setThemeId(originalTheme)
        }
        assertEquals(originalTheme, preferences.themeId.first())
    }

    private suspend fun verifyThemeLifecycle() {
        val application = ApplicationProvider.getApplicationContext<MirraApplication>()
        val preferences = application.container.appPreferencesRepository
        val originalTheme = preferences.themeId.first()
        try {
            preferences.setThemeId(MirraThemeId.NIGHT)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertFalse(
                        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                            .isAppearanceLightStatusBars,
                    )
                }

                scenario.recreate()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertFalse(
                        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                            .isAppearanceLightStatusBars,
                    )
                }
            }
            preferences.setThemeId(MirraThemeId.BLUE)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertTrue(
                        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                            .isAppearanceLightStatusBars,
                    )
                }
            }
        } finally {
            preferences.setThemeId(originalTheme)
        }
        assertEquals(originalTheme, preferences.themeId.first())
    }
}
