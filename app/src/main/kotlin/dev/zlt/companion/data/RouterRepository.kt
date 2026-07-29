package dev.zlt.companion.data

import dev.zlt.companion.net.LockedOut
import dev.zlt.companion.net.LoginFailed
import dev.zlt.companion.net.RouterCredentials
import dev.zlt.companion.net.ZltClient
import dev.zlt.companion.net.ZltProtocol
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the [ZltClient] and turns raw key/value reads into the app's models.
 *
 * The client is rebuilt whenever the saved credentials change, which also drops
 * the session cookie — the old one belongs to a different router.
 */
class RouterRepository(private val settingsStore: SettingsStore) {

    private val mutex = Mutex()
    private var cachedClient: ZltClient? = null
    private var cachedCredentials: RouterCredentials? = null

    private suspend fun client(): ZltClient {
        val credentials = settingsStore.current().credentials()
        return mutex.withLock {
            val existing = cachedClient
            if (existing != null && cachedCredentials == credentials) {
                existing
            } else {
                ZltClient(credentials).also {
                    cachedClient = it
                    cachedCredentials = credentials
                }
            }
        }
    }

    /**
     * One status poll.
     *
     * Ported from `status` in zlt/cli.py: try for a session and read the full
     * key set, and on a login problem fall back to the open keys with a note
     * rather than failing outright. Losing RSRP, band and SNR still leaves a
     * usable RSSI reading, which beats an error screen. A genuinely unreachable
     * router propagates.
     */
    suspend fun status(): RouterStatus {
        val client = client()
        var note: String? = null
        val data = try {
            client.ensureSession()
            client.get(*(ZltProtocol.OPEN_KEYS + ZltProtocol.AUTHED_KEYS).toTypedArray())
        } catch (e: LoginFailed) {
            note = e.message
            null
        } catch (e: LockedOut) {
            note = e.message
            null
        }
        if (data != null) return RouterStatus.from(data, authed = true, note = null)

        val open = client.get(*ZltProtocol.OPEN_KEYS.toTypedArray())
        return RouterStatus.from(open, authed = false, note = note)
    }

    /** The configured network mode. Needs a session. */
    suspend fun mode(): NetModeState {
        val client = client()
        client.ensureSession()
        return NetModeState.from(client.get(*ZltProtocol.NET_KEYS.toTypedArray()))
    }
}
