package dev.zlt.companion.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.zlt.companion.R
import dev.zlt.companion.data.AppContainer
import dev.zlt.companion.data.CachedSignal
import dev.zlt.companion.data.SignalLevel
import dev.zlt.companion.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Quick Settings tile: signal strength at a glance, tap to open the app.
 *
 * Android only calls [onStartListening] while the panel is actually open, so
 * there is no sanctioned way to keep a tile updating on a timer without a
 * foreground service — too heavy, and too much Play Store scrutiny, for a signal
 * readout. So the tile paints the cached reading the moment it appears, then
 * refreshes it in the background and repaints. In practice that means it is
 * current within a second of pulling the shade down, which is when anyone is
 * looking at it.
 *
 * A refresh failure leaves the cached value on screen rather than blanking the
 * tile: a slightly old reading beats no reading, and the app itself is one tap
 * away for anything more.
 */
class SignalTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null

    private val container by lazy { AppContainer.get(applicationContext) }
    private val settingsStore get() = container.settingsStore
    private val repository get() = container.repository
    private val cache get() = container.statusCache

    override fun onStartListening() {
        super.onStartListening()
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val settings = settingsStore.current()
            // Demo mode is deliberately excluded, not just unconfigured setups.
            // The tile lives outside the app, where there is no banner to say
            // the reading is a sample, so showing one would be a plain lie
            // about the network. It also must never poll in demo mode: that
            // would write sample readings into the cache the tile reads.
            if (!settings.isConfigured || settings.demoMode) {
                renderUnconfigured()
                return@launch
            }
            // Cached first, so the tile is never blank while the router answers.
            render(cache.current())
            runCatching { repository.snapshot() }
                .onSuccess { snapshot ->
                    cache.store(snapshot.status.reading)
                    render(cache.current())
                }
        }
    }

    override fun onStopListening() {
        refreshJob?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        val open = Runnable { openApp() }
        // Launching straight from a locked device would be dropped, so ask the
        // system to unlock first when it has to.
        if (isLocked) unlockAndRun(open) else open.run()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            // Only reached below Android 14, where this overload is the one
            // that exists; lint cannot see the version check above.
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(signal: CachedSignal) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, iconFor(signal.bars))
        tile.label = getString(R.string.tile_label)
        tile.state = if (signal.hasReading) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

        val word = signal.level?.let { getString(wordFor(it)) }
            ?: getString(R.string.tile_no_signal)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = word
        }
        tile.contentDescription = if (signal.hasReading) {
            getString(
                R.string.status_bars_description,
                word,
                signal.bars,
                SignalLevel.MAX_BARS,
            )
        } else {
            word
        }
        tile.updateTile()
    }

    private fun renderUnconfigured() {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_signal_0)
        tile.label = getString(R.string.tile_label)
        tile.state = Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(R.string.tile_not_set_up)
        }
        tile.contentDescription = getString(R.string.tile_not_set_up)
        tile.updateTile()
    }

    private fun iconFor(bars: Int): Int = when (bars.coerceIn(0, SignalLevel.MAX_BARS)) {
        1 -> R.drawable.ic_tile_signal_1
        2 -> R.drawable.ic_tile_signal_2
        3 -> R.drawable.ic_tile_signal_3
        4 -> R.drawable.ic_tile_signal_4
        else -> R.drawable.ic_tile_signal_0
    }

    private fun wordFor(level: SignalLevel): Int = when (level) {
        SignalLevel.EXCELLENT -> R.string.quality_excellent
        SignalLevel.GOOD -> R.string.quality_good
        SignalLevel.FAIR -> R.string.quality_fair
        SignalLevel.WEAK -> R.string.quality_weak
    }
}
