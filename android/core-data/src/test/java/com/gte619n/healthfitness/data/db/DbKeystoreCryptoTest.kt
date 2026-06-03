package com.gte619n.healthfitness.data.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Test

/**
 * IMPL-AND-20 (Phase 3) — JVM unit tests for the **Keystore-free** parts of
 * [DbKeystore]: the AES/GCM envelope transform and the passphrase generator.
 *
 * The Android Keystore key handling itself needs a device and is exercised by
 * the instrumented test (`core-data/src/androidTest/.../HfDatabaseInstrumentedTest`).
 * Here we substitute a software AES-256 key so the wrap/unwrap round-trip and
 * tamper detection run on the JVM.
 */
class DbKeystoreCryptoTest {

    private fun softwareKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `wrap then unwrap round-trips the passphrase`() {
        val key = softwareKey()
        val passphrase = DbKeystore.randomPassphrase()

        val blob = DbKeystore.wrap(key, passphrase)
        val recovered = DbKeystore.unwrap(key, blob)

        assertArrayEquals(passphrase, recovered)
    }

    @Test
    fun `randomPassphrase is 32 bytes and non-deterministic`() {
        val a = DbKeystore.randomPassphrase()
        val b = DbKeystore.randomPassphrase()
        assertEquals(32, a.size)
        assertEquals(32, b.size)
        assertFalse("two passphrases must differ", a.contentEquals(b))
    }

    @Test
    fun `wrap output starts with a 12-byte GCM IV and is longer than the plaintext`() {
        val key = softwareKey()
        val passphrase = DbKeystore.randomPassphrase()
        val blob = DbKeystore.wrap(key, passphrase)
        // IV(12) + ciphertext(32) + GCM tag(16) = 60.
        assertEquals(60, blob.size)
        assertNotEquals("ciphertext must not equal plaintext", passphrase, blob.copyOfRange(12, 44))
    }

    @Test
    fun `tampered ciphertext fails GCM authentication`() {
        val key = softwareKey()
        val blob = DbKeystore.wrap(key, DbKeystore.randomPassphrase())
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            DbKeystore.unwrap(key, tampered)
        }
    }

    @Test
    fun `a different key cannot decrypt the blob`() {
        val blob = DbKeystore.wrap(softwareKey(), DbKeystore.randomPassphrase())
        assertThrows(AEADBadTagException::class.java) {
            DbKeystore.unwrap(softwareKey(), blob)
        }
    }

    @Test
    fun `unwrap rejects a blob too short to hold an IV`() {
        assertThrows(IllegalArgumentException::class.java) {
            DbKeystore.unwrap(softwareKey(), ByteArray(8))
        }
    }

    @Test
    fun `mirror table catalog lists all 23 in-scope collections exactly once`() {
        val tables = com.gte619n.healthfitness.data.db.entity.MirrorTables.ALL
        assertEquals(23, tables.size)
        assertEquals("no duplicate table names", tables.size, tables.toSet().size)
        assertTrue(tables.contains("medications"))
        assertTrue(tables.contains("workoutPrograms"))
        assertTrue(tables.contains("workoutScheduled"))
        assertTrue(tables.contains("userProfile"))
    }
}
