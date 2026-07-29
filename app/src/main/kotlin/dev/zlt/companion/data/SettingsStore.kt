package dev.zlt.companion.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.zlt.companion.net.HostValidation
import dev.zlt.companion.net.RouterCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What the user has told the app about their router.
 *
 * There is no config file and no hardcoded address: [DEFAULT_HOST] is only the
 * value the field starts out holding, and it is editable like any other.
 */
data class RouterSettings(
    val host: String = DEFAULT_HOST,
    val username: String = DEFAULT_USERNAME,
    val password: String = "",
    val pollSeconds: Int = DEFAULT_POLL_SECONDS,
) {
    /** Both an address we will talk to and a password to talk with. */
    val isConfigured: Boolean
        get() = HostValidation.isValid(host) && password.isNotEmpty()

    fun credentials(): RouterCredentials =
        RouterCredentials(host = host, username = username, password = password)

    companion object {
        /** The usual gateway for this hardware. A starting point, not a constraint. */
        const val DEFAULT_HOST = "192.168.0.1"
        const val DEFAULT_USERNAME = "admin"
        const val DEFAULT_POLL_SECONDS = 5

        val POLL_CHOICES = listOf(3, 5, 10, 30)
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "router")

class SettingsStore(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<RouterSettings> = store.data.map { prefs ->
        RouterSettings(
            host = prefs[KEY_HOST] ?: RouterSettings.DEFAULT_HOST,
            username = prefs[KEY_USERNAME] ?: RouterSettings.DEFAULT_USERNAME,
            // Stored sealed; an unrecoverable value reads back empty, which
            // presents as "not configured" and re-prompts.
            password = SecretCipher.open(prefs[KEY_PASSWORD].orEmpty()),
            pollSeconds = prefs[KEY_POLL_SECONDS] ?: RouterSettings.DEFAULT_POLL_SECONDS,
        )
    }

    suspend fun current(): RouterSettings = settings.first()

    suspend fun save(host: String, username: String, password: String) {
        val cleanHost = HostValidation.normalize(host)
        val cleanUsername = username.trim().ifEmpty { RouterSettings.DEFAULT_USERNAME }
        store.edit { prefs ->
            prefs[KEY_HOST] = cleanHost
            prefs[KEY_USERNAME] = cleanUsername
            prefs[KEY_PASSWORD] = SecretCipher.seal(password)
        }
    }

    suspend fun setPollSeconds(seconds: Int) {
        store.edit { prefs -> prefs[KEY_POLL_SECONDS] = seconds }
    }

    private companion object {
        val KEY_HOST = stringPreferencesKey("host")
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_PASSWORD = stringPreferencesKey("password_sealed")
        val KEY_POLL_SECONDS = intPreferencesKey("poll_seconds")
    }
}
