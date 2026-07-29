package dev.zlt.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.zlt.companion.R
import dev.zlt.companion.data.RouterSettings
import dev.zlt.companion.net.HostValidation

/**
 * Where the router address, password and (behind Advanced) username are
 * entered. Doubles as first-run setup and later editing, since the fields and
 * the validation are identical either way.
 */
@Composable
fun SettingsScreen(
    settings: RouterSettings,
    showPollInterval: Boolean,
    onSave: (host: String, username: String, password: String) -> Unit,
    onPollSecondsChange: (Int) -> Unit,
    onBack: (() -> Unit)?,
    /** Null once the app is set up for real — only offered during first setup. */
    onTryDemo: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var host by remember(settings.host) { mutableStateOf(settings.host) }
    var username by remember(settings.username) { mutableStateOf(settings.username) }
    var password by remember(settings.password) { mutableStateOf(settings.password) }
    var showAdvanced by remember { mutableStateOf(settings.username != RouterSettings.DEFAULT_USERNAME) }
    var passwordVisible by remember { mutableStateOf(false) }
    var attemptedSave by remember { mutableStateOf(false) }

    val normalizedHost = HostValidation.normalize(host)
    val hostError = if (attemptedSave) HostValidation.reasonInvalid(normalizedHost) else null
    val passwordError = if (attemptedSave && password.isEmpty()) {
        stringResource(R.string.setup_password_required)
    } else {
        null
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(
                    if (onBack == null) R.string.setup_title else R.string.settings_title,
                ),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.setup_intro, stringResource(R.string.device_name)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(R.string.setup_host_label)) },
                supportingText = {
                    Text(hostError ?: stringResource(R.string.setup_host_hint))
                },
                isError = hostError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.setup_password_label)) },
                supportingText = {
                    Text(passwordError ?: stringResource(R.string.setup_password_hint))
                },
                isError = passwordError != null,
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(
                            stringResource(
                                if (passwordVisible) {
                                    R.string.setup_hide_password
                                } else {
                                    R.string.setup_show_password
                                },
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // The device allows five wrong passwords before a timed lockout, so
            // say so where the password is typed rather than after it bites.
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.lockout_warning),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }

            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(stringResource(R.string.setup_advanced))
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.setup_username_label)) },
                    supportingText = { Text(stringResource(R.string.setup_username_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (showPollInterval) {
                Text(
                    text = stringResource(R.string.settings_refresh_rate),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RouterSettings.POLL_CHOICES.forEach { seconds ->
                        FilterChip(
                            selected = settings.pollSeconds == seconds,
                            onClick = { onPollSecondsChange(seconds) },
                            label = { Text(stringResource(R.string.settings_seconds, seconds)) },
                        )
                    }
                }
            }

            Button(
                onClick = {
                    attemptedSave = true
                    if (HostValidation.isValid(normalizedHost) && password.isNotEmpty()) {
                        onSave(normalizedHost, username, password)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.setup_save))
            }

            if (onBack != null) {
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_back))
                }
            }

            // Offered only during first setup, so the app can be evaluated —
            // and reviewed for the Play Store — without the hardware.
            if (onTryDemo != null) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.demo_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onTryDemo, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.demo_try))
                }
            }

            Text(
                text = stringResource(R.string.settings_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
