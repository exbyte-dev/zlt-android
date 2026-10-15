package dev.zlt.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zlt.companion.R
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * The speed test. Off in demo mode: it would measure the real connection and
 * present it next to sample readings, and demo mode promises no traffic.
 */
@Composable
fun SpeedScreen(viewModel: SpeedViewModel, demoMode: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.speed_title), style = MaterialTheme.typography.titleLarge)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ResultRow(
                    label = stringResource(R.string.speed_ping),
                    value = state.pingMs?.let { stringResource(R.string.speed_ms, it) },
                    active = state.phase == SpeedPhase.PING,
                )
                ResultRow(
                    label = stringResource(R.string.speed_download),
                    value = (state.liveDownMbps ?: state.downMbps)?.let { stringResource(R.string.speed_mbps, mbps(it)) },
                    active = state.phase == SpeedPhase.DOWNLOAD,
                )
                ResultRow(
                    label = stringResource(R.string.speed_upload),
                    value = state.upMbps?.let { stringResource(R.string.speed_mbps, mbps(it)) },
                    active = state.phase == SpeedPhase.UPLOAD,
                )
            }
        }

        if (state.running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        val status = when {
            state.phase == SpeedPhase.PING -> stringResource(R.string.speed_measuring_ping)
            state.phase == SpeedPhase.DOWNLOAD -> stringResource(R.string.speed_measuring_down)
            state.phase == SpeedPhase.UPLOAD -> stringResource(R.string.speed_measuring_up)
            state.error != null -> state.error
            state.finishedAtEpochMs != null -> stringResource(
                R.string.speed_finished,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.finishedAtEpochMs!!)),
            )
            else -> null
        }
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.error != null && !state.running) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (demoMode) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.speed_demo_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(14.dp),
                )
            }
        } else if (state.running) {
            OutlinedButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.speed_stop))
            }
        } else {
            Button(onClick = viewModel::run, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.speed_run))
            }
        }

        Text(
            text = stringResource(R.string.speed_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.speed_server_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultRow(label: String, value: String?, active: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value ?: stringResource(R.string.detail_empty),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = if (active) FontWeight.Normal else FontWeight.Medium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One decimal place, in the user's own number format. */
private fun mbps(value: Double): String =
    NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(value)
