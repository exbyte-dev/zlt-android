package dev.zlt.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.zlt.companion.R
import dev.zlt.companion.data.SignalHistory
import dev.zlt.companion.data.SignalReading
import dev.zlt.companion.data.SignalSample

/**
 * The walk-test strip, ported from drawSpark in the dashboard's signal.js:
 * RSRP as the main line in the current quality colour, RSSI faint behind it,
 * both on one dBm axis over the last fifteen minutes.
 *
 * A reading the device did not give (no session for RSRP, or no service at
 * all) breaks the line rather than being drawn as a fall to the floor.
 */
@Composable
fun WalkTestCard(history: List<SignalSample>, reading: SignalReading?) {
    val hasRsrp = history.any { it.rsrpDbm != null }
    val main = reading?.let { qualityColor(it.level) } ?: MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val description = stringResource(R.string.walk_description)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.walk_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (history.size < 2) {
                Text(
                    text = stringResource(R.string.walk_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .semantics { contentDescription = description },
            ) {
                // Faint guides every 20 dBm, so the line has something to be read against.
                for (dbm in listOf(-60, -80, -100)) {
                    val y = yFor(dbm)
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                trace(history, { it.rssiDbm }, faint, 1.2.dp.toPx())
                trace(history, { it.rsrpDbm }, main, 2.dp.toPx())
            }
            Text(
                text = stringResource(
                    if (hasRsrp) R.string.walk_caption_rsrp else R.string.walk_caption_rssi,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun DrawScope.yFor(dbm: Int): Float = size.height * (1f - SignalHistory.heightFraction(dbm))

private fun DrawScope.trace(
    history: List<SignalSample>,
    value: (SignalSample) -> Int?,
    color: Color,
    width: Float,
) {
    val start = history.first().atEpochMs
    val span = (history.last().atEpochMs - start).coerceAtLeast(1L).toFloat()
    val path = Path()
    var penDown = false
    for (sample in history) {
        val dbm = value(sample)
        if (dbm == null) {
            penDown = false
            continue
        }
        val x = (sample.atEpochMs - start) / span * size.width
        val y = yFor(dbm)
        if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
        penDown = true
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
