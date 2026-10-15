package dev.zlt.companion.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit

/**
 * The router's `reqproc` HTTP API, ported from `ZltClient` in zlt/client.py.
 *
 * Deliberately a direct port rather than a reinterpretation: the endpoint
 * shapes, the login handshake, the success codes, the lockout guard and the
 * retry rule all match the Python, which is the live-verified reference. See
 * docs/protocol.md in the zltrouter project for the captured protocol.
 *
 * All IO suspends on [Dispatchers.IO]. Requests are serialised through a mutex
 * for the same reason the Python web layer holds a lock: the device is a single
 * slow embedded box, concurrent calls buy nothing, and the login handshake is a
 * read-then-write sequence that must not interleave with another caller's.
 */
class ZltClient(
    val credentials: RouterCredentials,
    baseClient: OkHttpClient = OkHttpClient(),
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {

    private val cookieJar = SessionCookieJar()

    private val http: OkHttpClient = baseClient.newBuilder()
        .cookieJar(cookieJar)
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        // The router answers every call itself; a redirect would mean something
        // other than the device is on the other end.
        .followRedirects(false)
        .build()

    private val mutex = Mutex()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // --- reads ---------------------------------------------------------------

    /**
     * `GET /reqproc/proc_get?isTest=false[&multi_data=1]&cmd=<comma,joined>`.
     *
     * [multi] defaults to "more than one cmd was asked for", matching the
     * Python. [extra] carries the additional query parameters some aggregate
     * cmds take alongside `cmd`.
     */
    suspend fun get(
        vararg cmds: String,
        multi: Boolean? = null,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> = mutex.withLock { getUnlocked(*cmds, multi = multi, extra = extra) }

    private suspend fun getUnlocked(
        vararg cmds: String,
        multi: Boolean? = null,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> = flatten(getObjectUnlocked(*cmds, multi = multi, extra = extra))

    /**
     * As [get], but the response object is handed back whole. The SMS inbox is
     * the one read whose payload is nested (a `messages` array), which the flat
     * map would reduce to an empty string.
     */
    suspend fun getObject(
        vararg cmds: String,
        extra: Map<String, String> = emptyMap(),
    ): JsonObject = mutex.withLock { getObjectUnlocked(*cmds, extra = extra) }

    private suspend fun getObjectUnlocked(
        vararg cmds: String,
        multi: Boolean? = null,
        extra: Map<String, String> = emptyMap(),
    ): JsonObject {
        val query = StringBuilder("isTest=false")
        // The cmd separator goes out percent-encoded as %2C. That is what the
        // Python sends (requests url-encodes commas) and what the device's own
        // web UI sends (encodeURIComponent does too), so it is the form the
        // firmware is known to accept.
        query.append("&cmd=").append(cmds.joinToString("%2C") { encode(it) })
        if (multi ?: (cmds.size > 1)) query.append("&multi_data=1")
        for ((key, value) in extra) {
            query.append('&').append(encode(key)).append('=').append(encode(value))
        }

        val request = Request.Builder()
            .url("${credentials.baseUrl}/${ZltProtocol.PROC_GET}?$query")
            .headers(deviceHeaders())
            .get()
            .build()
        return parseObject(execute(request))
    }

    /**
     * The CSRF token, and the authoritative "am I logged in" signal: empty means
     * no session. Both key spellings occur in the wild.
     */
    suspend fun token(): String = mutex.withLock { tokenUnlocked() }

    private suspend fun tokenUnlocked(): String {
        val data = getUnlocked("get_token")
        return data["token"] ?: data["get_token"] ?: ""
    }

    /**
     * `(attempts remaining, lockout seconds)`.
     *
     * `psw_fail_num_str` counts attempts *remaining*, not failures. An empty
     * value means the device did not say, which the Python treats as a full
     * allowance; an unparseable one is treated as zero so the guard errs toward
     * refusing to try.
     */
    suspend fun attemptsRemaining(): Pair<Int, Int> =
        mutex.withLock { attemptsRemainingUnlocked() }

    private suspend fun attemptsRemainingUnlocked(): Pair<Int, Int> {
        val data = getUnlocked("psw_fail_num_str", "login_lock_time")
        val rawAttempts = data["psw_fail_num_str"]
        val remaining = when {
            rawAttempts.isNullOrEmpty() -> ZltProtocol.MAX_LOGIN_COUNT
            else -> rawAttempts.toIntOrNull() ?: 0
        }
        val rawLock = data["login_lock_time"]
        val lock = when {
            rawLock.isNullOrEmpty() -> ZltProtocol.DEFAULT_LOCK_TIME
            else -> rawLock.toIntOrNull() ?: ZltProtocol.DEFAULT_LOCK_TIME
        }
        return remaining to lock
    }

    // --- auth ----------------------------------------------------------------

    /**
     * The four-step login from docs/protocol.md: read the lockout counters,
     * fetch the nonce, fetch the CSRF token, then POST the encoded credentials.
     *
     * The lockout read comes first and can abort before any POST is sent. That
     * ordering is the whole point of it: the device allows five failures before
     * locking out for five minutes, and there is no way to probe a password
     * safely, so a run of attempts is refused rather than made.
     */
    suspend fun login(): Map<String, String> = mutex.withLock { loginUnlocked() }

    private suspend fun loginUnlocked(): Map<String, String> {
        if (credentials.password.isEmpty()) {
            throw LoginFailed("No router password saved, so there is nothing to log in with.")
        }

        val (remaining, lock) = attemptsRemainingUnlocked()
        if (remaining < ZltProtocol.MIN_ATTEMPTS_TO_TRY) {
            throw LockedOut(remaining, lock)
        }

        val nonce = getUnlocked("get_random_login")["random_login"].orEmpty()
        if (nonce.isEmpty()) {
            throw LoginFailed("The router did not hand out a login nonce.")
        }

        val data = postRaw(
            linkedMapOf(
                "isTest" to "false",
                "goformId" to ZltProtocol.GOFORM_LOGIN,
                "username" to ZltAuth.encodeUsername(credentials.username),
                "password" to ZltAuth.encodePassword(nonce, credentials.password),
                "CSRFToken" to tokenUnlocked(),
            ),
        )

        val result = data["result"]
        if (result !in ZltProtocol.LOGIN_SUCCESS_RESULTS) {
            throw LoginFailed(
                "The router rejected the password. $remaining attempt(s) were " +
                    "remaining before it locks out.",
            )
        }
        return data
    }

    /** Log in only if there is no session; a non-empty token means there is. */
    suspend fun ensureSession() = mutex.withLock {
        if (tokenUnlocked().isEmpty()) loginUnlocked()
        Unit
    }

    /** Drops the cached session. The next call will log in again. */
    fun forgetSession() = cookieJar.clear()

    // --- writes --------------------------------------------------------------

    /**
     * `POST /reqproc/proc_post` with a session, a fresh CSRF token, and one
     * re-login-and-retry if the device answers with an auth-failure marker.
     */
    suspend fun post(goformId: String, fields: Map<String, String> = emptyMap()): Map<String, String> =
        mutex.withLock {
            if (tokenUnlocked().isEmpty()) loginUnlocked()
            postWithRetry(goformId, fields, retried = false)
        }

    private suspend fun postWithRetry(
        goformId: String,
        fields: Map<String, String>,
        retried: Boolean,
    ): Map<String, String> {
        val body = LinkedHashMap<String, String>()
        body["isTest"] = "false"
        body["goformId"] = goformId
        body.putAll(fields)
        body.putIfAbsent("CSRFToken", tokenUnlocked())

        val data = postRaw(body)
        val result = data["result"].orEmpty().lowercase()
        if (!retried && result in ZltProtocol.AUTH_FAIL_MARKERS) {
            loginUnlocked()
            return postWithRetry(goformId, fields, retried = true)
        }
        return data
    }

    private suspend fun postRaw(fields: Map<String, String>): Map<String, String> {
        val encoded = fields.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val request = Request.Builder()
            .url("${credentials.baseUrl}/${ZltProtocol.PROC_POST}")
            .headers(deviceHeaders())
            .post(encoded.toRequestBody(FORM_MEDIA_TYPE))
            .build()
        return flatten(parseObject(execute(request)))
    }

    // --- plumbing ------------------------------------------------------------

    /** Headers copied from the device's own web UI, as the Python does. */
    private fun deviceHeaders() = okhttp3.Headers.Builder()
        .add("Referer", "${credentials.baseUrl}/index.html")
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { response ->
                response.body?.string().orEmpty()
            }
        } catch (e: UnknownServiceException) {
            // Raised when the platform's network security config refuses the
            // cleartext connection. Nothing is wrong with the network, so this
            // must not be reported as an unreachable router.
            throw CleartextBlocked(credentials.host)
        } catch (e: IOException) {
            throw RouterUnreachable("Could not reach the router at ${credentials.host}.")
        }
    }

    /** Any response that is not a JSON object is not the router talking. */
    private fun parseObject(body: String): JsonObject {
        val element = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw RouterUnreachable(
                "The router sent back something that was not a valid response. " +
                    "Check that ${credentials.host} is really the router's address.",
            )
        }
        return element as? JsonObject ?: throw RouterUnreachable(
            "The router sent back an unexpected response.",
        )
    }

    /**
     * The device answers with a flat JSON object of string values. Nested values
     * flatten to empty rather than failing the whole read; [getObject] is the
     * way to read those.
     */
    private fun flatten(obj: JsonObject): Map<String, String> =
        obj.mapValues { (_, value) -> (value as? JsonPrimitive)?.content ?: "" }

    /**
     * quote_plus semantics, matching what Python's requests sends. URLEncoder
     * differs from it on two characters: it leaves `*` bare and encodes `~`.
     * Both are put back the Python's way, since `*` opens every USSD code and
     * the Python's encoding is the one verified against the device.
     */
    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("*", "%2A").replace("%7E", "~")

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 8L
        private val FORM_MEDIA_TYPE =
            "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
    }
}
