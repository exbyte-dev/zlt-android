package dev.zlt.companion.data

import android.content.Context

/**
 * The app's single [RouterRepository], and the stores that go with it.
 *
 * One repository means one [dev.zlt.companion.net.ZltClient], so one session
 * cookie, shared by every screen and the Quick Settings tile. Separate clients
 * would each log in on their own: more logins against a device that counts
 * them, and sessions that may displace each other.
 */
class AppContainer private constructor(context: Context) {

    val settingsStore = SettingsStore(context)
    val repository = RouterRepository.from(settingsStore)
    val statusCache = StatusCache(context)
    val ussdCodes = UssdCodeStore(context)

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
