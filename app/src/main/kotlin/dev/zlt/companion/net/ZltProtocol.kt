package dev.zlt.companion.net

/**
 * Protocol constants, ported from zlt/client.py and docs/protocol.md.
 *
 * Everything device-specific lives here, matching how the Python project keeps
 * its constants in one block at the top of client.py: a firmware that differs
 * should need changes in this file and nowhere else.
 */
object ZltProtocol {

    const val PROC_GET = "reqproc/proc_get"
    const val PROC_POST = "reqproc/proc_post"

    /** Status keys the device answers without a session. */
    val OPEN_KEYS = listOf(
        "network_type", "rssi", "signalbar", "lte_rsrq", "lte_pci", "ppp_status",
    )

    /** Status keys that come back empty until logged in. */
    val AUTHED_KEYS = listOf("lte_rsrp", "lte_band", "lte_snr")

    /**
     * Keys queried to resolve the configured network mode. `net_select` is the
     * one actually populated on this hardware; the other two are kept as
     * fallbacks for other firmware builds, in the Python's resolution order.
     */
    val NET_KEYS = listOf(
        "current_network_mode", "net_select_mode", "m_netselect_save", "net_select",
    )

    const val GOFORM_LOGIN = "LOGIN"
    const val GOFORM_SET_BEARER = "SET_BEARER_PREFERENCE"

    /** Attempts remaining below this and the client refuses to try at all. */
    const val MIN_ATTEMPTS_TO_TRY = 2
    const val MAX_LOGIN_COUNT = 5
    const val DEFAULT_LOCK_TIME = 300

    /** LOGIN answers "0" on a fresh login and "4" when already logged in. */
    val LOGIN_SUCCESS_RESULTS = setOf("0", "4")

    /** Non-LOGIN writes answer with this instead. */
    const val RESULT_SUCCESS = "success"

    /**
     * Best-effort markers that a write was refused for want of a session. The
     * primary authentication check is always a non-empty `get_token`; this set
     * is only a backstop that triggers one re-login and retry.
     */
    val AUTH_FAIL_MARKERS = setOf("no_session", "session_error", "need_login", "not_login", "-1")
}

/**
 * USSD, ported from the USSD block at the top of zlt/client.py.
 *
 * Captured live from the device's own js/service.js: a POST starts the
 * session, then `ussd_write_flag` is polled on its own until it reports a reply
 * is ready, and the reply is fetched with `ussd_data_info`, also on its own.
 * Both reads are aggregate cmds that must not be batched with anything else.
 */
object UssdProtocol {
    const val GOFORM = "USSD_PROCESS"
    const val OPERATOR_FIELD = "USSD_operator"
    const val SEND_FIELD = "USSD_send_number"
    const val REPLY_FIELD = "USSD_reply_number"
    const val OP_SEND = "ussd_send"
    const val OP_REPLY = "ussd_reply"
    const val OP_CANCEL = "ussd_cancel"

    const val FLAG_KEY = "ussd_write_flag"
    const val DATA_CMD = "ussd_data_info"
    const val DATA_FIELD = "ussd_data"
    const val ACTION_FIELD = "ussd_action"
    const val DCS_FIELD = "ussd_dcs"

    const val FLAG_PENDING = "15"
    const val FLAG_RECEIVED = "16"
    val FLAG_TIMEOUT = setOf("3", "4", "unknown")
    val FLAG_ERRORS = mapOf(
        "1" to "no service",
        "2" to "network terminated",
        "10" to "retry",
        "41" to "operation not supported",
        "99" to "unsupported",
    )

    /** `ussd_action` "1" means the network wants a reply; "0" is final. */
    const val ACTION_PROMPT = "1"

    /** `(dcs & 0x0C) == 0x08` means UCS2, e.g. 0x48 ("72"). */
    const val DCS_UCS2_MASK = 0x0C
    const val DCS_UCS2_VALUE = 0x08

    const val TIMEOUT_MS = 20_000L
    const val POLL_INTERVAL_MS = 1_000L
}

/**
 * SMS, ported from the SMS block at the top of zlt/client.py and
 * docs/protocol.md. All of it needs a session.
 */
object SmsProtocol {
    /** Aggregate cmd that takes its own query parameters alongside `cmd`. */
    const val LIST_CMD = "sms_data_total"
    const val STATUS_CMD = "sms_cmd_status_info"

