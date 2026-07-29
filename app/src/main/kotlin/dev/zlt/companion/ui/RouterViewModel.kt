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
    private val repository = RouterRepository(settingsStore)
    private val statusCache = StatusCache(application)

    val settings: StateFlow<RouterSettings?> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _state = MutableStateFlow(StatusUiState())
    val state: StateFlow<StatusUiState> = _state.asStateFlow()

    private var consecutiveFailures = 0
    private var inFlight: Job? = null

    /**
     * One poll.
     *
     * Overlapping calls are dropped rather than queued — the same guard the web
     * dashboard uses. The device is slow enough that a backlog of polls would
     * simply pile up behind the client's mutex.
     */
    fun refresh(includeMode: Boolean = false) {
        if (inFlight?.isActive == true) return
        inFlight = viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                val status = repository.status()
                // The mode is read on demand rather than every poll: it costs a
                // second authenticated round trip, it only changes when someone
                // changes it, and the device is slow enough that the two calls
                // would contend.
                val mode = if (includeMode || _state.value.mode == null) {
                    runCatching { repository.mode() }.getOrNull()
                } else {
                    null
                }
                consecutiveFailures = 0
                // Kept for the Quick Settings tile, which cannot poll on its own.
                statusCache.store(status.reading)
                _state.update {
                    it.copy(
                        status = status,
                        mode = mode ?: it.mode,
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
            refresh(includeMode = true)
        }
    }

    fun setPollSeconds(seconds: Int) {
        viewModelScope.launch { settingsStore.setPollSeconds(seconds) }
    }
}
