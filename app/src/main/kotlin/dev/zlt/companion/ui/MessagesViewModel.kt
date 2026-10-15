package dev.zlt.companion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zlt.companion.data.AppContainer
import dev.zlt.companion.net.SmsMessage
import dev.zlt.companion.net.ZltException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The outcome of the last thing done to the inbox, for the line under it. */
sealed interface SmsNotice {
    data object Sent : SmsNotice
    data class MarkedRead(val count: Int) : SmsNotice
    data class Deleted(val count: Int) : SmsNotice
    data class Failed(val message: String) : SmsNotice
}

/** What is in flight, so the screen can say so and lock what would race it. */
enum class SmsWork { LOADING, SENDING, MARKING, DELETING }

data class MessagesUiState(
    val messages: List<SmsMessage> = emptyList(),
    val loaded: Boolean = false,
    val work: SmsWork? = null,
    val notice: SmsNotice? = null,
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
    val composing: Boolean = false,
    val draftNumber: String = "",
    val draftText: String = "",
) {
    /**
     * Counted from the rows, never read from the device's `sms_unread_num`,
     * which was seen reporting 0 with unread rows still in the inbox. A badge
     * that disagrees with the list beneath it is worse than none.
     */
    val unread: Int get() = messages.count { it.unread }
    val busy: Boolean get() = work != null
}

/**
 * The inbox, ported from the web dashboard's sms.js.
 *
 * The inbox is read when Messages is first opened (or once after the first good
 * signal poll, to put a number on the tab), after a send, mark-read or delete,
 * and on Refresh — never on the signal poll's timer. An inbox read is slow on
 * this device, and polling both would make them queue behind each other.
 */
class MessagesViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val repository = container.repository

    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        // A different router, or demo mode switched, makes the inbox on screen
        // someone else's. Drop it; the next read fetches the right one.
        container.settingsStore.settings
            .map { it.demoMode to it.credentials() }
            .distinctUntilChanged()
            .drop(1)
            .onEach {
                job?.cancel()
                _state.value = MessagesUiState()
            }
            .launchIn(viewModelScope)
    }

    /** One read, if nothing has loaded yet. A failed read is retried next time. */
    fun ensureLoaded() {
        val current = _state.value
        if (!current.loaded && !current.busy) refresh()
    }

    fun refresh() {
        run(SmsWork.LOADING, clearNotice = false) { }
    }

    fun startCompose(number: String = "") {
        _state.update {
            it.copy(
                composing = true,
                draftNumber = number.ifEmpty { it.draftNumber },
                selecting = false,
                selected = emptySet(),
            )
        }
    }

    fun closeCompose() = _state.update { it.copy(composing = false) }

    fun editDraft(number: String = _state.value.draftNumber, text: String = _state.value.draftText) {
        _state.update { it.copy(draftNumber = number, draftText = text) }
    }

    fun send() {
        val draft = _state.value
        run(SmsWork.SENDING) {
            repository.sendSms(draft.draftNumber, draft.draftText)
            _state.update { it.copy(draftText = "", composing = false, notice = SmsNotice.Sent) }
        }
    }

    // --- selection -------------------------------------------------------------
    // Select mode follows the dashboard's: until you opt in, no destructive
    // control sits next to something you meant to read.

    fun setSelecting(on: Boolean, first: String? = null) {
        _state.update {
            it.copy(
                selecting = on,
                selected = if (on && first != null) setOf(first) else emptySet(),
                composing = if (on) false else it.composing,
                notice = null,
            )
        }
    }

    fun toggle(id: String) = _state.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun toggleAll() = _state.update {
        val all = it.messages.map(SmsMessage::id).toSet()
        it.copy(selected = if (it.selected == all) emptySet() else all)
    }

    fun markSelectedRead() {
        val ids = _state.value.selected
        if (ids.isEmpty()) return
        run(SmsWork.MARKING) {
            val count = repository.markRead(ids)
            _state.update { it.copy(selecting = false, selected = emptySet(), notice = SmsNotice.MarkedRead(count)) }
        }
    }

    fun deleteSelected() {
        val ids = _state.value.selected
        if (ids.isEmpty()) return
        run(SmsWork.DELETING) {
            val count = repository.deleteSms(ids)
            _state.update { it.copy(selecting = false, selected = emptySet(), notice = SmsNotice.Deleted(count)) }
        }
    }

    /**
     * Runs [action], then re-reads the inbox whether or not it worked: after a
     * failure the list may still have changed, and the re-read is also what
     * refreshes the tab badge.
     */
    private fun run(work: SmsWork, clearNotice: Boolean = true, action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(work = work, notice = if (clearNotice) null else it.notice) }
        job = viewModelScope.launch {
            try {
                action()
            } catch (e: ZltException) {
                _state.update { it.copy(notice = SmsNotice.Failed(e.message.orEmpty())) }
            }
            _state.update { it.copy(work = SmsWork.LOADING) }
            try {
                val messages = repository.inbox()
                _state.update { state ->
                    state.copy(
                        messages = messages,
                        loaded = true,
                        // Rows that have gone are no longer selectable.
                        selected = state.selected.intersect(messages.map(SmsMessage::id).toSet()),
                    )
                }
            } catch (e: ZltException) {
                // Keep a send or delete failure on screen over the re-read's.
                _state.update { it.copy(notice = it.notice as? SmsNotice.Failed ?: SmsNotice.Failed(e.message.orEmpty())) }
            } finally {
                _state.update { it.copy(work = null) }
            }
        }
    }
}
