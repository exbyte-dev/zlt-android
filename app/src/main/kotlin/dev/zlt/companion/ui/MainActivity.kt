package dev.zlt.companion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.zlt.companion.ui.theme.ZltTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZltTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot(viewModel: RouterViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }

    val current = settings
    if (current == null) {
        // At most one frame, while DataStore reads from disk.
        Surface {}
        return
    }

    if (settingsOpen || !current.isConfigured) {
        SettingsScreen(
            settings = current,
            showPollInterval = current.isConfigured,
            onSave = { host, username, password ->
                viewModel.saveSettings(host, username, password)
                settingsOpen = false
            },
            onPollSecondsChange = viewModel::setPollSeconds,
            onBack = if (current.isConfigured) ({ settingsOpen = false }) else null,
        )
        return
    }

    // Polling is tied to the RESUMED state, so it stops the moment the app is
    // backgrounded and resumes on return. Nothing polls the router in the
    // background: this app has no service and no wakelock.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, current.pollSeconds, current.credentials()) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refresh()
                delay(current.pollSeconds * 1_000L)
            }
        }
    }

    StatusScreen(
        state = state,
        host = current.host,
        onRefresh = { viewModel.refresh() },
        onOpenSettings = { settingsOpen = true },
        modeControl = {
            ModeControl(
                mode = state.mode,
                changingMode = state.changingMode,
                onSelect = viewModel::setMode,
            )
        },
    )
}
