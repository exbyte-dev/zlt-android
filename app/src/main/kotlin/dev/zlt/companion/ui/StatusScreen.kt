package dev.zlt.companion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.zlt.companion.R
import dev.zlt.companion.data.ConnectionState
import dev.zlt.companion.data.NetModeState
import dev.zlt.companion.data.RouterStatus
import dev.zlt.companion.data.SignalLevel
import dev.zlt.companion.data.SignalReading
import dev.zlt.companion.ui.theme.QualityColors
import java.text.DateFormat
import java.util.Date

@Composable
fun StatusScreen(
    state: StatusUiState,
    host: String,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modeControl: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(host = host, onOpenSettings = onOpenSettings)

            state.error?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            SignalCard(reading = state.status?.reading, everLoaded = state.everLoaded)

            state.status?.let { status ->
                ConnectionCard(status.connection)
                ModeCard(mode = state.mode, control = modeControl)

                if (!status.authed) {
                    Text(
                        text = stringResource(R.string.status_open_data_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DetailsSection(status)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.lastUpdatedEpochMs?.let {
                        stringResource(R.string.status_updated, formatTime(it))
                    } ?: stringResource(R.string.status_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRefresh, enabled = !state.refreshing) {
                    Text(stringResource(R.string.status_refresh))
                }
            }
        }
    }
}

@Composable
private fun Header(host: String, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        TextButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.settings_open))
        }
    }
}

/**
 * The headline: bars and a word, with the dBm figure deliberately smaller and
 * underneath. Someone who does not know what RSRP is should still be able to
 * read this at a glance.
 */
@Composable
private fun SignalCard(reading: SignalReading?, everLoaded: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.status_signal),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (reading == null) {
                Text(
                    text = if (everLoaded) {
                        stringResource(R.string.status_no_reading)
                    } else {
                        stringResource(R.string.status_checking)
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
                if (everLoaded) {
                    Text(
                        text = stringResource(R.string.status_no_reading_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            val word = stringResource(qualityWord(reading.level))
            val tint = qualityColor(reading.level)
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SignalBars(
                    bars = reading.bars,
                    tint = tint,
                    contentDescription = stringResource(
                        R.string.status_bars_description,
                        word,
                        reading.bars,
                        SignalLevel.MAX_BARS,
                    ),
                )
                Column {
                    Text(
                        text = word,
                        style = MaterialTheme.typography.headlineMedium,
                        color = tint,
                    )
                    // The raw figure stays available but secondary, as asked.
                    Text(
                        text = stringResource(
                            R.string.status_dbm,
                            reading.metric.label,
                            reading.dbm,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { reading.fraction },
                color = tint,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ConnectionCard(connection: ConnectionState) {
    LabelledCard(label = stringResource(R.string.status_internet)) {
        Text(
            text = when (connection) {
                ConnectionState.Connected -> stringResource(R.string.status_connected)
                ConnectionState.Connecting -> stringResource(R.string.status_connecting)
                ConnectionState.Disconnected -> stringResource(R.string.status_disconnected)
                ConnectionState.Unknown -> stringResource(R.string.status_connection_unknown)
                is ConnectionState.Reported ->
                    stringResource(R.string.status_connection_reported, connection.raw)
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ModeCard(mode: NetModeState?, control: @Composable () -> Unit) {
    LabelledCard(label = stringResource(R.string.status_network_mode)) {
        Text(
            text = when {
                mode == null -> stringResource(R.string.status_checking)
                mode.configured != null -> mode.configured.label
                mode.configuredRaw.isNotEmpty() ->
                    stringResource(R.string.status_mode_unrecognised, mode.configuredRaw)
                else -> stringResource(R.string.status_mode_unknown)
            },
            style = MaterialTheme.typography.titleMedium,
        )
        if (mode != null && mode.currentNetwork.isNotEmpty()) {
            Text(
                text = stringResource(R.string.status_on_now, mode.currentNetwork),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        control()
    }
}

@Composable
private fun LabelledCard(label: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun DetailsSection(status: RouterStatus) {
    var expanded by remember { mutableStateOf(false) }
    val empty = stringResource(R.string.detail_empty)

    TextButton(onClick = { expanded = !expanded }) {
        Text(
            stringResource(
                if (expanded) R.string.status_details_hide else R.string.status_details,
            ),
        )
    }
    if (!expanded) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DetailRow(stringResource(R.string.detail_network_type), status.networkType, empty)
            DetailRow(stringResource(R.string.detail_rsrp), status.rsrpDbm?.toString(), empty)
            DetailRow(stringResource(R.string.detail_rssi), status.rssiDbm?.toString(), empty)
            DetailRow(stringResource(R.string.detail_band), status.band, empty)
            DetailRow(stringResource(R.string.detail_snr), status.snr, empty)
            DetailRow(stringResource(R.string.detail_rsrq), status.rsrq, empty)
            DetailRow(stringResource(R.string.detail_pci), status.pci, empty)
            DetailRow(stringResource(R.string.detail_device_bars), status.deviceBars, empty)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String?, empty: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: empty,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

private fun qualityWord(level: SignalLevel): Int = when (level) {
    SignalLevel.EXCELLENT -> R.string.quality_excellent
    SignalLevel.GOOD -> R.string.quality_good
    SignalLevel.FAIR -> R.string.quality_fair
    SignalLevel.WEAK -> R.string.quality_weak
}

/** Excellent and good share a colour, as they do in the web dashboard. */
@Composable
fun qualityColor(level: SignalLevel): Color {
    val dark = isSystemInDarkTheme()
    return when (level) {
        SignalLevel.EXCELLENT, SignalLevel.GOOD ->
            if (dark) QualityColors.GoodDark else QualityColors.Good
        SignalLevel.FAIR -> if (dark) QualityColors.FairDark else QualityColors.Fair
        SignalLevel.WEAK -> if (dark) QualityColors.PoorDark else QualityColors.Poor
    }
}

private fun formatTime(epochMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(epochMs))
