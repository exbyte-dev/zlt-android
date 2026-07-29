package dev.zlt.companion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zlt.companion.data.NetModeState
import dev.zlt.companion.data.RouterRepository
import dev.zlt.companion.data.RouterSettings
import dev.zlt.companion.data.RouterStatus
import dev.zlt.companion.data.SettingsStore
import dev.zlt.companion.data.StatusCache
import dev.zlt.companion.net.NetworkMode
import dev.zlt.companion.net.ZltException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatusUiState(
    val status: RouterStatus? = null,
    val mode: NetModeState? = null,
    /** Shown only once failures are persistent; see [FAIL_THRESHOLD]. */
    val error: String? = null,
    val lastUpdatedEpochMs: Long? = null,
    val everLoaded: Boolean = false,
    val refreshing: Boolean = false,
    /** The mode being applied right now, if any. */
    val changingMode: NetworkMode? = null,
) {
    companion object {
        /**
         * Consecutive failures before the banner appears, ported from
         * POLL_FAIL_THRESHOLD in the Python dashboard's signal.js. One dropped
         * poll on a busy embedded device is normal and should not flash an
         * error over readings that are still on screen.
         */
        const val FAIL_THRESHOLD = 2
    }
}

class RouterViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val repository = RouterRepository.from(settingsStore)
    private val statusCache = StatusCache(application)

    val settings: StateFlow<RouterSettings?> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _state = MutableStateFlow(StatusUiState())
    val state: StateFlow<StatusUiState> = _state.asStateFlow()

    private var consecutiveFailures = 0
    private var inFlight: Job? = null

    /**
     * One poll: signal and configured mode together, in a single read.
     *
     * The mode is refreshed on every poll, not just on first load. It has to be:
     * a mode changed from the router's own web UI otherwise never shows up here,
     * and a stale mode on screen next to a live signal reading looks exactly
     * like the app misreporting the signal.
     *
     * Overlapping calls are dropped rather than queued — the same guard the web
     * dashboard uses. The device is slow enough that a backlog of polls would
     * simply pile up behind the client's mutex.
     */
    fun refresh() {
        if (inFlight?.isActive == true) return
        inFlight = viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                val snapshot = repository.snapshot()
                consecutiveFailures = 0
                // Kept for the Quick Settings tile, which cannot poll on its
                // own. Never written in demo mode: the tile sits outside the
                // app and must not report signal from a router that is not there.
                if (settings.value?.demoMode != true) {
                    statusCache.store(snapshot.status.reading)
                }
                _state.update {
                    it.copy(
                        status = snapshot.status,
                        // Keep the last known mode when this poll could not read
                        // one (no session), rather than blanking the card.
                        mode = snapshot.mode ?: it.mode,
                        error = null,
                        lastUpdatedEpochMs = System.currentTimeMillis(),
                        everLoaded = true,
                        refreshing = false,
                    )
                }
            } catch (e: ZltException) {
                consecutiveFailures++
                _state.update {
                    it.copy(
                        refreshing = false,
                        error = if (consecutiveFailures >= StatusUiState.FAIL_THRESHOLD) {
                            e.message
                        } else {
                            it.error
                        },
                    )
                }
            }
        }
    }

    /**
     * Changes the network mode, then shows whatever the router reports
     * afterwards rather than assuming the request took.
     */
    fun setMode(mode: NetworkMode) {
        if (_state.value.changingMode != null) return
        _state.update { it.copy(changingMode = mode, error = null) }
        viewModelScope.launch {
            try {
                val applied = repository.setMode(mode)
                _state.update { it.copy(mode = applied, changingMode = null) }
            } catch (e: ZltException) {
                // Unlike a failed poll this is shown immediately: the user just
                // pressed something and is owed an answer about it.
                _state.update { it.copy(changingMode = null, error = e.message) }
            }
        }
    }

    fun saveSettings(host: String, username: String, password: String) {
        viewModelScope.launch {
            settingsStore.save(host, username, password)
            // Credentials changed, so nothing on screen is trustworthy any more.
            consecutiveFailures = 0
            _state.value = StatusUiState()
            refresh()
        }
    }

    fun setPollSeconds(seconds: Int) {
        viewModelScope.launch { settingsStore.setPollSeconds(seconds) }
    }

    fun setDemoMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setDemoMode(enabled)
            // The data source changed, so nothing on screen still applies.
            consecutiveFailures = 0
            _state.value = StatusUiState()
            refresh()
        }
    }
}
