package dev.zlt.companion.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A USSD code the user saved under a name, e.g. "Balance" -> `*310#`. */
data class SavedCode(val label: String, val code: String)

/**
 * The saved-code list rules, ported from zlt/ussd_store.py: labels are unique
 * ignoring case, saving an existing label replaces its code, and the file
 * format is the same `[{"label", "code"}]` array, so a list copied over from
 * `~/.config/zlt/ussd.json` reads as-is.
 *
 * Kept free of Android types so it is testable on the JVM.
 */
object SavedCodes {

    fun parse(raw: String): List<SavedCode> {
        if (raw.isBlank()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray
            ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val label = (obj["label"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val code = (obj["code"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            SavedCode(label, code)
        }
    }

    fun serialize(codes: List<SavedCode>): String = buildJsonArray {
        for (c in codes) {
            add(
                buildJsonObject {
                    put("label", c.label)
                    put("code", c.code)
                },
            )
        }
    }.toString()

    fun upsert(codes: List<SavedCode>, label: String, code: String): List<SavedCode> {
        val entry = SavedCode(label.trim(), code.trim())
        val index = codes.indexOfFirst { it.label.equals(entry.label, ignoreCase = true) }
        return if (index >= 0) {
            codes.toMutableList().also { it[index] = entry }
        } else {
            codes + entry
        }
    }

    fun remove(codes: List<SavedCode>, label: String): List<SavedCode> =
        codes.filterNot { it.label.equals(label.trim(), ignoreCase = true) }
}

private val Context.ussdDataStore: DataStore<Preferences> by preferencesDataStore(name = "ussd_codes")

/** Saved USSD codes, kept on the phone alongside the router settings. */
class UssdCodeStore(context: Context) {

    private val store = context.applicationContext.ussdDataStore

    val codes: Flow<List<SavedCode>> = store.data.map { SavedCodes.parse(it[KEY_CODES].orEmpty()) }

    suspend fun save(label: String, code: String) = update { SavedCodes.upsert(it, label, code) }

    suspend fun remove(label: String) = update { SavedCodes.remove(it, label) }

    private suspend fun update(change: (List<SavedCode>) -> List<SavedCode>) {
        store.edit { prefs ->
            prefs[KEY_CODES] = SavedCodes.serialize(change(SavedCodes.parse(prefs[KEY_CODES].orEmpty())))
        }
    }

    private companion object {
        val KEY_CODES = stringPreferencesKey("codes_json")
    }
}
