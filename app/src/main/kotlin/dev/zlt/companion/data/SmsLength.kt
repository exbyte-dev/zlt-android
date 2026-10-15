package dev.zlt.companion.data

import dev.zlt.companion.net.DeviceText

/**
 * How many SMS parts a message will go out as, so the cost is visible before
 * Send rather than on the bill.
 *
 * The device picks the encoding (see [DeviceText.smsEncodeType]): GSM7 fits 160
 * characters in one part, UNICODE only 70, and a message that spills over is
 * split into parts that each lose a few characters to the joining header.
 */
data class SmsLength(val characters: Int, val parts: Int, val unicode: Boolean) {
    companion object {
        private const val GSM7_SINGLE = 160
        private const val GSM7_PART = 153
        private const val UNICODE_SINGLE = 70
        private const val UNICODE_PART = 67

        fun of(text: String): SmsLength {
            val body = text.trim()
            val unicode = !DeviceText.isGsm7(body)
            val count = body.length
            val single = if (unicode) UNICODE_SINGLE else GSM7_SINGLE
            val part = if (unicode) UNICODE_PART else GSM7_PART
            val parts = when {
                count == 0 -> 0
                count <= single -> 1
                else -> (count + part - 1) / part
            }
            return SmsLength(count, parts, unicode)
        }
    }
}
