package dev.zlt.companion.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the router password with an AES-GCM key held in the Android Keystore.
 *
 * The key material never leaves the Keystore and is not extractable, so what
 * lands in DataStore is ciphertext that is meaningless off this device — which
 * is also why backups are excluded in data_extraction_rules.xml: a restored copy
 * could not be decrypted anyway.
 *
 * androidx.security:security-crypto (EncryptedSharedPreferences) would be the
 * obvious library for this and is deliberately not used: it has been deprecated
 * without a replacement, and this is a hundred lines of well-trodden Keystore
 * work rather than a reason to ship an unmaintained dependency in an app headed
 * for the Play Store.
 *
 * The key is created without requiring user authentication, because the Quick
 * Settings tile has to read the password while the device is locked.
 */
object SecretCipher {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "zlt.router.password.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** @return `base64(iv ‖ ciphertext)`, or an empty string for empty input. */
    fun seal(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + ciphertext
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    /**
     * @return the plaintext, or an empty string if it cannot be recovered.
     *
     * Undecryptable is a real state rather than a bug: the Keystore key is
     * dropped if the app's data is cleared or it is restored onto another
     * device. Callers treat an empty password as "not configured" and ask for it
     * again, which is the right outcome — better than crashing on launch.
     */
    fun open(sealed: String): String {
        if (sealed.isEmpty()) return ""
        return try {
            val packed = Base64.decode(sealed, Base64.NO_WRAP)
            if (packed.size <= IV_BYTES) return ""
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(GCM_TAG_BITS, packed, 0, IV_BYTES),
                )
            }
            String(
                cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES),
                Charsets.UTF_8,
            )
        } catch (e: Exception) {
            ""
        }
    }
}
