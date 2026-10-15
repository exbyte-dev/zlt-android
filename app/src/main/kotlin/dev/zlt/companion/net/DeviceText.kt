package dev.zlt.companion.net

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How the device encodes text on the wire, ported from `_decode_ussd`,
 * `_decode_sms`, `_encode_sms`, `_sms_encode_type`, `_sms_time`,
 * `_msg_id_list` and `_parse_sms_date` in zlt/client.py.
 *
 * Pure functions, so they are pinned against the Python's own test vectors in
 * DeviceTextTest without a router or a device.
 */
internal object DeviceText {

    /**
     * A device hex payload as bytes, or null if it is not hex. USSD replies and
     * SMS bodies both arrive hex-encoded, and both have to tolerate firmware
     * that sends plain text instead.
     */
    fun hexBytes(data: String): ByteArray? {
        if (data.length % 2 != 0) return null
        val out = ByteArray(data.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(data[i * 2], 16)
            val lo = Character.digit(data[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * Strict UTF-16BE, or null. The platform's `String(bytes, UTF_16BE)` would
     * quietly substitute replacement characters where the Python raises, and
     * the Python's answer to a bad payload is to show it raw.
     */
    private fun strictUtf16(bytes: ByteArray): String? = try {
        Charsets.UTF_16BE.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /**
     * A `ussd_data` payload, decoded by its data coding scheme: UCS2 when
     * `(dcs & 0x0C) == 0x08`, otherwise one byte per character. Non-hex input
     * is returned unchanged.
     */
    fun decodeUssd(data: String, dcs: String): String {
        if (data.isEmpty()) return ""
        val raw = hexBytes(data) ?: return data
        val dcsValue = dcs.trim().toIntOrNull() ?: 0
        if ((dcsValue and UssdProtocol.DCS_UCS2_MASK) == UssdProtocol.DCS_UCS2_VALUE) {
            return strictUtf16(raw) ?: data
        }
        return String(raw, Charsets.ISO_8859_1)
    }

    /** An SMS body. Unlike USSD these carry no DCS: always UCS2 hex. */
    fun decodeSms(content: String): String {
        if (content.isEmpty()) return ""
        val raw = hexBytes(content) ?: return content
        val text = strictUtf16(raw) ?: return content
        // The device pads short bodies with NULs; the stock UI drops them too.
        return text.replace("\u0000", "")
    }

    /** An outgoing body the way `MessageBody` expects it: UCS2 hex, upper case. */
    fun encodeSms(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_16BE)
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                append(HEX_UPPER[value ushr 4])
                append(HEX_UPPER[value and 0x0F])
            }
        }
    }

    fun isGsm7(text: String): Boolean = text.all { it in SmsProtocol.GSM7_CHARS }

    fun smsEncodeType(text: String): String =
        if (isGsm7(text)) SmsProtocol.ENCODE_GSM7 else SmsProtocol.ENCODE_UNICODE

    /**
     * `sms_time` as the device's getCurrentTimeString() writes it:
     * `YY;MM;DD;HH;MM;SS;<signed whole-hour UTC offset>`, e.g.
     * `26;07;24;15;28;20;+1`. The offset truncates toward zero, as the
     * Python's `int()` does.
     */
    fun smsTime(now: ZonedDateTime = ZonedDateTime.now()): String {
        val hours = now.offset.totalSeconds / 3600
        val sign = if (hours >= 0) "+" else ""
        return now.format(SMS_TIME_FORMAT) + ";" + sign + hours
    }

    /**
     * The `msg_id` that SET_MSG_READ and DELETE_SMS expect: ids joined by `;`
     * with a trailing `;`, exactly as the device's own setSmsRead and
     * deleteMessage build it.
     *
     * `;` is the separator, so an id carrying one would quietly widen the
     * operation to messages nobody picked. Delete is permanent, so that is
     * refused rather than cleaned up.
     */
    fun msgIdList(ids: Collection<String>): String {
        val clean = ids.map { it.trim() }
        if (clean.isEmpty() || clean.any { it.isEmpty() }) {
            throw SmsFailed("No messages were selected.")
        }
        clean.firstOrNull { id -> id.any { it == ';' || it.isWhitespace() } }?.let {
            throw SmsFailed("\"$it\" is not a valid message id.")
        }
        return clean.joinToString(";") + ";"
    }

    /**
     * The inbox timestamp, made readable. Live samples are comma-separated
     * (`26,07,24,15,28,20,+4`); the stock UI also accepts semicolons. Anything
     * that does not fit is handed back untouched rather than guessed at.
     */
    fun parseSmsDate(raw: String): String {
        val parts = raw.trim().split(';', ',')
        if (parts.size < 6) return raw
        val numbers = parts.take(5).map { it.trim().toIntOrNull() ?: return raw }
        val (yy, mm, dd, hh, mi) = numbers
        // Locale.ROOT, or an Arabic or Persian locale would print its own digits.
        return String.format(Locale.ROOT, "20%02d-%02d-%02d %02d:%02d", yy, mm, dd, hh, mi)
    }

    private const val HEX_UPPER = "0123456789ABCDEF"
    private val SMS_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yy;MM;dd;HH;mm;ss", Locale.ROOT)
}
