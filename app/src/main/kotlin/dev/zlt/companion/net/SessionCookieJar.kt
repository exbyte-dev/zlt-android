package dev.zlt.companion.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the router's session cookie (named `random`) for the life of the
 * process.
 *
 * The Python client persists cookies to disk so a short-lived CLI invocation
 * does not log in every time. That reasoning does not carry over: this app is a
 * long-running process, and `ensureSession()` re-logs in automatically the
 * moment `get_token` comes back empty, so the cost of not persisting is at most
 * one extra login per app start — three GETs and a POST. Weighed against
 * writing a live authentication cookie to disk, keeping it in memory is the
 * better trade.
 *
 * Cookies are stored by name and returned for any request to the same host,
 * which is what the device's own flat, path-less session cookie expects.
 */
class SessionCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            store[cookie.name] = cookie
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store.values.toList()

    /** Drops the session, forcing the next call to log in again. */
    fun clear() {
        store.clear()
    }

    /** Visible for tests and diagnostics. */
    fun names(): Set<String> = store.keys.toSet()
}
