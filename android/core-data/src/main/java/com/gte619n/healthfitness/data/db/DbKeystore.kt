package com.gte619n.healthfitness.data.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * IMPL-AND-20 (Phase 3) — manages the SQLCipher passphrase for `hf-offline.db`.
 *
 * **Strategy (envelope encryption, no secret ever in plaintext at rest):**
 *  1. A random 32-byte passphrase is generated **once** on first DB open.
 *  2. It is encrypted (AES-256/GCM/NoPadding) under a **non-exportable** key that
 *     lives in the Android Keystore (`AndroidKeyStore`). The Keystore key never
 *     leaves the TEE/StrongBox-backed store; we only ever hold a `Cipher` handle.
 *  3. The resulting `IV || ciphertext` blob is persisted (Base64) in ordinary
 *     SharedPreferences. Without the Keystore key the blob is useless, so plain
 *     prefs are sufficient — this avoids a hard dependency on the (heavier,
 *     occasionally flaky) EncryptedSharedPreferences/Tink stack.
 *  4. On later opens the blob is decrypted back to the passphrase.
 *
 * `getOrCreatePassphrase()` returns the raw bytes handed to SQLCipher's
 * `SupportFactory`. The byte array should be zeroed by the caller once SQLCipher
 * has copied it (SupportFactory takes ownership and clears it).
 *
 * The pure transform helpers ([wrap]/[unwrap]/[randomPassphrase]) are
 * Keystore-free and unit-tested on the JVM; the Keystore key handling itself
 * requires a device and is covered by the instrumented test.
 */
class DbKeystore(
    private val context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val prefsName: String = DEFAULT_PREFS,
) {
    /**
     * Returns the SQLCipher passphrase, generating + persisting it on first use.
     * Returns a fresh copy each call (so the caller can zero it after use).
     */
    fun getOrCreatePassphrase(): ByteArray {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_BLOB, null)
        if (stored != null) {
            return unwrap(secretKey(), Base64.decode(stored, Base64.NO_WRAP))
        }
        val passphrase = randomPassphrase()
        val blob = wrap(secretKey(), passphrase)
        prefs.edit().putString(PREF_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        return passphrase
    }

    /** Loads the existing Keystore key, creating it on first use. */
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Not user-auth-bound: the DB must open in background sync workers
            // without a foreground unlock prompt (D10 periodic floor / FCM pull).
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "hf_offline_db_key"
        const val DEFAULT_PREFS = "hf_offline_db_prefs"
        private const val PREF_BLOB = "passphrase_blob"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        private const val PASSPHRASE_BYTES = 32

        /** 32 random bytes — the SQLCipher raw key. */
        fun randomPassphrase(): ByteArray =
            ByteArray(PASSPHRASE_BYTES).also { java.security.SecureRandom().nextBytes(it) }

        /**
         * Encrypts [plaintext] under [key], returning `IV(12) || ciphertext+tag`.
         * Pure given a key — unit-tested on the JVM with a software AES key.
         */
        fun wrap(key: SecretKey, plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            require(iv.size == GCM_IV_BYTES) { "unexpected GCM IV length: ${iv.size}" }
            val ct = cipher.doFinal(plaintext)
            return iv + ct
        }

        /** Inverse of [wrap]: splits the IV, then decrypts. */
        fun unwrap(key: SecretKey, blob: ByteArray): ByteArray {
            require(blob.size > GCM_IV_BYTES) { "blob too short to contain IV + ciphertext" }
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val ct = blob.copyOfRange(GCM_IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(ct)
        }
    }
}
