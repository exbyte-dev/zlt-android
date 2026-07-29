package dev.zlt.companion.net

import java.security.MessageDigest
import java.util.Base64

/**
 * The router's login encoding, ported from `encode_username` / `encode_password`
 * in the Python client (zlt/client.py).
 *
 * The password scheme has one detail that is easy to get subtly wrong: the
 * SHA-256 digest is rendered as a *lowercase hex string* first, and it is that
 * 64-character string which then gets Base64-encoded — not the 32 raw digest
 * bytes. Encoding the raw bytes produces a plausible-looking 44-character value
 * that the router simply rejects, and every rejection burns one of the five
 * login attempts before a five-minute lockout. ZltAuthTest pins both functions
 * against vectors generated from the Python implementation so a regression here
 * fails the build rather than the device.
 *
 * java.util.Base64 rather than android.util.Base64: it exists from API 26 (the
 * app's minSdk) and, unlike the Android one, is real on the JVM, so these
 * functions are directly unit-testable without an instrumented test.
 */
internal object ZltAuth {

    private const val HEX_DIGITS = "0123456789abcdef"

    /** `Base64(utf8(username))`. */
    fun encodeUsername(username: String): String =
        Base64.getEncoder().encodeToString(username.toByteArray(Charsets.UTF_8))

    /** `Base64(ascii(lowercase_sha256_hex(randomLogin + password)))`. */
    fun encodePassword(randomLogin: String, password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((randomLogin + password).toByteArray(Charsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0F])
            }
        }
        return Base64.getEncoder().encodeToString(hex.toByteArray(Charsets.US_ASCII))
    }
}
