package dev.zlt.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Base64

/**
 * Every expected value here was produced by running the Python implementation
 * (zlt.client.encode_username / encode_password) directly, so these assert
 * agreement with the reference rather than with a re-reading of it. The first
 * two also appear verbatim in the Python project's own tests
 * (tests/test_client_reads.py).
 */
class ZltAuthTest {

    @Test
    fun `username vector from python`() {
        assertEquals("YWRtaW4=", ZltAuth.encodeUsername("admin"))
    }

    @Test
    fun `username handles spaces empty and non ascii`() {
        assertEquals("dXNlciB3aXRoIHNwYWNlcw==", ZltAuth.encodeUsername("user with spaces"))
        assertEquals("", ZltAuth.encodeUsername(""))
        // Pins UTF-8: the Python encodes with .encode("utf-8"), and a JVM
        // default charset of ISO-8859-1 would silently produce something else.
        assertEquals("w7xuw69jw7hkw6k=", ZltAuth.encodeUsername("ünïcødé"))
    }

    @Test
    fun `password vector from python`() {
        assertEquals(
            "OTZlNmM5YWY2MzY3YjBiZDc5YTc0ODQxN2MxNzI5NGFiYmJkNjY5MTM5N2RkY2VmYTUzZTI0MjcxMWNmNmJlNg==",
            ZltAuth.encodePassword("12345678", "admin"),
        )
    }

    @Test
    fun `password vectors covering punctuation short nonce and empty password`() {
        assertEquals(
            "OTU4MTViMWVjYzFjNmNlODhiMmYzMGZmYmRhNjZjNjgwOGIxMmZlM2RjNzgyYzljMzE3ZjRmYTQ1YjU2NTRkOA==",
            ZltAuth.encodePassword("12345678", "p@ssw0rd!"),
        )
        assertEquals(
            "YTU0OTQyYzhlMzY1ZjM3ODRmMzhiOGQ0MzdmOWQ3MDgyOTBkYjYwNzM4YjAwY2RjZmI5MzRjMzJkMWJlOTdmMw==",
            ZltAuth.encodePassword("0", "x"),
        )
        assertEquals(
            "ZWY3OTdjODExOGYwMmRmYjY0OTYwN2RkNWQzZjhjNzYyMzA0OGM5YzA2M2Q1MzJjYzk1YzVlZDdhODk4YTY0Zg==",
            ZltAuth.encodePassword("12345678", ""),
        )
    }

    @Test
    fun `password is utf8 not platform default`() {
        assertEquals(
            "MzgzMDYxNzAxY2MzNmU4Njg0ZjI5ZmFlNWM4MDgxOTU3YjI4ZGIxNjMyYzY4NTI2YzZlNmIyMGExMTdiOTU2YQ==",
            ZltAuth.encodePassword("abcdefgh", "pässwörd"),
        )
    }

    @Test
    fun `password encodes the hex string not the raw digest bytes`() {
        // The failure mode this guards against: Base64 of the 32 raw digest
        // bytes is 44 chars and looks perfectly reasonable, but the router
        // rejects it and each rejection costs a login attempt.
        val encoded = ZltAuth.encodePassword("12345678", "admin")
        assertEquals(88, encoded.length)
        assertEquals(64, Base64.getDecoder().decode(encoded).size)

        val rawDigestForm = Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("12345678admin".toByteArray(Charsets.UTF_8)),
        )
        assertNotEquals(rawDigestForm, encoded)
    }

    @Test
    fun `password hex digest is lowercase`() {
        val hex = String(Base64.getDecoder().decode(ZltAuth.encodePassword("12345678", "admin")))
        assertEquals(hex.lowercase(), hex)
    }

    @Test
    fun `nonce is prefixed to the password not appended`() {
        // Order matters and is not symmetric; swapping the two would pass a
        // naive round-trip test but fail against the device.
        assertNotEquals(
            ZltAuth.encodePassword("admin", "12345678"),
            ZltAuth.encodePassword("12345678", "admin"),
        )
    }
}
