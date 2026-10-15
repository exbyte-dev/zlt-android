package dev.zlt.companion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zlt.companion.R
import dev.zlt.companion.data.SmsLength
import dev.zlt.companion.net.SmsMessage
import kotlinx.coroutines.launch

/**
 * The router's SMS inbox, with compose, mark read and delete.
 *
 * Sender and body arrive off the mobile network, so they are only ever shown
 * as plain text — the one thing on screen a stranger gets to write.
 */
@Composable
fun MessagesScreen(viewModel: MessagesViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(pluralStringResource(R.plurals.sms_delete_title, state.selected.size, state.selected.size))
            },
            text = { Text(stringResource(R.string.sms_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteSelected()
                }) {
                    Text(
                        stringResource(R.string.sms_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.sms_cancel))
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            item(key = "header") {
                InboxHeader(
                    state = state,
                    onRefresh = viewModel::refresh,
                    onToggleSelect = { viewModel.setSelecting(!state.selecting) },
                    onToggleAll = viewModel::toggleAll,
                )
            }
            item(key = "notice") { NoticeLine(state) }
            if (!state.selecting) {
                item(key = "compose") {
                    Composer(
                        state = state,
                        onOpen = { viewModel.startCompose() },
                        onClose = viewModel::closeCompose,
                        onNumber = { viewModel.editDraft(number = it) },
                        onText = { viewModel.editDraft(text = it) },
                        onSend = viewModel::send,
                    )
                }
            }
            if (state.loaded && state.messages.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.sms_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(state.messages, key = { it.id }) { message ->
                MessageRow(
                    message = message,
                    selecting = state.selecting,
                    selected = message.id in state.selected,
                    enabled = !state.busy,
                    onToggle = { viewModel.toggle(message.id) },
                    onLongPress = { if (!state.selecting) viewModel.setSelecting(true, first = message.id) },
                    onReply = {
                        viewModel.startCompose(number = message.number)
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                )
            }
        }
        if (state.selecting && state.selected.isNotEmpty()) {
            SelectionBar(
                count = state.selected.size,
                enabled = !state.busy,
                onMarkRead = viewModel::markSelectedRead,
                onDelete = { confirmDelete = true },
            )
        }
    }
}

@Composable
private fun InboxHeader(
    state: MessagesUiState,
    onRefresh: () -> Unit,
    onToggleSelect: () -> Unit,
    onToggleAll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.sms_title), style = MaterialTheme.typography.titleLarge)
            if (state.unread > 0) {
                Text(
                    text = stringResource(R.string.sms_unread_count, state.unread),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (state.selecting) {
            val everyOne = state.messages.isNotEmpty() && state.selected.size == state.messages.size
            TextButton(onClick = onToggleAll, enabled = state.messages.isNotEmpty()) {
                Text(stringResource(if (everyOne) R.string.sms_select_none else R.string.sms_select_all))
            }
        } else {
            TextButton(onClick = onRefresh, enabled = !state.busy) {
                Text(stringResource(R.string.sms_refresh))
            }
        }
        TextButton(onClick = onToggleSelect, enabled = state.messages.isNotEmpty() || state.selecting) {
            Text(stringResource(if (state.selecting) R.string.sms_done else R.string.sms_select))
        }
    }
}

@Composable
private fun NoticeLine(state: MessagesUiState) {
    val (text, isError) = when (val notice = state.notice) {
        null -> when (state.work) {
            SmsWork.LOADING -> stringResource(R.string.sms_loading) to false
            SmsWork.SENDING -> stringResource(R.string.sms_sending) to false
            SmsWork.MARKING -> stringResource(R.string.sms_marking) to false
            SmsWork.DELETING -> stringResource(R.string.sms_deleting) to false
            null -> return
        }
        SmsNotice.Sent -> stringResource(R.string.sms_sent) to false
        is SmsNotice.MarkedRead -> pluralStringResource(R.plurals.sms_marked, notice.count, notice.count) to false
        is SmsNotice.Deleted -> pluralStringResource(R.plurals.sms_deleted, notice.count, notice.count) to false
        is SmsNotice.Failed -> notice.message to true
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Composer(
    state: MessagesUiState,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onNumber: (String) -> Unit,
    onText: (String) -> Unit,
    onSend: () -> Unit,
) {
    if (!state.composing) {
        OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sms_new))
        }
        return
    }
    val length = SmsLength.of(state.draftText)
    val parts = pluralStringResource(R.plurals.sms_parts, length.parts, length.parts)
    val ready = state.draftNumber.isNotBlank() && state.draftText.isNotBlank()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = state.draftNumber,
                onValueChange = onNumber,
                label = { Text(stringResource(R.string.sms_to)) },
                placeholder = { Text(stringResource(R.string.sms_to_hint)) },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.draftText,
                onValueChange = onText,
                label = { Text(stringResource(R.string.sms_body)) },
                minLines = 3,
                enabled = !state.busy,
                supportingText = {
                    Text(
                        stringResource(
                            if (length.unicode) R.string.sms_length_unicode else R.string.sms_length,
                            pluralStringResource(R.plurals.sms_characters, length.characters, length.characters),
                            parts,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.sms_cost_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onClose, enabled = !state.busy) {
                    Text(stringResource(R.string.sms_cancel))
                }
                Button(onClick = onSend, enabled = ready && !state.busy) {
                    Text(stringResource(R.string.sms_send))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: SmsMessage,
    selecting: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onReply: () -> Unit,
) {
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(
        colors = colors,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
                onClick = { if (selecting) onToggle() },
                onLongClick = onLongPress,
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = if (selecting) 4.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = enabled)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (message.unread) {
                        val unread = stringResource(R.string.sms_unread_marker)
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .semantics { contentDescription = unread },
                        )
                    }
                    Text(
                        text = if (message.outgoing) {
                            stringResource(R.string.sms_to_prefix, message.number)
                        } else {
                            message.number
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (message.unread) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = message.date,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
                if (!selecting && !message.outgoing) {
                    TextButton(
                        onClick = onReply,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text(stringResource(R.string.sms_reply))
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(count: Int, enabled: Boolean, onMarkRead: () -> Unit, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.sms_selected_count, count),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onMarkRead, enabled = enabled) {
                Text(stringResource(R.string.sms_mark_read))
            }
            TextButton(onClick = onDelete, enabled = enabled) {
                Text(stringResource(R.string.sms_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
