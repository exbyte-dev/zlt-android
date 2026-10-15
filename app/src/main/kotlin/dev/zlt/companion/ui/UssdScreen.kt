package dev.zlt.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zlt.companion.R
import dev.zlt.companion.data.SavedCode
import dev.zlt.companion.net.UssdState

/**
 * USSD: dial a code through the router's SIM, answer a menu, keep favourites.
 *
 * Saved codes are plain one-tap buttons until Manage is switched on, so a
 * remove control never sits next to a button someone meant to press — the
 * same reasoning as the dashboard's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UssdScreen(viewModel: UssdViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val codes by viewModel.savedCodes.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    var managing by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.ussd_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.ussd_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text(stringResource(R.string.ussd_code)) },
                placeholder = { Text("*310#") },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.send(code) }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { viewModel.send(code) }, enabled = code.isNotBlank() && !state.busy) {
                Text(stringResource(R.string.ussd_send))
            }
        }

        SavedCodes(
            codes = codes,
            managing = managing,
            busy = state.busy,
            prefillCode = code,
            onToggleManage = { managing = !managing },
            onSend = { viewModel.send(it.code) },
            onSave = viewModel::saveCode,
            onRemove = { viewModel.removeCode(it.label) },
        )

        if (state.busy) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.ussd_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.error?.let { ResultCard(text = it, isError = true) }

        if (state.cancelled) {
            ResultCard(text = stringResource(R.string.ussd_cancelled), isError = false)
        }

        state.result?.let { result ->
            when (result.state) {
                UssdState.TIMEOUT -> ResultCard(stringResource(R.string.ussd_timeout), isError = true)
                UssdState.ERROR -> ResultCard(stringResource(R.string.ussd_error, result.text), isError = true)
                UssdState.COMPLETE -> ResultCard(result.text, isError = false)
                UssdState.PROMPT -> {
                    ResultCard(result.text, isError = false)
                    ReplyBox(
                        busy = state.busy,
                        onReply = viewModel::reply,
                        onCancel = viewModel::cancel,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(text: String, isError: Boolean) {
    Card(
        colors = if (isError) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Selectable, so a voucher number or balance can be copied out.
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun ReplyBox(busy: Boolean, onReply: (String) -> Unit, onCancel: () -> Unit) {
    var reply by rememberSaveable { mutableStateOf("") }
    val send = {
        if (reply.isNotBlank()) {
            onReply(reply)
            reply = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = reply,
            onValueChange = { reply = it },
            label = { Text(stringResource(R.string.ussd_reply_label)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel, enabled = !busy) {
                Text(stringResource(R.string.ussd_end))
            }
            Button(onClick = send, enabled = reply.isNotBlank() && !busy) {
                Text(stringResource(R.string.ussd_reply))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedCodes(
    codes: List<SavedCode>,
    managing: Boolean,
    busy: Boolean,
    prefillCode: String,
    onToggleManage: () -> Unit,
    onSend: (SavedCode) -> Unit,
    onSave: (label: String, code: String) -> Unit,
    onRemove: (SavedCode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.ussd_saved),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggleManage) {
                Text(stringResource(if (managing) R.string.ussd_manage_done else R.string.ussd_manage))
            }
        }

        if (!managing) {
            if (codes.isEmpty()) {
                Text(
                    text = stringResource(R.string.ussd_saved_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                codes.forEach { saved ->
                    AssistChip(
                        onClick = { onSend(saved) },
                        enabled = !busy,
                        label = { Text(saved.label) },
                    )
                }
            }
            return
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                codes.forEach { saved ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(saved.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = saved.code,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onRemove(saved) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.ussd_remove, saved.label),
                            )
                        }
                    }
                }
                if (codes.isNotEmpty()) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                AddCodeForm(prefillCode = prefillCode, onSave = onSave)
            }
        }
    }
}

@Composable
private fun AddCodeForm(prefillCode: String, onSave: (label: String, code: String) -> Unit) {
    var label by rememberSaveable { mutableStateOf("") }
    // Starts from whatever is in the dial box, the code most likely to be saved.
    var code by rememberSaveable { mutableStateOf(prefillCode.trim()) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val missing = tried && (label.isBlank() || code.isBlank())

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text(stringResource(R.string.ussd_save_label)) },
            placeholder = { Text(stringResource(R.string.ussd_save_label_hint)) },
            singleLine = true,
            isError = missing && label.isBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(stringResource(R.string.ussd_code)) },
            singleLine = true,
            isError = missing && code.isBlank(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            supportingText = if (missing) {
                { Text(stringResource(R.string.ussd_save_needs_both)) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                tried = true
                if (label.isNotBlank() && code.isNotBlank()) {
                    onSave(label, code)
                    label = ""
                    code = ""
                    tried = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.ussd_save))
        }
    }
}
