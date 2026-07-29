package dev.zlt.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zlt.companion.data.SignalLevel

/**
 * The familiar ascending bars. Filled up to [bars], the rest left faint.
 *
 * A single [contentDescription] is set on the row and the bars themselves are
 * not individually described, so a screen reader announces "Signal: good, 3 of
 * 4 bars" rather than reading out four anonymous boxes.
 */
@Composable
fun SignalBars(
    bars: Int,
    tint: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
    barWidth: Dp = 10.dp,
    shortestBar: Dp = 10.dp,
    barStep: Dp = 8.dp,
    gap: Dp = 5.dp,
) {
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    Row(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (index in 1..SignalLevel.MAX_BARS) {
            Box(
                width = barWidth,
                height = shortestBar + barStep * (index - 1),
                color = if (index <= bars) tint else faint,
            )
        }
    }
}

@Composable
private fun Box(width: Dp, height: Dp, color: Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(width = width, height = height)
            .height(height)
            .background(color = color, shape = RoundedCornerShape(2.dp)),
    )
}
