package com.gte619n.healthfitness.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gte619n.healthfitness.data.db.entity.MedicationEntity
import com.gte619n.healthfitness.data.db.entity.OutboxEntity
import com.gte619n.healthfitness.data.db.entity.SyncStateEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.sqlcipher.database.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * IMPL-AND-20 (Phase 3) — instrumented round-trip tests for the SQLCipher-backed
 * [HfDatabase].
 *
 * ⚠️ REQUIRES AN EMULATOR OR DEVICE. SQLCipher loads native libraries and the
 * Android Keystore is hardware-backed, so none of this runs in a JVM unit test
 * or in the agent sandbox. Run with:
 *
 *     ./gradlew :core-data:connectedDebugAndroidTest
 *
 * Coverage:
 *  - upsert / observeActive / getById round-trips on a mirror table.
 *  - tombstone (markArchived) removes a row from `observeActive` but keeps it
 *    queryable via getById.
 *  - outbox + sync_state structural tables persist and read back.
 *  - the DB opens with the real [DbKeystore] passphrase (SupportFactory path).
 *  - opening the same file with a WRONG key fails (proves encryption-at-rest).
 *  - [DbWipe] deletes the on-disk file (sign-out wipe, D5).
 */
@RunWith(AndroidJUnit4::class)
class HfDatabaseInstrumentedTest {

    private lateinit var context: Context
    private lateinit var keystore: DbKeystore
    private lateinit var db: HfDatabase

    private fun row(id: String, lastUpdate: Long, status: String = "ACTIVE") =
        MedicationEntity(
            id = id,
            payloadJson = """{"name":"med-$id"}""",
            lastUpdate = lastUpdate,
            status = status,
            dirty = false,
            syncState = "SYNCED",
        )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Start from a clean slate: remove any DB file + Keystore-wrapped key blob.
        context.getDatabasePath(HfDatabase.DB_NAME).parentFile
            ?.listFiles { f -> f.name.startsWith(HfDatabase.DB_NAME) }
            ?.forEach { it.delete() }
        keystore = DbKeystore(context)
        db = HfDatabase.build(context, keystore)
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
    }

    @Test
    fun upsert_and_observeActive_roundTrips() = runBlocking {
        val dao = db.medicationDao()
        dao.upsert(row("a", lastUpdate = 100))
        dao.upsert(row("b", lastUpdate = 200))

        val active = dao.observeActive().first()
        assertEquals(2, active.size)
        // ordered by lastUpdate DESC.
        assertEquals("b", active.first().id)
        assertEquals("""{"name":"med-a"}""", dao.getById("a")?.payloadJson)
    }

    @Test
    fun markArchived_tombstones_row_out_of_observeActive() = runBlocking {
        val dao = db.medicationDao()
        dao.upsert(row("a", lastUpdate = 100))
        dao.markArchived("a", lastUpdate = 300)

        assertTrue(dao.observeActive().first().isEmpty())
        val tombstone = dao.getById("a")
        assertNotNull(tombstone)
        assertEquals("ARCHIVED", tombstone!!.status)
        assertEquals(300, tombstone.lastUpdate)
    }

    @Test
    fun delete_hardRemoves_row() = runBlocking {
        val dao = db.medicationDao()
        dao.upsert(row("a", lastUpdate = 100))
        dao.delete("a")
        assertNull(dao.getById("a"))
    }

    @Test
    fun outbox_and_syncState_persist() = runBlocking {
        db.syncStateDao().upsert(
            SyncStateEntity(cursor = "cursor-1", schemaVersion = 1, lastFullSyncAt = 42L),
        )
        assertEquals("cursor-1", db.syncStateDao().get()?.cursor)

        val outbox = db.outboxDao()
        outbox.insert(
            OutboxEntity(
                mutationId = "m1",
                entityTable = "medications",
                entityId = "a",
                op = "CREATE",
                payloadJson = """{"name":"med-a"}""",
                originDeviceId = "device-1",
                seq = 1,
                attempts = 0,
                nextAttemptAt = 0,
                createdAt = 0,
            ),
        )
        assertEquals(1, outbox.listDue(now = 1).size)
        assertEquals(1L, outbox.maxSeq())
        assertEquals(1, outbox.observePendingCount().first())
    }

    @Test
    fun database_opens_only_with_correct_keystore_key() {
        // Write a row, close, then try to reopen the raw file with a WRONG key.
        runBlocking { db.medicationDao().upsert(row("a", lastUpdate = 100)) }
        db.close()

        SQLiteDatabase.loadLibs(context)
        val dbFile = context.getDatabasePath(HfDatabase.DB_NAME)
        var openedWithWrongKey = true
        try {
            val wrong = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                "this-is-the-wrong-passphrase".toCharArray(),
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            wrong.rawQuery("SELECT count(*) FROM medications", null).close()
            wrong.close()
        } catch (_: Throwable) {
            openedWithWrongKey = false
        }
        assertFalse("encrypted DB must NOT open with the wrong key", openedWithWrongKey)
    }

    @Test
    fun orphanedPassphraseBlob_selfHeals_insteadOfCrashing() = runBlocking {
        // Reproduces the field crash on Samsung/Android 16 (AEADBadTagException out
        // of MainActivity.onCreate): the Keystore-wrapped passphrase blob survives
        // in SharedPreferences (e.g. restored from auto-backup) but the
        // non-exportable Keystore key that decrypts it is gone. unwrap() then fails
        // its GCM tag check.
        db.medicationDao().upsert(row("a", lastUpdate = 100))
        db.close()

        // Drop ONLY the Keystore key, leaving the prefs blob behind — exactly the
        // orphaned state that used to crash. (resolvePassphrase has a side effect:
        // it rewrites the blob, so we must NOT call it before build() or build()
        // would see a valid blob and skip the wipe. build() is the sole caller in
        // production; mirror that here.)
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry(DbKeystore.DEFAULT_KEY_ALIAS)

        // build() must recover end-to-end: catch the AEADBadTagException, rekey,
        // wipe the unreadable DB, and open cleanly — instead of crashing.
        val healed = HfDatabase.build(context, DbKeystore(context))
        // The empty store proves the stale DB was wiped (regenerated path taken);
        // the pre-orphan row is gone and the fresh store is fully usable.
        assertTrue(healed.medicationDao().observeActive().first().isEmpty())
        healed.medicationDao().upsert(row("b", lastUpdate = 200))
        assertEquals("b", healed.medicationDao().observeActive().first().single().id)
        healed.close()
    }

    @Test
    fun dbWipe_deletes_the_database_file() = runBlocking {
        runBlocking { db.medicationDao().upsert(row("a", lastUpdate = 100)) }
        val dbFile: File = context.getDatabasePath(HfDatabase.DB_NAME)
        assertTrue(dbFile.exists())

        // Mirror DbWipe's file-deletion behavior (DbWipe itself needs the Hilt
        // Provider<HfDatabase>; here we close + delete directly).
        db.close()
        DbWipe(context, { db }).wipe()

        assertFalse("hf-offline.db must be gone after wipe", dbFile.exists())
    }
}
