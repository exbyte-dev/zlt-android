package dev.zlt.companion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zlt.companion.speed.SpeedTest
import dev.zlt.companion.speed.SpeedTestFailed
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SpeedPhase { PING, DOWNLOAD, UPLOAD }

data class SpeedUiState(
    val phase: SpeedPhase? = null,
    val pingMs: Int? = null,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    /** The download figure while it is still running. */
    val liveDownMbps: Double? = null,
    val error: String? = null,
    val finishedAtEpochMs: Long? = null,
) {
    val running: Boolean get() = phase != null
}

class SpeedViewModel(application: Application) : AndroidViewModel(application) {

    private val speedTest = SpeedTest()

    private val _state = MutableStateFlow(SpeedUiState())
    val state: StateFlow<SpeedUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** Latency, then download, then upload, as the dashboard runs them. */
    fun run() {
        if (_state.value.running) return
        _state.value = SpeedUiState(phase = SpeedPhase.PING)
        job = viewModelScope.launch {
            try {
                val ping = speedTest.ping()
                _state.update { it.copy(pingMs = ping, phase = SpeedPhase.DOWNLOAD) }
                val down = speedTest.download { live -> _state.update { it.copy(liveDownMbps = live) } }
                _state.update { it.copy(downMbps = down, liveDownMbps = null, phase = SpeedPhase.UPLOAD) }
                val up = speedTest.upload()
                _state.update {
                    it.copy(upMbps = up, phase = null, finishedAtEpochMs = System.currentTimeMillis())
                }
            } catch (e: SpeedTestFailed) {
                // Stopping interrupts the transfer, which surfaces as a failed
                // read. That is not an error worth showing.
                ensureActive()
                _state.update { it.copy(phase = null, liveDownMbps = null, error = e.message) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(phase = null, liveDownMbps = null) }
    }
}
