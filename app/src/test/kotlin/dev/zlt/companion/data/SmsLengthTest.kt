package dev.zlt.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsLengthTest {

    @Test
    fun `empty is no parts`() = assertEquals(SmsLength(0, 0, unicode = false), SmsLength.of("  "))

    @Test
    fun `gsm7 fits 160 in one part`() = assertEquals(1, SmsLength.of("a".repeat(160)).parts)

    @Test
    fun `gsm7 splits at 153 per part`() {
        assertEquals(2, SmsLength.of("a".repeat(161)).parts)
        assertEquals(2, SmsLength.of("a".repeat(306)).parts)
        assertEquals(3, SmsLength.of("a".repeat(307)).parts)
    }

    @Test
    fun `unicode fits 70 in one part`() {
        val length = SmsLength.of("☕".repeat(70))
        assertEquals(true, length.unicode)
        assertEquals(1, length.parts)
    }

    @Test
    fun `unicode splits at 67 per part`() = assertEquals(2, SmsLength.of("☕".repeat(71)).parts)

    @Test
    fun `counts what is sent, which is trimmed`() = assertEquals(2, SmsLength.of("  Hi \n").characters)
}
