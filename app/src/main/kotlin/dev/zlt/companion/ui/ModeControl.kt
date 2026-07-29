package dev.zlt.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zlt.companion.R
import dev.zlt.companion.data.NetModeState
import dev.zlt.companion.net.NetworkMode

/**
 * The mode switcher: one chip per BearerPreference the device accepts.
 *
 * Every chip is disabled while a change is in flight. The device applies the
 * change by re-attaching to the network, so a second request sent underneath
 * the first would race the read-back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModeControl(
    mode: NetModeState?,
    changingMode: NetworkMode?,
    onSelect: (NetworkMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            NetworkMode.entries.forEach { candidate ->
                FilterChip(
                    selected = mode?.configured == candidate,
                    onClick = { onSelect(candidate) },
                    enabled = changingMode == null,
                    label = { Text(candidate.label) },
                    leadingIcon = if (changingMode == candidate) {
                        {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        Text(
            text = stringResource(R.string.mode_change_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
