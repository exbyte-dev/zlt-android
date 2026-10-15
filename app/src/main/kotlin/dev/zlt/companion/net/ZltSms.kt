package dev.zlt.companion.net

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One inbox row, decoded. */
data class SmsMessage(
    val id: String,
    val number: String,
    val text: String,
    /** `YYYY-MM-DD HH:MM`, or the device's raw string if it would not parse. */
    val date: String,
    val unread: Boolean,
    val outgoing: Boolean,
)

/**
 * SMS, ported from `sms_list` / `sms_send` / `sms_mark_read` / `sms_delete` in
 * zlt/client.py. See docs/protocol.md in zltrouter for the captured contract.
 *
 * Extensions over [ZltClient], for the same reason as the USSD ones: every
 * request keeps the client's session handling, and the mutex is only held per
 * request, so the signal poll is not frozen while a send waits on the network.
 */
suspend fun ZltClient.smsList(limit: Int = SMS_DEFAULT_LIMIT): List<SmsMessage> {
    ensureSession()
    val data = getObject(
        SmsProtocol.LIST_CMD,
        extra = linkedMapOf(
            "page" to "0",
            "data_per_page" to SmsProtocol.PAGE_SIZE,
            "mem_store" to SmsProtocol.MEM_STORE,
            "tags" to SmsProtocol.TAGS_ALL,
            "order_by" to SmsProtocol.ORDER_BY,
        ),
    )
    // A device that speaks a different SMS API should say so, not hand back a
    // silently empty inbox.
    val rows = data["messages"] as? JsonArray ?: throw UnsupportedResponse(
        "The router answered the inbox request in a way this app does not " +
            "recognise, so its firmware may use a different SMS interface.",
    )
    // data_per_page is advisory, so the cap is applied here as well.
    return rows.take(limit).mapNotNull { (it as? JsonObject)?.let(::smsFromRow) }
}

/** Sends one message and waits for the network to confirm it. Costs money. */
suspend fun ZltClient.smsSend(
    number: String,
    text: String,
    timeoutMs: Long = SmsProtocol.TIMEOUT_MS,
    intervalMs: Long = SmsProtocol.POLL_INTERVAL_MS,
    settleMs: Long = SmsProtocol.SETTLE_MS,
) {
    val to = number.trim()
    val body = text.trim()
    if (to.isEmpty()) throw SmsFailed("Enter a number to send to.")
    if (body.isEmpty()) throw SmsFailed("The message is empty.")

    val data = post(
        SmsProtocol.SEND_GOFORM,
        linkedMapOf(
            "Number" to to,
            "sms_time" to DeviceText.smsTime(),
            "MessageBody" to DeviceText.encodeSms(body),
            "ID" to SmsProtocol.NEW_ID,
            "encode_type" to DeviceText.smsEncodeType(body),
        ),
    )
    if (data["result"] != ZltProtocol.RESULT_SUCCESS) {
        throw SmsFailed("The router would not send the message (it answered \"${data["result"].orEmpty()}\").")
    }
    delay(settleMs)
    smsPoll(SmsProtocol.SEND_SLOT, timeoutMs, intervalMs, rejected = "The network rejected the message.", what = "the message to send")
}

/**
 * Marks messages read; returns how many were addressed. Answered at once, with
 * nothing to poll. The device has no way back to unread.
 */
suspend fun ZltClient.smsMarkRead(ids: Collection<String>): Int {
    val msgId = DeviceText.msgIdList(ids)
    val data = post(
        SmsProtocol.READ_GOFORM,
        linkedMapOf("msg_id" to msgId, "tag" to SmsProtocol.READ_TAG),
    )
    if (data["result"] != ZltProtocol.RESULT_SUCCESS) {
        throw SmsFailed("The router would not mark the messages read (it answered \"${data["result"].orEmpty()}\").")
    }
    return ids.size
}

/** Deletes messages and waits for the device to confirm. Permanent. */
suspend fun ZltClient.smsDelete(
    ids: Collection<String>,
    timeoutMs: Long = SmsProtocol.TIMEOUT_MS,
    intervalMs: Long = SmsProtocol.POLL_INTERVAL_MS,
    settleMs: Long = SmsProtocol.SETTLE_MS,
): Int {
    val msgId = DeviceText.msgIdList(ids)
    val data = post(SmsProtocol.DELETE_GOFORM, mapOf("msg_id" to msgId))
    if (data["result"] != ZltProtocol.RESULT_SUCCESS) {
        throw SmsFailed("The router would not delete the messages (it answered \"${data["result"].orEmpty()}\").")
    }
    delay(settleMs)
    smsPoll(SmsProtocol.DELETE_SLOT, timeoutMs, intervalMs, rejected = "The router could not delete the messages.", what = "the delete")
    return ids.size
}

/**
 * Waits on one of the device's `sms_cmd` status slots. Send and delete report
 * the same way and differ only in the slot.
 *
 * No status field at all means nothing is queued on that slot yet: the live
 * device answers `{"messages": []}` until the command lands. That is treated as
 * pending, so a slow network is not called a failure.
 */
private suspend fun ZltClient.smsPoll(
    slot: String,
    timeoutMs: Long,
    intervalMs: Long,
    rejected: String,
    what: String,
) {
    val done = pollFor(timeoutMs, intervalMs) {
        val status = get(SmsProtocol.STATUS_CMD, extra = mapOf("sms_cmd" to slot))[SmsProtocol.STATUS_FIELD]
            .orEmpty().trim()
        when (status) {
            SmsProtocol.STATUS_DONE -> true
            SmsProtocol.STATUS_FAILED -> throw SmsFailed(rejected)
            else -> null
        }
    }
    if (done == null) throw SmsFailed("Timed out waiting for the router to confirm $what.")
}

internal fun smsFromRow(row: JsonObject): SmsMessage {
    fun field(name: String) = (row[name] as? JsonPrimitive)?.content.orEmpty()
    val tag = field("tag").trim()
    return SmsMessage(
        id = field("id"),
        number = field("number"), // plain text, unlike the body
        text = DeviceText.decodeSms(field("content")),
        date = DeviceText.parseSmsDate(field("date")),
        unread = tag == SmsProtocol.TAG_UNREAD,
        outgoing = tag in SmsProtocol.TAGS_OUTGOING,
    )
}

/** What the web dashboard shows; the inbox is read whole and capped here. */
const val SMS_DEFAULT_LIMIT = 50
