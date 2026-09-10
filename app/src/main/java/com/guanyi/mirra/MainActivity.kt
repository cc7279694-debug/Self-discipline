package com.guanyi.mirra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as MirraApplication).container
        lifecycleScope.launch {
            container.startup.await()
            setContent {
                val restoredDestination by container.appPreferencesRepository.lastDestination.collectAsStateWithLifecycle(
                    initialValue = TopLevelDestination.Start,
                )

                MirraTheme {
                    MirraApp(
                        container = container,
                        restoredDestination = restoredDestination,
                        onDestinationChanged = { destination ->
                            lifecycleScope.launch {
                                container.appPreferencesRepository.setLastDestination(destination)
                            }
                        },
                    )
                }
            }
        }
    }
}
