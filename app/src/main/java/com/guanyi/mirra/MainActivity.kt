package com.guanyi.mirra

import android.os.Bundle
import android.content.Intent
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
    private val runtime get() = (application as MirraApplication).monitoringPlatform
    override fun onStart() { super.onStart(); runtime.setAppVisible(true) }
    override fun onStop() { runtime.setAppVisible(false); super.onStop() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptInterventionIntent(intent)
    }
    private fun acceptInterventionIntent(intent: Intent) {
        val request = com.guanyi.mirra.platform.intervention.InterventionActivityIntents.read(this, intent) ?: return
        lifecycleScope.launch {
            (application as MirraApplication).container.startup.await()
            runtime.interventionChannels?.navigation?.submit(request)
        }
    }
    override fun onResume() {
        super.onResume()
        (application as MirraApplication).monitoringPlatform.refreshCapabilities()
        runtime.reconcileInterventionPresentation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as MirraApplication).container
        lifecycleScope.launch {
            container.startup.await()
            if (savedInstanceState == null) acceptInterventionIntent(intent)
            setContent {
                val restoredDestination by container.appPreferencesRepository.lastDestination.collectAsStateWithLifecycle(
                    initialValue = TopLevelDestination.Start,
                )
                val themeId by container.appPreferencesRepository.themeId.collectAsStateWithLifecycle(
                    initialValue = com.guanyi.mirra.data.preferences.MirraThemeId.BLUE,
                )

                MirraTheme(themeId = themeId) {
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
