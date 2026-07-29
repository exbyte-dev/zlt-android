package dev.zlt.companion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zlt.companion.data.RouterSettings
import dev.zlt.companion.data.SettingsStore
import dev.zlt.companion.ui.theme.ZltTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(applicationContext)
        setContent {
            ZltTheme {
                AppRoot(store)
            }
        }
    }
}

@Composable
private fun AppRoot(store: SettingsStore) {
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var settingsOpen by remember { mutableStateOf(false) }

    val current = settings
    if (current == null) {
        // One frame at most, while DataStore reads from disk.
        Surface {}
        return
    }

    // Nothing to show until there is a router to show it for.
    LaunchedEffect(current.isConfigured) {
        if (!current.isConfigured) settingsOpen = true
    }

    if (settingsOpen || !current.isConfigured) {
        SettingsScreen(
            settings = current,
            showPollInterval = current.isConfigured,
            onSave = { host, username, password ->
                scope.launch {
                    store.save(host, username, password)
                    settingsOpen = false
                }
            },
            onPollSecondsChange = { seconds -> scope.launch { store.setPollSeconds(seconds) } },
            onBack = if (current.isConfigured) ({ settingsOpen = false }) else null,
        )
    } else {
        // Replaced by the status screen in the next commit.
        Surface { Text(placeholderText(current)) }
    }
}

private fun placeholderText(settings: RouterSettings): String =
    "Configured for ${settings.host}"
