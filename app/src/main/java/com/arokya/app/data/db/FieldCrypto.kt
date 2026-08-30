package com.arokya.app.data.db

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts individual sensitive columns (profile PII, lab findings) with an
 * AES-256-GCM key that's generated once and never leaves the Android
 * Keystore — the app process never sees the raw key bytes.
 *
 * This replaces an earlier SQLCipher-based design: SQLCipher's current
 * release requires compileSdk 37, which no shipping Android Gradle Plugin
 * version supports yet, and there's no older release with a lower floor
 * worth chasing. Field-level encryption needs no native library and nothing
 * beyond the stock Android SDK, so there's no compileSdk/AGP treadmill to
 * keep up with — the DB *file* itself is plain SQLite, but every sensitive
 * value in it is ciphertext.
 */
object FieldCrypto {
    private const val KEYSTORE_ALIAS = "arokya_field_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    private val secretKey: SecretKey by lazy {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey) ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    /** Empty string round-trips as empty — most of these fields start out blank. */
    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey) }
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipherText, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        val parts = stored.split(":", limit = 2)
        if (parts.size != 2) return "" // not something we encrypted (e.g. pre-migration data) — drop it, don't crash
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}
