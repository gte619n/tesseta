package com.gte619n.healthfitness.data.sync

import android.content.Context
import androidx.room.Room
import com.gte619n.healthfitness.data.db.DbKeystore
import com.gte619n.healthfitness.data.db.HfDatabase
import com.gte619n.healthfitness.data.db.dao.SyncStateDao
import com.gte619n.healthfitness.data.db.entity.SyncStateEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

/**
 * IMPL-AND-20 (Phase 7) — shared helpers for the instrumented E2E + convergence
 * tests. Run on a device/emulator only (real SQLCipher Room).
 *
 * [mirrorStore] wires a real [MirrorStore] over an [HfDatabase]'s 21 typed DAOs so
 * the sync engine / outbox can dispatch generically against the on-disk encrypted
 * store, exactly as Hilt does in production.
 */

/** Build a real [MirrorStore] over every mirror DAO of [db]. */
internal fun mirrorStore(db: HfDatabase): MirrorStore = MirrorStore(
    bodyComposition = db.bodyCompositionDao(),
    bloodReading = db.bloodReadingDao(),
    bloodTestReport = db.bloodTestReportDao(),
    medication = db.medicationDao(),
    medicationAdherence = db.medicationAdherenceDao(),
    medicationHistory = db.medicationHistoryDao(),
    protocol = db.protocolDao(),
    goal = db.goalDao(),
    goalPhase = db.goalPhaseDao(),
    goalStep = db.goalStepDao(),
    goalChatThread = db.goalChatThreadDao(),
    goalChatMessage = db.goalChatMessageDao(),
    nutritionDailyLog = db.nutritionDailyLogDao(),
    nutritionEntry = db.nutritionEntryDao(),
    nutritionTarget = db.nutritionTargetDao(),
    location = db.locationDao(),
    dailyMetric = db.dailyMetricDao(),
    deviceSync = db.deviceSyncDao(),
    dexaScan = db.dexaScanDao(),
    weeklyWorkoutAggregate = db.weeklyWorkoutAggregateDao(),
    workoutProgram = db.workoutProgramDao(),
    workoutScheduled = db.workoutScheduledDao(),
    userProfile = db.userProfileDao(),
)

/**
 * Build a **named** SQLCipher-encrypted [HfDatabase] — the production
 * [HfDatabase.build] hardcodes the single `hf-offline.db` file, but the
 * convergence test runs two logical clients (A and B) in one process, each needing
 * its own on-disk encrypted store. Uses the same [SupportFactory] + Keystore key as
 * production so each client's DB is genuinely encrypted at rest.
 */
internal fun buildNamedDb(context: Context, keystore: DbKeystore, dbName: String): HfDatabase {
    SQLiteDatabase.loadLibs(context)
    val factory = SupportFactory(keystore.getOrCreatePassphrase())
    return Room.databaseBuilder(context, HfDatabase::class.java, dbName)
        .openHelperFactory(factory)
        .fallbackToDestructiveMigration()
        .build()
}

/** Delete every on-disk file for the named DB (clean-slate setUp/tearDown). */
internal fun deleteDbFiles(context: Context, dbName: String) {
    context.getDatabasePath(dbName).parentFile
        ?.listFiles { f -> f.name.startsWith(dbName) }
        ?.forEach { it.delete() }
}

/** Moshi mirroring the production NetworkModule shape used by the sync layer. */
internal val instrumentedMoshi: Moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

/**
 * In-memory [DbWiper] for the convergence/E2E engine wiring — the schemaVersion
 * wipe path is exercised by the pure-JVM `SyncEnginePullTest`; here we only need a
 * no-op so the engine can be constructed.
 */
internal class NoopDbWiper : DbWiper {
    var wipes = 0
    override suspend fun wipeMirrors() { wipes++ }
}

/** Records kill-switch writes without DataStore (the engine writes the latch). */
internal class RecordingKillSwitchSink : KillSwitchSink {
    val writes = mutableListOf<Boolean>()
    override suspend fun setKillSwitch(on: Boolean) { writes += on }
}

/**
 * In-memory single-row [SyncStateDao] so each simulated client keeps its OWN
 * cursor independent of the shared encrypted DB's `sync_state` row (the
 * convergence test runs two logical clients in one process).
 */
internal class InMemorySyncStateDao : SyncStateDao {
    private val state = MutableStateFlow<SyncStateEntity?>(null)
    override fun observe(): Flow<SyncStateEntity?> = state
    override suspend fun get(): SyncStateEntity? = state.value
    override suspend fun upsert(state: SyncStateEntity) { this.state.value = state }
    override suspend fun updateCursor(cursor: String?) {
        state.value = state.value?.copy(cursor = cursor)
            ?: SyncStateEntity(cursor = cursor, schemaVersion = 1, lastFullSyncAt = null)
    }
    override suspend fun clear() { state.value = null }
}
