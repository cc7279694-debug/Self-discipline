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

        val preferencesRepository = (application as MirraApplication).container.appPreferencesRepository

        setContent {
            val restoredDestination by preferencesRepository.lastDestination.collectAsStateWithLifecycle(
                initialValue = TopLevelDestination.Start,
            )

            MirraTheme {
                MirraApp(
                    restoredDestination = restoredDestination,
                    onDestinationChanged = { destination ->
                        lifecycleScope.launch {
                            preferencesRepository.setLastDestination(destination)
                        }
                    },
                )
            }
        }
    }
}
