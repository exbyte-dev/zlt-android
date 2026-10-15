package dev.zlt.companion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zlt.companion.data.AppContainer
import dev.zlt.companion.data.SavedCode
import dev.zlt.companion.net.UssdResult
import dev.zlt.companion.net.ZltException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UssdUiState(
    val busy: Boolean = false,
    /** What the network last said, if anything. */
    val result: UssdResult? = null,
    /** True right after Cancel, until the next exchange. */
    val cancelled: Boolean = false,
    /** A failure talking to the router, as opposed to a USSD error reply. */
    val error: String? = null,
)

/** USSD, ported from the web dashboard's ussd.js. */
class UssdViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val repository = container.repository
    private val store = container.ussdCodes

    private val _state = MutableStateFlow(UssdUiState())
    val state: StateFlow<UssdUiState> = _state.asStateFlow()

    val savedCodes: StateFlow<List<SavedCode>> =
        store.codes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var job: Job? = null

    init {
        // A menu open on one router means nothing on another.
        container.settingsStore.settings
            .map { it.demoMode to it.credentials() }
            .distinctUntilChanged()
            .drop(1)
            .onEach {
                job?.cancel()
                _state.value = UssdUiState()
            }
            .launchIn(viewModelScope)
    }

    fun send(code: String) {
        val clean = code.trim()
        if (clean.isEmpty()) return
        exchange { repository.ussdSend(clean) }
    }

    fun reply(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        exchange { repository.ussdReply(clean) }
    }

    fun cancel() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            try {
                repository.ussdCancel()
                _state.update { UssdUiState(cancelled = true) }
            } catch (e: ZltException) {
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    private fun exchange(call: suspend () -> UssdResult) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, cancelled = false) }
        job = viewModelScope.launch {
            try {
                val result = call()
                _state.update { UssdUiState(result = result) }
            } catch (e: ZltException) {
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    // Saved codes live on the phone and never touch the router.

    fun saveCode(label: String, code: String) {
        if (label.isBlank() || code.isBlank()) return
        viewModelScope.launch { store.save(label, code) }
    }

    fun removeCode(label: String) {
        viewModelScope.launch { store.remove(label) }
    }
}
