package dev.zlt.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Pins the wire encodings against the Python project's own vectors
 * (tests/test_client_sms.py and tests/test_client_ussd.py), several of them
 * captured from the live device.
 */
class DeviceTextTest {

    // --- USSD decoding -------------------------------------------------------

    @Test
    fun `ussd ucs2 decodes`() = assertEquals("Hi", DeviceText.decodeUssd(UCS2_HI, "72"))

    @Test
    fun `ussd real device sample decodes`() =
        assertEquals("MTN", DeviceText.decodeUssd("004d0054004e", "72"))

    @Test
    fun `ussd single byte scheme decodes`() =
        assertEquals("Hello", DeviceText.decodeUssd("48656c6c6f", "68"))

    @Test
    fun `ussd non hex passes through`() =
        assertEquals("Balance: 5", DeviceText.decodeUssd("Balance: 5", "0"))

    @Test
    fun `ussd empty stays empty`() = assertEquals("", DeviceText.decodeUssd("", "72"))

    @Test
    fun `ussd odd length hex passes through`() =
        assertEquals("abc", DeviceText.decodeUssd("abc", "72"))

    // --- SMS decoding --------------------------------------------------------

    @Test
    fun `sms body decodes`() = assertEquals("Hi", DeviceText.decodeSms(UCS2_HI))

    @Test
    fun `sms real device sample decodes`() =
        assertEquals("Activation", DeviceText.decodeSms("00410063007400690076006100740069006F006E"))

    @Test
    fun `sms nul padding is stripped`() = assertEquals("Hi", DeviceText.decodeSms(UCS2_HI + "0000"))

    @Test
    fun `sms non hex passes through`() = assertEquals("Plain text", DeviceText.decodeSms("Plain text"))

    @Test
    fun `sms that is hex but not utf16 passes through`() {
        // Three bytes cannot be UTF-16. The Python raises and shows it raw.
        assertEquals("414243", DeviceText.decodeSms("414243"))
    }

    // --- SMS encoding --------------------------------------------------------

    @Test
    fun `encode is ucs2 hex`() = assertEquals("00480069", DeviceText.encodeSms("Hi"))

    @Test
    fun `encode round trips`() {
        for (text in listOf("Hi", "Balance: N1,200.00", "naïve café", "line\nbreak", "emoji 😀")) {
            assertEquals(text, DeviceText.decodeSms(DeviceText.encodeSms(text)))
        }
    }

    @Test
    fun `encode empty`() = assertEquals("", DeviceText.encodeSms(""))

    @Test
    fun `plain text goes out as gsm7`() =
        assertEquals("GSM7_default", DeviceText.smsEncodeType("Hello 123"))

    @Test
    fun `e acute is still gsm7`() =
        // In the GSM 03.38 set; it takes a genuinely foreign character to
        // force UNICODE, and getting this wrong more than doubles the cost.
        assertEquals("GSM7_default", DeviceText.smsEncodeType("café"))

    @Test
    fun `anything else goes out as unicode`() =
        assertEquals("UNICODE", DeviceText.smsEncodeType("café ☕"))

    @Test
    fun `empty is gsm7`() = assertEquals("GSM7_default", DeviceText.smsEncodeType(""))

    // --- time ----------------------------------------------------------------

    @Test
    fun `sms time matches the device format`() {
        val now = ZonedDateTime.of(2026, 7, 24, 15, 28, 20, 0, ZoneOffset.ofHours(1))
        assertEquals("26;07;24;15;28;20;+1", DeviceText.smsTime(now))
    }

    @Test
    fun `sms time negative offset`() {
        val now = ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(-5))
        assertEquals("26;01;02;03;04;05;-5", DeviceText.smsTime(now))
    }

    @Test
    fun `sms time truncates a half hour offset as the python does`() {
        val now = ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHoursMinutes(5, 30))
        assertEquals("26;01;02;03;04;05;+5", DeviceText.smsTime(now))
    }

    @Test
    fun `inbox date comma form`() =
        assertEquals("2026-07-24 15:28", DeviceText.parseSmsDate("26,07,24,15,28,20,+4"))

    @Test
    fun `inbox date semicolon form`() =
        assertEquals("2026-07-24 15:28", DeviceText.parseSmsDate("26;07;24;15;28;20;+4"))

    @Test
    fun `inbox date garbage passes through`() =
        assertEquals("whenever", DeviceText.parseSmsDate("whenever"))

    // --- msg_id --------------------------------------------------------------

    @Test
    fun `msg ids join with a trailing separator`() =
        assertEquals("659;658;", DeviceText.msgIdList(listOf("659", "658")))

    @Test
    fun `one msg id keeps the separator`() = assertEquals("657;", DeviceText.msgIdList(listOf("657")))

    @Test
    fun `msg ids are trimmed`() = assertEquals("657;", DeviceText.msgIdList(listOf(" 657 ")))

    @Test
    fun `an empty selection is refused`() {
        for (ids in listOf(emptyList(), listOf(""), listOf("   "))) {
            assertThrows(SmsFailed::class.java) { DeviceText.msgIdList(ids) }
        }
    }

    @Test
    fun `ids that could widen the operation are refused`() {
        for (bad in listOf("1;2", "1 2", "1\t2", "1\n2")) {
            assertThrows(SmsFailed::class.java) { DeviceText.msgIdList(listOf(bad)) }
        }
    }

    private companion object {
        /** "Hi" as UCS2, the shape the device returns for every body. */
        const val UCS2_HI = "00480069"
    }
}
