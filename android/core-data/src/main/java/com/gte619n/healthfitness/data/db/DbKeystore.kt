package com.gte619n.healthfitness.data.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
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
     * The resolved SQLCipher passphrase plus whether it had to be **minted fresh**
     * because the previously-stored one could not be recovered. When [regenerated]
     * is true the on-disk DB is encrypted under a now-lost key and is unreadable,
     * so the caller MUST discard it (see [HfDatabase.build]).
     */
    class Resolution(val passphrase: ByteArray, val regenerated: Boolean)

    /**
     * Resolves the SQLCipher passphrase, generating + persisting it on first use
     * and **self-healing** when the stored blob can't be decrypted.
     *
     * The wrapped passphrase lives in plain SharedPreferences while the AES key
     * that protects it is a non-exportable Keystore key. Those two can drift out
     * of sync — most commonly when Android/OEM auto-backup restores the prefs onto
     * a device where the Keystore key was never restored (it can't be), but also
     * if the key is ever invalidated. The blob then fails its GCM tag check with
     * [javax.crypto.AEADBadTagException]. Historically that exception escaped all
     * the way out of `MainActivity.onCreate` (this runs during Hilt injection) and
     * force-closed the app on every launch. We now treat an undecryptable blob as
     * "no passphrase yet": drop the stale key + blob and mint a new pair. The old
     * encrypted DB is unreadable under the new passphrase, so the caller wipes it
     * and the sync layer refills it from the backend.
     */
    fun resolvePassphrase(): Resolution {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_BLOB, null)
        if (stored != null) {
            try {
                return Resolution(unwrap(secretKey(), Base64.decode(stored, Base64.NO_WRAP)), regenerated = false)
            } catch (e: Exception) {
                // AEADBadTagException / KeyStoreException / ProviderException / a
                // malformed blob — any failure to recover the passphrase. Reset
                // and regenerate rather than crash. Catch broadly on purpose: the
                // recovery path is always safe, and the alternative is a launch
                // crash loop.
                Log.w(TAG, "stored DB passphrase could not be decrypted; resetting key + passphrase", e)
                deleteKey()
                prefs.edit().remove(PREF_BLOB).apply()
            }
        }
        val passphrase = randomPassphrase()
        val blob = wrap(secretKey(), passphrase)
        prefs.edit().putString(PREF_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        return Resolution(passphrase, regenerated = stored != null)
    }

    /**
     * Returns the SQLCipher passphrase, generating + persisting it on first use.
     * Returns a fresh copy each call (so the caller can zero it after use).
     * Self-heals an undecryptable blob; see [resolvePassphrase].
     */
    fun getOrCreatePassphrase(): ByteArray = resolvePassphrase().passphrase

    /** Drops the Keystore key so the next [secretKey] call mints a fresh one. */
    private fun deleteKey() {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(keyAlias)
        } catch (e: Exception) {
            Log.w(TAG, "could not delete stale Keystore key '$keyAlias'", e)
        }
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
        private const val TAG = "DbKeystore"
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
