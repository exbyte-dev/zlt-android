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

    /**
     * The raw rows handed back for `cmd=sms_data_total`, in the device's own
     * shape (content as UCS2 hex, number as plain text). Null answers the
     * inbox read like any other cmd, with no `messages` key.
     */
    var smsRows: List<Map<String, String>>? = null

    /**
     * `sms_cmd_status_result` values for successive status polls, the last
     * repeating. A null entry reproduces what the live device answers when
     * nothing is in flight: `{"messages": []}` with no status key at all.
     */
    var smsStatuses: List<String?> = listOf("3")
    private var smsStatusIndex = 0

    /** `ussd_write_flag` values for successive polls, the last repeating. */
    var ussdFlags: List<String>? = null
    private var ussdFlagIndex = 0

    /** The body of `cmd=ussd_data_info`. */
    var ussdData: Map<String, String> = emptyMap()

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
        val body = when {
            cmd == "sms_data_total" && smsRows != null -> smsRows!!.joinToString(",", "{\"messages\":[", "]}") { jsonOf(it) }
            cmd == "sms_cmd_status_info" -> {
                val status = smsStatuses[minOf(smsStatusIndex++, smsStatuses.size - 1)]
                if (status == null) "{\"messages\":[]}" else jsonOf(mapOf("sms_cmd" to "4", "sms_cmd_status_result" to status))
            }
            cmd == "ussd_write_flag" && ussdFlags != null -> {
                val flags = ussdFlags!!
                jsonOf(mapOf("ussd_write_flag" to flags[minOf(ussdFlagIndex++, flags.size - 1)]))
            }
            cmd == "ussd_data_info" -> jsonOf(ussdData)
            else -> handleKeys(cmd)
        }
        return MockResponse().setResponseCode(200).setBody(body)
    }

    /** Requests whose cmd was exactly [cmd], in order. */
    fun getsFor(cmd: String): List<RecordedRequest> =
        requestsSeen.filter { it.method == "GET" && it.requestUrl?.queryParameter("cmd") == cmd }

    private fun handleKeys(cmd: String): String {
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
        return body
    }

    private fun jsonOf(map: Map<String, String>): String =
        map.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }

    /** POST bodies only, the equivalent of the Python's `_post_bodies()`. */
    fun postCount(): Int = postsSeen.size

    override fun close() {
        server.shutdown()
    }
}
