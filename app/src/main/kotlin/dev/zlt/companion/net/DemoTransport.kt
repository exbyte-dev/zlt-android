package dev.zlt.companion.net

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLDecoder

/**
 * A stand-in router, served entirely in-process.
 *
 * This exists so the app can be seen working without the hardware — by anyone
 * evaluating it, and by whoever reviews it for the Play Store, who will not
 * have a ZLT T10D MAX on their desk.
 *
 * It plugs into the `baseClient` parameter [ZltClient] already takes, so it
 * answers at the HTTP layer and nothing above it changes: the login handshake,
 * the flat-JSON parsing, [dev.zlt.companion.data.RouterRepository] and the
 * whole UI run exactly as they do against a real device. A fake further up
 * would skip the protocol layer and drift from it.
 *
 * State is per-instance, so one demo session cannot leak into the next.
 */
class DemoTransport : Interceptor {

    /** proc_get key -> value. Mutable so a mode change visibly takes effect. */
    private val values: MutableMap<String, String> = linkedMapOf(
        "network_type" to "LTE",
        "rssi" to "-58",
        "signalbar" to "4",
        "lte_rsrq" to "-9",
        "lte_pci" to "204",
        // Spelled out rather than imported from ConnectionState, following the
        // convention RouterMock states: these are wire values, and a renamed
        // constant should break a test rather than move both sides at once.
        "ppp_status" to "ppp_connected",
        "lte_rsrp" to "-78",
        "lte_band" to "3",
        "lte_snr" to "14",
        "net_select" to NetworkMode.LTE_ONLY.value,
        "current_network_mode" to "LTE",
        // Empty on this hardware, as on the real device. Present so the
        // repository's "did the device drop the mode keys?" check sees them.
        "net_select_mode" to "",
        "m_netselect_save" to "",
    )

    /**
     * Whether the demo session has logged in. Held here rather than in a cookie
     * so the login handshake is still exercised without cookie plumbing.
     */
    private var loggedIn = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = if (request.method == "POST") handlePost(request) else handleGet(request.url)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody(JSON))
            .build()
    }

    private fun handleGet(url: HttpUrl): String {
        // OkHttp decodes %2C back to a comma for us.
        val cmds = url.queryParameter("cmd").orEmpty().split(',').filter { it.isNotEmpty() }
        return when {
            "get_token" in cmds -> json("token" to if (loggedIn) DEMO_TOKEN else "")
            "get_random_login" in cmds -> json("random_login" to DEMO_NONCE)
            "psw_fail_num_str" in cmds -> json(
                "psw_fail_num_str" to ZltProtocol.MAX_LOGIN_COUNT.toString(),
                "login_lock_time" to ZltProtocol.DEFAULT_LOCK_TIME.toString(),
            )
            // Authed keys read back empty until logged in, exactly as the
            // device does it, so the repository's fallback path stays real.
            else -> json(*cmds.map { it to visibleValue(it) }.toTypedArray())
        }
    }

    private fun visibleValue(key: String): String = when {
        key in ZltProtocol.AUTHED_KEYS && !loggedIn -> ""
        else -> values[key].orEmpty()
    }

    private fun handlePost(request: Request): String {
        val fields = formFields(request)
        return when (fields["goformId"]) {
            ZltProtocol.GOFORM_LOGIN -> {
                loggedIn = true
                json("result" to "0")
            }
            ZltProtocol.GOFORM_SET_BEARER -> {
                val requested = fields["BearerPreference"].orEmpty()
                if (NetworkMode.fromValue(requested) == null) {
                    json("result" to "failure")
                } else {
                    values["net_select"] = requested
                    json("result" to ZltProtocol.RESULT_SUCCESS)
                }
            }
            else -> json("result" to ZltProtocol.RESULT_SUCCESS)
        }
    }

    private fun formFields(request: Request): Map<String, String> {
        val buffer = okio.Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                decode(name) to decode(value)
            }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

    /** The device answers a flat object of string values; so does this. */
    private fun json(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escape(key)}\":\"${escape(value)}\""
        }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val DEMO_TOKEN = "demo-csrf-token"
        private const val DEMO_NONCE = "demo-nonce"

        /**
         * Demo mode never reaches the network, but [ZltClient] still builds URLs
         * from these, so the host has to pass [HostValidation].
         */
        val CREDENTIALS = RouterCredentials(
            host = "192.168.0.1",
            username = "admin",
            password = "demo",
        )

        /** A client whose every request is answered in-process. */
        fun okHttpClient(): OkHttpClient =
            OkHttpClient.Builder().addInterceptor(DemoTransport()).build()
    }
}
