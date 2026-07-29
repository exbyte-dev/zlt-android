package dev.zlt.companion.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Signal quality colours, shared by the meter, the readings and the tile. */
object QualityColors {
    val Good = Color(0xFF2E7D4F)
    val GoodDark = Color(0xFF4ADE9B)
    val Fair = Color(0xFF9A6B00)
    val FairDark = Color(0xFFE0B341)
    val Poor = Color(0xFFB3261E)
    val PoorDark = Color(0xFFF2857D)
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF25638F),
    secondary = Color(0xFF50606E),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF97CBFA),
    secondary = Color(0xFFB7C9D8),
)

@Composable
fun ZltTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
