package dev.zlt.companion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.zlt.companion.R
import dev.zlt.companion.data.SignalReading
import dev.zlt.companion.ui.theme.ZltTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ZltTheme {
                AppRoot()
            }
        }
    }
}

/** The four tabs, in the dashboard's order. */
private enum class AppTab(@StringRes val label: Int) {
    SIGNAL(R.string.tab_signal),
    MESSAGES(R.string.tab_messages),
    USSD(R.string.tab_ussd),
    SPEED(R.string.tab_speed),
}

@Composable
private fun AppRoot(
    viewModel: RouterViewModel = viewModel(),
    messagesViewModel: MessagesViewModel = viewModel(),
    ussdViewModel: UssdViewModel = viewModel(),
    speedViewModel: SpeedViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages by messagesViewModel.state.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(AppTab.SIGNAL) }

    val current = settings
    if (current == null) {
        // At most one frame, while DataStore reads from disk.
        Surface {}
        return
    }

    if (settingsOpen || !current.isConfigured) {
        BackHandler(enabled = current.isConfigured) { settingsOpen = false }
        SettingsScreen(
            settings = current,
            showPollInterval = current.isConfigured,
            onSave = { host, username, password ->
                viewModel.saveSettings(host, username, password)
                settingsOpen = false
            },
            onPollSecondsChange = viewModel::setPollSeconds,
            onBack = if (current.isConfigured) ({ settingsOpen = false }) else null,
            // Only during first setup: once there is a real router configured,
            // offering a fake one is just confusing.
            onTryDemo = if (!current.isConfigured) ({ viewModel.setDemoMode(true) }) else null,
        )
        return
    }

    // Back from another tab goes to Signal first, then leaves the app.
    BackHandler(enabled = tab != AppTab.SIGNAL) { tab = AppTab.SIGNAL }

    // Polling is tied to the RESUMED state, so it stops the moment the app is
    // backgrounded and resumes on return. Nothing polls the router in the
    // background: this app has no service and no wakelock. It runs on every
    // tab, as the dashboard's does, so the reading in the top bar stays live.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, current.pollSeconds, current.credentials(), current.demoMode) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refresh()
                delay(current.pollSeconds * 1_000L)
            }
        }
    }

    // One inbox read after the first good poll, only to put a number on the
    // Messages tab. Not repeated on a timer; see MessagesViewModel.
    LaunchedEffect(state.everLoaded) {
        if (state.everLoaded) messagesViewModel.ensureLoaded()
    }
    LaunchedEffect(tab) {
        if (tab == AppTab.MESSAGES) messagesViewModel.ensureLoaded()
    }

    Scaffold(
        topBar = {
            TopBar(
                host = current.host,
                // The Signal tab shows the full meter; everywhere else gets
                // the one-line version, as the dashboard's meter folds.
                reading = state.status?.reading,
                showReading = tab != AppTab.SIGNAL && state.everLoaded,
                onOpenSettings = { settingsOpen = true },
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { candidate ->
                    NavigationBarItem(
                        selected = tab == candidate,
                        onClick = { tab = candidate },
                        label = { Text(stringResource(candidate.label)) },
                        icon = { TabIcon(candidate, unread = messages.unread) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // So a tab's imePadding() adds only what the keyboard covers
                // beyond the navigation bar, rather than both stacked.
                .consumeWindowInsets(padding),
        ) {
            // Persistent, not dismissable, and on every tab: sample messages
            // and readings must never be mistaken for the real router's.
            if (current.demoMode) {
                DemoBanner(onExitDemo = { viewModel.setDemoMode(false) })
            }
            val content = Modifier.weight(1f)
            when (tab) {
                AppTab.SIGNAL -> StatusScreen(
                    state = state,
                    onRefresh = { viewModel.refresh() },
                    modeControl = {
                        ModeControl(
                            mode = state.mode,
                            changingMode = state.changingMode,
                            onSelect = viewModel::setMode,
                        )
                    },
                    modifier = content,
                )
                AppTab.MESSAGES -> MessagesScreen(viewModel = messagesViewModel, modifier = content)
                AppTab.USSD -> UssdScreen(viewModel = ussdViewModel, modifier = content)
                AppTab.SPEED -> SpeedScreen(
                    viewModel = speedViewModel,
                    demoMode = current.demoMode,
                    modifier = content,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    host: String,
    reading: SignalReading?,
    showReading: Boolean,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.device_name),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            if (showReading) {
                SignalSummary(reading)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_open))
            }
        },
    )
}

/** The folded meter: bars and the quality word. */
@Composable
private fun SignalSummary(reading: SignalReading?) {
    val word = reading?.let { stringResource(qualityWord(it.level)) }
        ?: stringResource(R.string.status_no_reading)
    val tint = reading?.let { qualityColor(it.level) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(end = 4.dp),
    ) {
        SignalBars(
            bars = reading?.bars ?: 0,
            tint = tint,
            contentDescription = word,
            barWidth = 4.dp,
            shortestBar = 5.dp,
            barStep = 3.dp,
            gap = 2.dp,
        )
        Text(text = word, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
private fun TabIcon(tab: AppTab, unread: Int) {
    when (tab) {
        AppTab.SIGNAL -> Icon(painterResource(R.drawable.ic_tile_signal_4), contentDescription = null)
        AppTab.MESSAGES -> {
            val description = if (unread > 0) {
                stringResource(R.string.tab_messages_unread, unread)
            } else {
                null
            }
            BadgedBox(
                badge = { if (unread > 0) Badge { Text(unread.toString()) } },
                modifier = if (description != null) {
                    Modifier.semantics { contentDescription = description }
                } else {
                    Modifier
                },
            ) {
                Icon(Icons.Filled.Email, contentDescription = null)
            }
        }
        AppTab.USSD -> Icon(Icons.Filled.Call, contentDescription = null)
        AppTab.SPEED -> Icon(
            painterResource(R.drawable.ic_tab_speed),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun DemoBanner(onExitDemo: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.demo_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            )
            TextButton(onClick = onExitDemo) {
                Text(stringResource(R.string.demo_exit))
            }
        }
    }
}
