package com.gte619n.healthfitness.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gte619n.healthfitness.data.db.DbKeystore
import com.gte619n.healthfitness.data.db.HfDatabase
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.SyncRowState
import com.gte619n.healthfitness.data.db.entity.SyncRowStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.time.Instant

/**
 * IMPL-AND-20 (Phase 7) — tombstone apply removes a locally-created row
 * (Definition of Done #4).
 *
 * ⚠️ REQUIRES AN EMULATOR OR DEVICE (real SQLCipher Room + sync engine over
 * MockWebServer). Run with `./gradlew :core-data:connectedDebugAndroidTest`.
 *
 * Scenario: a row exists locally — created while "offline" (PENDING, dirty=false
 * after a prior confirmed sync) — and a later delta carries a tombstone
 * (`status=ARCHIVED, doc=null`) for it. On apply the engine must archive the row so
 * it drops out of the UI `observeActive()` Flow, even though the second device was
 * offline at the originating device's delete time. This is the cross-device delete
 * propagation in DoD #4.
 */
@RunWith(AndroidJUnit4::class)
class TombstoneApplyTest {

    private lateinit var context: Context
    private lateinit var keystore: DbKeystore
    private lateinit var db: HfDatabase
    private lateinit var server: MockWebServer
    private lateinit var engine: SyncEngine

    private val moshi = instrumentedMoshi

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(HfDatabase.DB_NAME).parentFile
            ?.listFiles { f -> f.name.startsWith(HfDatabase.DB_NAME) }
            ?.forEach { it.delete() }
        keystore = DbKeystore(context)
        db = HfDatabase.build(context, keystore)
        server = MockWebServer().also { it.start() }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SyncApi::class.java)
        engine = SyncEngine(
            api = api,
            mirror = mirrorStore(db),
            syncStateDao = InMemorySyncStateDao(),
            dbWiper = NoopDbWiper(),
            flags = RecordingKillSwitchSink(),
            moshi = moshi,
            io = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
        server.shutdown()
    }

    @Test
    fun tombstone_delta_archives_a_locally_existing_row_out_of_observeActive() = runBlocking {
        val id = "br-tombstone-1"

        // A row that already exists locally (created earlier; here SYNCED so the
        // tombstone is a clean LWW apply, modelling "offline at delete time then
        // catching up on reconnect").
        mirrorStore(db).upsert(
            MirrorTables.BLOOD_READINGS,
            MirrorRowData(
                id = id,
                payloadJson = """{"id":"$id","systolic":120,"diastolic":80}""",
                lastUpdate = Instant.parse("2026-06-02T18:00:00Z").toEpochMilli(),
                status = SyncRowStatus.ACTIVE.name,
                dirty = false,
                syncState = SyncRowState.SYNCED.name,
            ),
        )
        // It is visible in the UI Flow before the tombstone.
        assertTrue(
            db.bloodReadingDao().observeActive().first().any { it.id == id },
        )

        // The delta carries a tombstone for it (the delete happened on device A).
        server.enqueue(
            MockResponse().setBody(
                """
                {"schemaVersion":1,"changes":[
                  {"collection":"bloodReadings","id":"$id","status":"ARCHIVED",
                   "lastUpdate":"2026-06-02T18:10:00Z","doc":null}
                ],"nextCursor":"done","hasMore":false,"killSwitch":false}
                """.trimIndent(),
            ),
        )

        val result = engine.pull()
        assertEquals(1, result.applied)

        // The row is gone from the UI Flow (archived) …
        assertTrue(
            "tombstoned row must drop out of observeActive",
            db.bloodReadingDao().observeActive().first().none { it.id == id },
        )
        // … but the tombstone is retained (status=ARCHIVED, lastUpdate bumped) so a
        // late LWW comparison still has something to beat (no hard-delete; D2).
        val tombstone = db.bloodReadingDao().getById(id)
        assertNotNull(tombstone)
        assertEquals(SyncRowStatus.ARCHIVED.name, tombstone!!.status)
        assertEquals(Instant.parse("2026-06-02T18:10:00Z").toEpochMilli(), tombstone.lastUpdate)
    }
}
