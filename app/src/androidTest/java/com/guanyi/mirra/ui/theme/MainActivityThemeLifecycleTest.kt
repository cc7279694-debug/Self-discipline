package com.guanyi.mirra.ui.theme

import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.MainActivity
import com.guanyi.mirra.MirraApplication
import com.guanyi.mirra.data.preferences.MirraThemeId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityThemeLifecycleTest {
    @Test
    fun persistedThemeIsAppliedAfterActivityRecreate() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<MirraApplication>()
        application.container.appPreferencesRepository.setThemeId(MirraThemeId.NIGHT)

        try {
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
        } finally {
            application.container.appPreferencesRepository.setThemeId(MirraThemeId.BLUE)
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                        .isAppearanceLightStatusBars,
                )
            }
        }
        Unit
    }
}
