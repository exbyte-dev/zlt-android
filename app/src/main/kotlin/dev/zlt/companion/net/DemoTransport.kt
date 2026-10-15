package dev.zlt.companion.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * the JSON parsing, the SMS and USSD state machines,
 * [dev.zlt.companion.data.RouterRepository] and the whole UI run exactly as
 * they do against a real device. A fake further up would skip the protocol
 * layer and drift from it.
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
     * The demo inbox, newest first, in the device's own row shape: bodies as
     * UCS2 hex, dates comma-separated, `tag` 1 unread / 0 read / 2 sent.
     */
    private val inbox: MutableList<DemoSms> = mutableListOf(
        DemoSms("14", "121", "Your data balance is 4.2 GB, valid until 02 Nov.", "1", "26,10,14,09,12,03,+4"),
        DemoSms("13", "08031234567", "Are you home yet? Call me when you see this.", "1", "26,10,13,18,40,51,+4"),
        DemoSms("12", "08031234567", "On my way", "2", "26,10,13,18,02,10,+4"),
        DemoSms("11", "Info", "Dial *310# any time to check your balance.", "0", "26,10,11,08,00,00,+4"),
    )
    private var nextSmsId = 15

    /** Where the demo USSD session is, so a reply lands on the right menu. */
    private var ussdMenu: UssdMenu? = null
    private var ussdReply: Pair<String, Boolean>? = null

    /**
     * Whether the demo session has logged in. Held here rather than in a cookie
     * so the login handshake is still exercised without cookie plumbing.
     */
    private var loggedIn = false

    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = if (request.method == "POST") handlePost(request) else handleGet(request.url)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toString().toResponseBody(JSON))
            .build()
    }

    private fun handleGet(url: HttpUrl): JsonObject {
        // OkHttp decodes %2C back to a comma for us.
        val cmds = url.queryParameter("cmd").orEmpty().split(',').filter { it.isNotEmpty() }
        return when {
            "get_token" in cmds -> flat("token" to if (loggedIn) DEMO_TOKEN else "")
            "get_random_login" in cmds -> flat("random_login" to DEMO_NONCE)
            "psw_fail_num_str" in cmds -> flat(
                "psw_fail_num_str" to ZltProtocol.MAX_LOGIN_COUNT.toString(),
                "login_lock_time" to ZltProtocol.DEFAULT_LOCK_TIME.toString(),
            )
            "sms_data_total" in cmds -> inboxJson()
            // Every SMS command completes at once in the demo.
            "sms_cmd_status_info" in cmds -> flat(
                "sms_cmd" to url.queryParameter("sms_cmd").orEmpty(),
                "sms_cmd_status_result" to "3",
            )
            "ussd_write_flag" in cmds -> flat("ussd_write_flag" to if (ussdReply != null) "16" else "15")
            "ussd_data_info" in cmds -> {
                val (text, prompt) = ussdReply ?: ("" to false)
                flat(
                    "ussd_data" to DeviceText.encodeSms(text),
                    "ussd_dcs" to "72",
                    "ussd_action" to if (prompt) "1" else "0",
                )
            }
            // Authed keys read back empty until logged in, exactly as the
            // device does it, so the repository's fallback path stays real.
            else -> flat(*cmds.map { it to visibleValue(it) }.toTypedArray())
        }
    }

    private fun visibleValue(key: String): String = when {
        key in ZltProtocol.AUTHED_KEYS && !loggedIn -> ""
        else -> values[key].orEmpty()
    }

    private fun handlePost(request: Request): JsonObject {
        val fields = formFields(request)
        return when (fields["goformId"]) {
            ZltProtocol.GOFORM_LOGIN -> {
                loggedIn = true
                flat("result" to "0")
            }
            ZltProtocol.GOFORM_SET_BEARER -> {
                val requested = fields["BearerPreference"].orEmpty()
                if (NetworkMode.fromValue(requested) == null) {
                    flat("result" to "failure")
                } else {
                    values["net_select"] = requested
                    success()
                }
            }
            "SEND_SMS" -> {
                inbox.add(
                    0,
                    DemoSms(
                        id = (nextSmsId++).toString(),
                        number = fields["Number"].orEmpty(),
                        text = DeviceText.decodeSms(fields["MessageBody"].orEmpty()),
                        tag = "2",
                        date = fields["sms_time"].orEmpty().replace(';', ','),
                    ),
                )
                success()
            }
            "SET_MSG_READ" -> {
                val ids = msgIds(fields)
                inbox.replaceAll { if (it.id in ids && it.tag == "1") it.copy(tag = "0") else it }
                success()
            }
            "DELETE_SMS" -> {
                val ids = msgIds(fields)
                inbox.removeAll { it.id in ids }
                success()
            }
            "USSD_PROCESS" -> {
                when (fields["USSD_operator"]) {
                    "ussd_send" -> ussdSend(fields["USSD_send_number"].orEmpty())
                    "ussd_reply" -> ussdAnswer(fields["USSD_reply_number"].orEmpty().trim())
                    else -> {
                        ussdMenu = null
                        ussdReply = null
                    }
                }
                success()
            }
            else -> success()
        }
    }

    // --- USSD ----------------------------------------------------------------
    // A balance code answers outright; anything else opens a two-level menu, so
    // both the one-shot and the reply paths can be tried.

    private enum class UssdMenu { MAIN, BUNDLES }

    private fun ussdSend(code: String) {
        if (code.trim() == "*310#") {
            ussdMenu = null
            ussdReply = "Main balance: 1,250.00. Data: 4.2 GB, valid until 02 Nov." to false
        } else {
            ussdMenu = UssdMenu.MAIN
            ussdReply = MAIN_MENU to true
        }
    }

    private fun ussdAnswer(choice: String) {
        ussdReply = when (ussdMenu) {
            UssdMenu.MAIN -> when (choice) {
                "1" -> {
                    ussdMenu = UssdMenu.BUNDLES
                    BUNDLE_MENU to true
                }
                "2" -> {
                    ussdMenu = null
                    "Main balance: 1,250.00." to false
                }
                else -> {
                    ussdMenu = null
                    "Session ended." to false
                }
            }
            UssdMenu.BUNDLES -> {
                ussdMenu = null
                if (choice == "1" || choice == "2") {
                    "This is the demo, so nothing was bought." to false
                } else {
                    "Session ended." to false
                }
            }
            null -> "Session ended." to false
        }
    }

    // --- plumbing ------------------------------------------------------------

    private fun inboxJson(): JsonObject = buildJsonObject {
        put(
            "messages",
            buildJsonArray {
                for (sms in inbox) {
                    add(
                        buildJsonObject {
                            put("id", sms.id)
                            put("number", sms.number)
                            put("content", DeviceText.encodeSms(sms.text))
                            put("tag", sms.tag)
                            put("date", sms.date)
                            put("draft_group_id", "")
                        },
                    )
                }
            },
        )
    }

    private fun msgIds(fields: Map<String, String>): Set<String> =
        fields["msg_id"].orEmpty().split(';').filter { it.isNotEmpty() }.toSet()

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

    private fun success() = flat("result" to ZltProtocol.RESULT_SUCCESS)

    /** The device answers a flat object of string values; so does this. */
    private fun flat(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (key, value) -> key to JsonPrimitive(value) })

    private data class DemoSms(
        val id: String,
        val number: String,
        val text: String,
        val tag: String,
        val date: String,
    )

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val DEMO_TOKEN = "demo-csrf-token"
        private const val DEMO_NONCE = "demo-nonce"

        private const val MAIN_MENU = "1. Data bundles\n2. Check balance\n3. Exit"
        private const val BUNDLE_MENU = "1. 1 GB for 7 days\n2. 5 GB for 30 days\n0. Back"

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
