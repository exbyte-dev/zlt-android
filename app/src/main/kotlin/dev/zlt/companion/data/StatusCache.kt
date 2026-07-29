package dev.zlt.companion.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * The last signal reading the app saw.
 *
 * This exists for the Quick Settings tile. Android only calls a tile's
 * `onStartListening` while the panel is actually open, so a tile has no way to
 * keep itself current on a timer without a foreground service — which would be
 * a heavy and Play-scrutinised thing to add for a signal readout. Instead the
 * tile draws this cached value the instant it appears and then refreshes it, so
 * it shows something true-as-of-recently rather than a spinner.
 */
data class CachedSignal(
    val bars: Int = 0,
    val level: SignalLevel? = null,
    val dbm: Int? = null,
    val metricLabel: String? = null,
    val atEpochMs: Long = 0L,
) {
    val hasReading: Boolean get() = level != null
}

private val Context.statusDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "status_cache")

class StatusCache(context: Context) {

    private val store = context.applicationContext.statusDataStore

    suspend fun current(): CachedSignal {
        val prefs = store.data.first()
        val level = prefs[KEY_LEVEL]?.let { name ->
            SignalLevel.entries.firstOrNull { it.name == name }
        }
        return CachedSignal(
            bars = level?.bars ?: 0,
            level = level,
            dbm = prefs[KEY_DBM],
            metricLabel = prefs[KEY_METRIC],
            atEpochMs = prefs[KEY_AT] ?: 0L,
        )
    }

    /** A null reading is stored too: "no signal" is a result worth showing. */
    suspend fun store(reading: SignalReading?) {
        store.edit { prefs ->
            if (reading == null) {
                prefs.remove(KEY_LEVEL)
                prefs.remove(KEY_DBM)
                prefs.remove(KEY_METRIC)
            } else {
                prefs[KEY_LEVEL] = reading.level.name
                prefs[KEY_DBM] = reading.dbm
                prefs[KEY_METRIC] = reading.metric.label
            }
            prefs[KEY_AT] = System.currentTimeMillis()
        }
    }

    private companion object {
        val KEY_LEVEL = stringPreferencesKey("level")
        val KEY_DBM = intPreferencesKey("dbm")
        val KEY_METRIC = stringPreferencesKey("metric")
        val KEY_AT = longPreferencesKey("at")
    }
}
