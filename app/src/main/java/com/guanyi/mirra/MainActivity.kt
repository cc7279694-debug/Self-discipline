package com.guanyi.mirra

import android.os.Bundle
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.ui.theme.MirraTheme
import kotlinx.coroutines.launch
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.AbstractComposeView
import androidx.lifecycle.Lifecycle

class MainActivity : ComponentActivity() {
    private val app get() = application as MirraApplication
    private val runtime get() = app.monitoringPlatformOrNull
    private var initialLaunchIntentPending = false
    override fun onStart() { super.onStart(); runtime?.setAppVisible(true) }
    override fun onStop() { runtime?.setAppVisible(false); super.onStop() }
    override fun onDestroy() { app.unregisterReaders(this); super.onDestroy() }
    internal fun detachStorageReaders() {
        // ViewModelStore alone does not own Navigation/Flow/Coil composition readers.
        fun dispose(view: View) {
            if (view is AbstractComposeView) view.disposeComposition()
            else if (view is ViewGroup) for (index in 0 until view.childCount) dispose(view.getChildAt(index))
        }
        dispose(findViewById(android.R.id.content))
        viewModelStore.clear()
        installStorageContent()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptInterventionIntent(intent)
    }
    private fun acceptInterventionIntent(intent: Intent) {
        val request = com.guanyi.mirra.platform.intervention.InterventionActivityIntents.read(this, intent) ?: return
        lifecycleScope.launch {
            val selected = app.storageState.value.takeIf { it.phase == StoragePresentation.OPEN }?.container ?: return@launch
            selected.startup.await()
            if (app.storageState.value.phase != StoragePresentation.OPEN || app.storageState.value.container !== selected) return@launch
            runtime?.interventionChannels?.navigation?.submit(request)
        }
    }
    override fun onResume() {
        super.onResume()
        if (app.storageState.value.phase == StoragePresentation.OPEN) {
            runtime?.refreshCapabilities()
            runtime?.reconcileInterventionPresentation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        initialLaunchIntentPending = savedInstanceState == null
        app.registerReaders(this)
        installStorageContent()
    }

    private fun installStorageContent() {
        setContent {
            val storage by app.storageState.collectAsStateWithLifecycle()
            val container = storage.container
            val statusMessage = storage.statusMessage
            if (storage.phase == StoragePresentation.BLOCKED) {
                MirraTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement=Arrangement.Center) {
                            Text("数据恢复尚未确认", style=MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(16.dp))
                            Text("为保护原数据，Mirra 暂停打开学习数据。请保留现有文件，不要清除应用数据。")
                            Spacer(Modifier.height(24.dp))
                            Button(onClick=app::retryRestoreBootstrap, modifier=Modifier.heightIn(min=48.dp)) { Text("重新检查并打开") }
                        }
                    }
                }
            } else if (storage.phase == StoragePresentation.SWITCHING || container == null) {
                MirraTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) { Text("正在安全恢复数据…") } } }
            } else key(container) {
                var ready by remember { mutableStateOf(false) }
                var startupFailed by remember { mutableStateOf(false) }
                LaunchedEffect(container) {
                    try {
                        container.startup.await()
                        ready = true
                        if (initialLaunchIntentPending) {
                            initialLaunchIntentPending = false
                            acceptInterventionIntent(intent)
                        }
                    } catch (_: Exception) { startupFailed = true }
                }
                LaunchedEffect(container, storage.phase) {
                    if (storage.phase == StoragePresentation.OPEN && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        runtime?.setAppVisible(true)
                        runtime?.refreshCapabilities()
                        runtime?.reconcileInterventionPresentation()
                    }
                }
                val restoredDestination by container.appPreferencesRepository.lastDestination.collectAsStateWithLifecycle(
                    initialValue = TopLevelDestination.Start,
                )
                val themeId by container.appPreferencesRepository.themeId.collectAsStateWithLifecycle(
                    initialValue = com.guanyi.mirra.data.preferences.MirraThemeId.BLUE,
                )

                MirraTheme(themeId = themeId) {
                    Box(Modifier.fillMaxSize()) {
                    if (ready) MirraApp(
                        container = container,
                        restoredDestination = restoredDestination,
                        onDestinationChanged = { destination ->
                            lifecycleScope.launch {
                                if (app.storageState.value.phase == StoragePresentation.OPEN &&
                                    app.storageState.value.container === container) {
                                    container.appPreferencesRepository.setLastDestination(destination)
                                }
                            }
                        },
                    ) else Text(if (startupFailed) "启动恢复未完成，请重新打开 Mirra。" else "正在打开 Mirra…", Modifier.align(Alignment.Center))
                    if (storage.phase == StoragePresentation.BUSY) {
                        androidx.compose.ui.window.Dialog(onDismissRequest={}, properties=androidx.compose.ui.window.DialogProperties(dismissOnBackPress=false, dismissOnClickOutside=false)) {
                            Surface(shape=MaterialTheme.shapes.large) { Text("正在安全处理数据…", Modifier.padding(28.dp)) }
                        }
                    }
                    if (storage.phase == StoragePresentation.OPEN && statusMessage != null) {
                        AlertDialog(
                            onDismissRequest = app::acknowledgeStorageMessage,
                            title = { Text("数据恢复") },
                            text = { Text(statusMessage) },
                            confirmButton = {
                                TextButton(onClick = app::acknowledgeStorageMessage) { Text("知道了") }
                            },
                        )
                    }
                    }
                }
            }
        }
    }
}
