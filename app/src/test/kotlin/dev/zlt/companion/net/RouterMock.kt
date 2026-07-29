package dev.zlt.companion.net

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A stand-in for the device, mirroring tests/router_mock.py from the Python
 * project so both test suites exercise the same wire behaviour.
 *
 * As in the Python mock, the cmd and parameter names are spelled out here
 * rather than imported from [ZltProtocol], so that renaming a constant breaks a
 * test instead of silently moving both sides at once.
 */
class RouterMock : AutoCloseable {

    private val server = MockWebServer()

    /** proc_get key -> value, answered for any cmd that asks for it. */
    val values: MutableMap<String, String> = mutableMapOf()

    /** Bodies queued for successive POSTs; the last one repeats. */
    private val postBodies = ArrayDeque<String>()

    /** Every POST body the client sent, in order. */
    val postsSeen: MutableList<String> = mutableListOf()

    /** Every request the client sent, in order. */
    val requestsSeen: MutableList<RecordedRequest> = mutableListOf()

    private var loginSetsCookie: String? = null
    private var rawGetBody: String? = null
    private var answerAtMostKeys: Int? = null

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestsSeen += request
                return when {
                    request.method == "POST" -> handlePost(request)
                    else -> handleGet(request)
                }
            }
        }
        server.start()
    }

    /** `host:port` in the form [RouterCredentials] wants. */
    val host: String get() = "${server.hostName}:${server.port}"

    fun credentials(username: String = "admin", password: String = "admin") =
        RouterCredentials(host = host, username = username, password = password)

    fun client(username: String = "admin", password: String = "admin") =
        ZltClient(credentials(username, password), timeoutSeconds = 5)

    /** Answer every proc_get with this exact body, valid JSON or not. */
    fun answerRawGet(body: String) {
        rawGetBody = body
    }

    /**
     * Simulate firmware that will not serve an unlimited cmd list: only the
     * first [count] keys of any multi read come back, the rest are dropped
     * from the response entirely.
     */
    fun answerAtMostKeys(count: Int) {
        answerAtMostKeys = count
    }

    /** Queue one POST response body. Call repeatedly for a sequence. */
    fun queuePost(result: String, extra: Map<String, String> = emptyMap()) {
        val fields = (mapOf("result" to result) + extra)
            .entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
        postBodies += "{$fields}"
    }

    /** Make the next successful LOGIN hand back a session cookie. */
    fun setsCookieOnLogin(value: String) {
        loginSetsCookie = value
    }

    private fun handlePost(request: RecordedRequest): MockResponse {
        val body = request.body.readUtf8()
        postsSeen += body
        val response = MockResponse()
            .setResponseCode(200)
            .setBody(if (postBodies.size > 1) postBodies.removeFirst() else postBodies.firstOrNull() ?: "{\"result\":\"success\"}")
        val cookie = loginSetsCookie
        if (cookie != null && body.contains("goformId=LOGIN")) {
            response.addHeader("Set-Cookie", "random=$cookie; path=/")
        }
        return response
    }

    private fun handleGet(request: RecordedRequest): MockResponse {
        rawGetBody?.let { return MockResponse().setResponseCode(200).setBody(it) }

        val cmd = request.requestUrl?.queryParameter("cmd").orEmpty()
        val body = when (cmd) {
            "get_random_login" -> jsonOf(mapOf("random_login" to values.getOrDefault("random_login", "")))
            "get_token" -> if (values.containsKey("token")) {
                jsonOf(mapOf("token" to values.getValue("token")))
            } else {
                jsonOf(mapOf("get_token" to values.getOrDefault("get_token", "")))
            }
            else -> {
                val keys = cmd.split(",").let { all ->
                    answerAtMostKeys?.let { all.take(it) } ?: all
                }
                jsonOf(keys.associateWith { values.getOrDefault(it, "") })
            }
        }
        return MockResponse().setResponseCode(200).setBody(body)
    }

    private fun jsonOf(map: Map<String, String>): String =
        map.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }

    /** POST bodies only, the equivalent of the Python's `_post_bodies()`. */
    fun postCount(): Int = postsSeen.size

    override fun close() {
        server.shutdown()
    }
}
