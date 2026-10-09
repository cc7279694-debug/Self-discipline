package com.guanyi.mirra

import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The restore boundary must synchronously remove both composition and ViewModel readers. */
@RunWith(AndroidJUnit4::class)
class StorageReaderLifecycleTest {
    @Test fun detachingReadersDisposesCompositionBeforeReturning() {
        runBlocking { ApplicationProvider.getApplicationContext<MirraApplication>().container.startup.await() }
        val entered = CountDownLatch(1)
        val disposed = AtomicBoolean(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DisposableEffect(Unit) {
                        entered.countDown()
                        onDispose { disposed.set(true) }
                    }
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue("The captured composition must be active before detaching", entered.await(5, TimeUnit.SECONDS))
            assertFalse(disposed.get())
            scenario.onActivity { activity ->
                activity.detachStorageReaders()
                assertTrue("Old navigation/image readers must be disposed before stores close", disposed.get())
            }
        }
    }

    @Test fun detachingReadersClearsOldViewModelWorkBeforeReturning() {
        val cleared = AtomicBoolean(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return modelClass.cast(ReaderViewModel(cleared))!!
                    }
                }
                ViewModelProvider(activity, factory)[ReaderViewModel::class.java]
                activity.detachStorageReaders()
                assertTrue("Old ViewModel readers must be cancelled before stores close", cleared.get())
            }
        }
    }

    private class ReaderViewModel(private val cleared: AtomicBoolean) : ViewModel() {
        override fun onCleared() { cleared.set(true) }
    }
}