    // Read parameters. data_per_page is advisory: the device answered ten rows
    // when asked for three, so the cap is applied again client-side.
    const val MEM_STORE = "1"
    const val TAGS_ALL = "10"
    const val ORDER_BY = "order by id desc"
    const val PAGE_SIZE = "500"

    /** `tag` "1" is an unread inbox message; 2, 3 and 4 are outgoing folders. */
    const val TAG_UNREAD = "1"
    val TAGS_OUTGOING = setOf("2", "3", "4")

    const val SEND_GOFORM = "SEND_SMS"
    /** A new message rather than an edited draft. */
    const val NEW_ID = "-1"
    const val SEND_SLOT = "4"

    const val READ_GOFORM = "SET_MSG_READ"
    const val READ_TAG = "0"
    const val DELETE_GOFORM = "DELETE_SMS"
    const val DELETE_SLOT = "6"

    const val STATUS_FIELD = "sms_cmd_status_result"
    const val STATUS_FAILED = "2"
    const val STATUS_DONE = "3"

    const val ENCODE_GSM7 = "GSM7_default"
    const val ENCODE_UNICODE = "UNICODE"

    /** The stock UI waits this long before its first status poll. */
    const val SETTLE_MS = 1_000L
    const val TIMEOUT_MS = 30_000L
    const val POLL_INTERVAL_MS = 3_000L

    /**
     * GSM 03.38 basic set, lifted verbatim from the device's GSM7_Table. A body
     * made entirely of these goes out as GSM7 (160 characters per part);
     * anything else has to be UNICODE (70 per part), so this decides the cost.
     */
    const val GSM7_CHARS =
        "\n\u000c\r !\"#\$%&'()*+,-./0123456789:;<=>?@" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_" +
            "abcdefghijklmnopqrstuvwxyz{|}~" +
            "\u00a0¡£¤¥§¿ÄÅÆÇÉÑÖØÜßàäåæèéìñòöøùüΓΔΘΛΞΠΣΦΨΩ€"
}

/**
 * The five distinct BearerPreference values the device accepts. The Python maps
 * eight friendly aliases onto these; the app only needs the canonical five, each
 * with the label the stock web UI uses.
 *
 * Note that 3G is `TD_W`, not `Only_WCDMA` — this is called out explicitly in
 * the Python's protocol notes as a live-verified correction.
 */
enum class NetworkMode(val value: String, val label: String) {
    AUTO("NETWORK_auto", "Automatic"),
    LTE_ONLY("Only_LTE", "4G only"),
    LTE_3G("TD_W_LTE", "4G and 3G"),
    WCDMA_ONLY("TD_W", "3G only"),
    GSM_ONLY("Only_GSM", "2G only");

    companion object {
        fun fromValue(value: String): NetworkMode? = entries.firstOrNull { it.value == value }
    }
}

/** Credentials for one router. */
data class RouterCredentials(
    val host: String,
    val username: String,
    val password: String,
) {
    /** The origin the Python's Config.host holds, e.g. `http://192.168.0.1`. */
    val baseUrl: String get() = "http://$host"
}

sealed class ZltException(message: String) : Exception(message)

/** Network failure, timeout, or a response that was not JSON at all. */
class RouterUnreachable(message: String) : ZltException(message)

/**
 * The platform blocked the request because the address is not in the app's
 * permitted-cleartext list. Distinct from [RouterUnreachable] because the fix is
 * completely different: nothing is wrong with the network.
 */
class CleartextBlocked(val host: String) : ZltException(
    "Android blocked the connection to $host because it is not in the app's " +
        "list of permitted local addresses.",
)

/** Login was rejected, or could not be attempted for want of a password. */
class LoginFailed(message: String) : ZltException(message)

/** Refusing to log in: too few attempts remain before the device locks out. */
class LockedOut(
    val attemptsRemaining: Int,
    val lockSeconds: Int,
) : ZltException(
    "Only $attemptsRemaining login attempt(s) remain before the router locks " +
        "out for $lockSeconds seconds, so no attempt was made.",
)

/** The router answered a write with something other than success. */
class RouterRejected(message: String) : ZltException(message)

/**
 * The device answered a USSD or SMS read in a shape this app does not know.
 * Raised rather than polling to a timeout or showing an empty inbox, because it
 * means the firmware speaks a different API from the one captured.
 */
class UnsupportedResponse(message: String) : ZltException(message)

/** An SMS could not be sent, marked read or deleted. */
class SmsFailed(message: String) : ZltException(message)
