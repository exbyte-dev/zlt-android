package dev.zlt.companion.net

import kotlinx.coroutines.delay

/** What a USSD exchange came back with. */
data class UssdResult(val text: String, val state: UssdState)

enum class UssdState {
    /** A final answer; the session is over. */
    COMPLETE,

    /** The network is showing a menu and wants a reply. */
    PROMPT,

    /** The device reported an error; [UssdResult.text] says which. */
    ERROR,

    /** Nothing came back in time. */
    TIMEOUT,
}

/**
 * USSD, ported from `ussd_send` / `ussd_reply` / `ussd_cancel` and their poll
 * in zlt/client.py.
 *
 * Written as extensions over [ZltClient.post] and [ZltClient.get], so every
 * request still goes through the client's session handling and its mutex. The
 * mutex is held per request rather than across the whole exchange, which lets
 * the signal poll keep running while a slow USSD reply is awaited.
 */
suspend fun ZltClient.ussdSend(
    code: String,
    timeoutMs: Long = UssdProtocol.TIMEOUT_MS,
    intervalMs: Long = UssdProtocol.POLL_INTERVAL_MS,
): UssdResult {
    post(
        UssdProtocol.GOFORM,
        mapOf(
            UssdProtocol.OPERATOR_FIELD to UssdProtocol.OP_SEND,
            UssdProtocol.SEND_FIELD to code,
        ),
    )
    return ussdPoll(timeoutMs, intervalMs)
}

suspend fun ZltClient.ussdReply(
    text: String,
    timeoutMs: Long = UssdProtocol.TIMEOUT_MS,
    intervalMs: Long = UssdProtocol.POLL_INTERVAL_MS,
): UssdResult {
    post(
        UssdProtocol.GOFORM,
        mapOf(
            UssdProtocol.OPERATOR_FIELD to UssdProtocol.OP_REPLY,
            UssdProtocol.REPLY_FIELD to text,
        ),
    )
    return ussdPoll(timeoutMs, intervalMs)
}

suspend fun ZltClient.ussdCancel() {
    post(UssdProtocol.GOFORM, mapOf(UssdProtocol.OPERATOR_FIELD to UssdProtocol.OP_CANCEL))
}

private suspend fun ZltClient.ussdPoll(timeoutMs: Long, intervalMs: Long): UssdResult =
    pollFor(timeoutMs, intervalMs) {
        when (val flag = classifyUssdFlag(get(UssdProtocol.FLAG_KEY))) {
            UssdFlag.Received -> readUssdData()
            UssdFlag.Timeout -> UssdResult("", UssdState.TIMEOUT)
            is UssdFlag.Error -> UssdResult(flag.reason, UssdState.ERROR)
            UssdFlag.Pending -> null
        }
    } ?: UssdResult("", UssdState.TIMEOUT)

private suspend fun ZltClient.readUssdData(): UssdResult {
    val data = get(UssdProtocol.DATA_CMD)
    val text = DeviceText.decodeUssd(
        data[UssdProtocol.DATA_FIELD].orEmpty(),
        data[UssdProtocol.DCS_FIELD].orEmpty(),
    )
    val action = data[UssdProtocol.ACTION_FIELD].orEmpty().trim()
    val state = if (action == UssdProtocol.ACTION_PROMPT) UssdState.PROMPT else UssdState.COMPLETE
    return UssdResult(text, state)
}

internal sealed interface UssdFlag {
    data object Received : UssdFlag
    data object Timeout : UssdFlag
    data object Pending : UssdFlag
    data class Error(val reason: String) : UssdFlag
}

/**
 * Maps a `ussd_write_flag` poll answer, as `_classify_flag` does.
 *
 * A response with no flag key at all means the device speaks a different USSD
 * API from the one captured. Failing at once beats polling to the timeout.
 */
internal fun classifyUssdFlag(raw: Map<String, String>): UssdFlag {
    val flag = raw[UssdProtocol.FLAG_KEY]?.trim() ?: throw UnsupportedResponse(
        "The router answered the USSD request in a way this app does not " +
            "recognise, so its firmware may use a different USSD interface.",
    )
    return when {
        flag == UssdProtocol.FLAG_RECEIVED -> UssdFlag.Received
        flag in UssdProtocol.FLAG_TIMEOUT -> UssdFlag.Timeout
        flag in UssdProtocol.FLAG_ERRORS -> UssdFlag.Error(UssdProtocol.FLAG_ERRORS.getValue(flag))
        // "15", or anything else the device reports while it is still working.
        else -> UssdFlag.Pending
    }
}

/**
 * Calls [attempt] until it answers, waiting [intervalMs] between tries, and
 * gives up with null once [timeoutMs] worth of waiting has gone by.
 *
 * Counts the waits rather than reading a clock, so the bound is the same under
 * a test's virtual time as on a device, and a test runs in no time at all.
 */
internal suspend fun <T : Any> pollFor(
    timeoutMs: Long,
    intervalMs: Long,
    attempt: suspend () -> T?,
): T? {
    val step = intervalMs.coerceAtLeast(1)
    var waited = 0L
    while (true) {
        attempt()?.let { return it }
        if (waited >= timeoutMs) return null
        delay(step)
        waited += step
    }
}
