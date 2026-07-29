package dev.zlt.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostValidationTest {

    @Test
    fun `normalize strips scheme whitespace and path`() {
        assertEquals("192.168.0.1", HostValidation.normalize("192.168.0.1"))
        assertEquals("192.168.0.1", HostValidation.normalize("  192.168.0.1  "))
        assertEquals("192.168.0.1", HostValidation.normalize("http://192.168.0.1"))
        assertEquals("192.168.0.1", HostValidation.normalize("https://192.168.0.1"))
        assertEquals("192.168.0.1", HostValidation.normalize("http://192.168.0.1/"))
        assertEquals("192.168.0.1", HostValidation.normalize("http://192.168.0.1/index.html"))
    }

    @Test
    fun `private ranges are accepted`() {
        listOf(
            "192.168.0.1", "192.168.1.1", "192.168.100.254",
            "10.0.0.1", "10.255.255.254",
            "172.16.0.1", "172.31.255.254",
            "100.64.0.1",
        ).forEach { assertTrue("$it should be valid", HostValidation.isValid(it)) }
    }

    @Test
    fun `public addresses are rejected`() {
        listOf(
            "8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1",
            "192.169.0.1", "100.63.0.1", "100.128.0.1",
        ).forEach { assertFalse("$it should be rejected", HostValidation.isValid(it)) }
    }

    @Test
    fun `rejection explains itself in plain language`() {
        val reason = HostValidation.reasonInvalid("8.8.8.8")
        assertNotNull(reason)
        assertTrue(reason!!.contains("public address"))
    }

    @Test
    fun `malformed input is rejected`() {
        assertNotNull(HostValidation.reasonInvalid(""))
        assertNotNull(HostValidation.reasonInvalid("192.168.0"))
        assertNotNull(HostValidation.reasonInvalid("192.168.0.1.5"))
        assertNotNull(HostValidation.reasonInvalid("192.168.0.256"))
        assertNotNull(HostValidation.reasonInvalid("192.168.0.-1"))
        assertNotNull(HostValidation.reasonInvalid("router.local"))
        assertNotNull(HostValidation.reasonInvalid("192.168.0.x"))
    }

    @Test
    fun `leading zeros are rejected rather than silently reinterpreted`() {
        // 192.168.000.001 parses fine octet-by-octet but is ambiguous (some
        // resolvers read a leading zero as octal), so it is refused outright.
        assertNotNull(HostValidation.reasonInvalid("192.168.000.001"))
        assertTrue(HostValidation.isValid("192.168.0.1"))
    }

    @Test
    fun `hostnames are not accepted even if they might resolve locally`() {
        assertFalse(HostValidation.isValid("localhost"))
        assertFalse(HostValidation.isValid("myrouter"))
    }
}
