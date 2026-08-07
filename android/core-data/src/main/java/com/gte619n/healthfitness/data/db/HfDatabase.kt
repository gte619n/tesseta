package com.gte619n.healthfitness.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gte619n.healthfitness.data.db.dao.BloodReadingDao
import com.gte619n.healthfitness.data.db.dao.BloodTestReportDao
import com.gte619n.healthfitness.data.db.dao.BodyCompositionDao
import com.gte619n.healthfitness.data.db.dao.CatalogCacheDao
import com.gte619n.healthfitness.data.db.dao.DailyMetricDao
import com.gte619n.healthfitness.data.db.dao.DeviceSyncDao
import com.gte619n.healthfitness.data.db.dao.DexaScanDao
import com.gte619n.healthfitness.data.db.dao.GoalChatMessageDao
import com.gte619n.healthfitness.data.db.dao.GoalChatThreadDao
import com.gte619n.healthfitness.data.db.dao.GoalDao
import com.gte619n.healthfitness.data.db.dao.GoalPhaseDao
import com.gte619n.healthfitness.data.db.dao.GoalStepDao
import com.gte619n.healthfitness.data.db.dao.LocationDao
import com.gte619n.healthfitness.data.db.dao.MedicationAdherenceDao
import com.gte619n.healthfitness.data.db.dao.MedicationDao
import com.gte619n.healthfitness.data.db.dao.MedicationHistoryDao
import com.gte619n.healthfitness.data.db.dao.NutritionDailyLogDao
import com.gte619n.healthfitness.data.db.dao.NutritionEntryDao
import com.gte619n.healthfitness.data.db.dao.NutritionOpDao
import com.gte619n.healthfitness.data.db.dao.NutritionTargetDao
import com.gte619n.healthfitness.data.db.dao.OutboxDao
import com.gte619n.healthfitness.data.db.dao.ProtocolDao
import com.gte619n.healthfitness.data.db.dao.SyncStateDao
import com.gte619n.healthfitness.data.db.dao.UserProfileDao
import com.gte619n.healthfitness.data.db.dao.WeeklyWorkoutAggregateDao
import com.gte619n.healthfitness.data.db.dao.WorkoutProgramDao
import com.gte619n.healthfitness.data.db.dao.WorkoutScheduledDao
import com.gte619n.healthfitness.data.db.dao.WorkoutSessionDraftDao
import com.gte619n.healthfitness.data.db.entity.BloodReadingEntity
import com.gte619n.healthfitness.data.db.entity.BloodTestReportEntity
import com.gte619n.healthfitness.data.db.entity.BodyCompositionEntity
import com.gte619n.healthfitness.data.db.entity.CatalogCacheEntity
import com.gte619n.healthfitness.data.db.entity.DailyMetricEntity
import com.gte619n.healthfitness.data.db.entity.DeviceSyncEntity
import com.gte619n.healthfitness.data.db.entity.DexaScanEntity
import com.gte619n.healthfitness.data.db.entity.GoalChatMessageEntity
import com.gte619n.healthfitness.data.db.entity.GoalChatThreadEntity
import com.gte619n.healthfitness.data.db.entity.GoalEntity
import com.gte619n.healthfitness.data.db.entity.GoalPhaseEntity
import com.gte619n.healthfitness.data.db.entity.GoalStepEntity
import com.gte619n.healthfitness.data.db.entity.LocationEntity
import com.gte619n.healthfitness.data.db.entity.MedicationAdherenceEntity
import com.gte619n.healthfitness.data.db.entity.MedicationEntity
import com.gte619n.healthfitness.data.db.entity.MedicationHistoryEntity
import com.gte619n.healthfitness.data.db.entity.NutritionDailyLogEntity
import com.gte619n.healthfitness.data.db.entity.NutritionEntryEntity
import com.gte619n.healthfitness.data.db.entity.NutritionOpEntity
import com.gte619n.healthfitness.data.db.entity.NutritionTargetEntity
import com.gte619n.healthfitness.data.db.entity.OutboxEntity
import com.gte619n.healthfitness.data.db.entity.ProtocolEntity
import com.gte619n.healthfitness.data.db.entity.SyncStateEntity
import com.gte619n.healthfitness.data.db.entity.UserProfileEntity
import com.gte619n.healthfitness.data.db.entity.WeeklyWorkoutAggregateEntity
import com.gte619n.healthfitness.data.db.entity.WorkoutProgramEntity
import com.gte619n.healthfitness.data.db.entity.WorkoutScheduledEntity
import com.gte619n.healthfitness.data.db.entity.WorkoutSessionDraftEntity
import net.sqlcipher.database.SupportFactory

/**
 * IMPL-AND-20 (Phase 3) — the on-device, SQLCipher-encrypted offline store.
 *
 * One database file [DB_NAME] holding two structural tables (`sync_state`,
 * `outbox`) plus one mirror table per in-scope collection. It is the UI source
 * of truth (D8); the network layer only fills/refreshes it (Phase 4/5).
 *
 * Opened via SQLCipher's [SupportFactory] keyed with the Keystore-wrapped
 * passphrase from [DbKeystore] (D5). The whole file is wiped on sign-out by
 * [DbWipe] for PHI hygiene.
 */
@Database(
    entities = [
        // structural
        SyncStateEntity::class,
        OutboxEntity::class,
        // mirror tables (one per in-scope collection)
        BodyCompositionEntity::class,
        BloodReadingEntity::class,
        BloodTestReportEntity::class,
        MedicationEntity::class,
        MedicationAdherenceEntity::class,
        MedicationHistoryEntity::class,
        ProtocolEntity::class,
        GoalEntity::class,
        GoalPhaseEntity::class,
        GoalStepEntity::class,
        GoalChatThreadEntity::class,
        GoalChatMessageEntity::class,
        NutritionDailyLogEntity::class,
        NutritionEntryEntity::class,
        NutritionTargetEntity::class,
        LocationEntity::class,
        DailyMetricEntity::class,
        DeviceSyncEntity::class,
        DexaScanEntity::class,
        WeeklyWorkoutAggregateEntity::class,
        WorkoutProgramEntity::class,
        WorkoutScheduledEntity::class,
        UserProfileEntity::class,
        // device-local (non-mirror) tables
        WorkoutSessionDraftEntity::class,
        // offline-fix: shared read cache for fetched catalog entities (ADR-0018) —
        // NOT a mirror table, NOT wired into the sync engine.
        CatalogCacheEntity::class,
        // durable queue of in-flight nutrition AI-create ops (describe / saved-meal
        // / meal-items / label). Device-local, its own drain rail — NOT a mirror.
        NutritionOpEntity::class,
    ],
    // v3: bumped to force a destructive wipe + full resync so rows pulled before
    // the delta-doc id injection (which lacked their document id) are re-fetched
    // cleanly. The auth token lives in DataStore, not Room, so this doesn't sign
    // the user out.
    // v4: adds the workoutSessionDrafts table (ADR-0012 phone workout logger) —
    // additive MIGRATION_3_4, no wipe (a draft is the ONLY copy of an
    // in-progress session, so destructive fallback must never be its upgrade path).
    // v5: adds the catalog_cache table (offline-fix, ADR-0018) — a bounded read
    // cache of fetched reference entities (equipment/food/drug). Additive
    // MIGRATION_4_5; a plain local cache, not a mirror and not synced.
    // v6: adds the nutritionOps table — the durable queue backing the hardened
    // nutrition AI-create flows (describe / saved-meal / meal-items / label).
    // Additive MIGRATION_5_6; device-local (holds an in-flight op that a wipe
    // would drop), not a mirror and not synced.
    version = 6,
    exportSchema = true,
)
abstract class HfDatabase : RoomDatabase() {
    abstract fun syncStateDao(): SyncStateDao
    abstract fun outboxDao(): OutboxDao

    abstract fun bodyCompositionDao(): BodyCompositionDao
    abstract fun bloodReadingDao(): BloodReadingDao
    abstract fun bloodTestReportDao(): BloodTestReportDao
    abstract fun medicationDao(): MedicationDao
    abstract fun medicationAdherenceDao(): MedicationAdherenceDao
    abstract fun medicationHistoryDao(): MedicationHistoryDao
    abstract fun protocolDao(): ProtocolDao
    abstract fun goalDao(): GoalDao
    abstract fun goalPhaseDao(): GoalPhaseDao
    abstract fun goalStepDao(): GoalStepDao
    abstract fun goalChatThreadDao(): GoalChatThreadDao
    abstract fun goalChatMessageDao(): GoalChatMessageDao
    abstract fun nutritionDailyLogDao(): NutritionDailyLogDao
    abstract fun nutritionEntryDao(): NutritionEntryDao
    abstract fun nutritionTargetDao(): NutritionTargetDao
    abstract fun locationDao(): LocationDao
    abstract fun dailyMetricDao(): DailyMetricDao
    abstract fun deviceSyncDao(): DeviceSyncDao
    abstract fun dexaScanDao(): DexaScanDao
    abstract fun weeklyWorkoutAggregateDao(): WeeklyWorkoutAggregateDao
    abstract fun workoutProgramDao(): WorkoutProgramDao
    abstract fun workoutScheduledDao(): WorkoutScheduledDao
    abstract fun userProfileDao(): UserProfileDao

    abstract fun workoutSessionDraftDao(): WorkoutSessionDraftDao

    abstract fun catalogCacheDao(): CatalogCacheDao

    abstract fun nutritionOpDao(): NutritionOpDao

    companion object {
        const val DB_NAME = "hf-offline.db"

        /**
         * v3 → v4: add the workoutSessionDrafts table (ADR-0012). Additive —
         * must match the exported schema 4.json exactly so Room's identity-hash
         * validation passes.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workoutSessionDrafts` (" +
                        "`programId` TEXT NOT NULL, `scheduledId` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `lastActivityAt` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, `sessionJson` TEXT NOT NULL, " +
                        "`loggedJson` TEXT NOT NULL, " +
                        "PRIMARY KEY(`programId`, `scheduledId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workoutSessionDrafts_lastActivityAt` " +
                        "ON `workoutSessionDrafts` (`lastActivityAt`)",
                )
            }
        }

        /**
         * v4 → v5: add the catalog_cache table (offline-fix, ADR-0018). Additive —
         * a bounded read cache of fetched reference entities (equipment/food/drug),
         * keyed by `(type, id)`. Must match the exported schema 5.json exactly so
         * Room's identity-hash validation passes.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `catalog_cache` (" +
                        "`type` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                        "`json` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`type`, `id`))",
                )
            }
        }

        /**
         * v5 → v6: add the nutritionOps table — the durable queue backing the
         * hardened nutrition AI-create flows. Additive; keyed by op `id` with an
         * index on `nextAttemptAt` (the drain's "due now" query). Must match the
         * exported schema 6.json exactly so Room's identity-hash validation passes.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `nutritionOps` (" +
                        "`id` TEXT NOT NULL, `type` TEXT NOT NULL, `date` TEXT NOT NULL, " +
                        "`mealWire` TEXT NOT NULL, `clientEntryId` TEXT NOT NULL, " +
                        "`idempotencyKey` TEXT NOT NULL, `payloadJson` TEXT, `jpegPath` TEXT, " +
                        "`label` TEXT NOT NULL, `attempts` INTEGER NOT NULL, " +
                        "`nextAttemptAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_nutritionOps_nextAttemptAt` " +
                        "ON `nutritionOps` (`nextAttemptAt`)",
                )
            }
        }

        /**
         * Builds the encrypted database. Loads the SQLCipher native libs, fetches
         * the Keystore-wrapped passphrase, and hands it to [SupportFactory]
         * (which copies then zeroes the byte array).
         */
        fun build(context: Context, keystore: DbKeystore): HfDatabase {
            net.sqlcipher.database.SQLiteDatabase.loadLibs(context)
            val resolution = keystore.resolvePassphrase()
            if (resolution.regenerated) {
                // The previous passphrase was unrecoverable (e.g. the prefs blob was
                // restored from backup without its non-exportable Keystore key). Any
                // existing DB file is encrypted under that lost key and cannot be
                // opened — delete it (and its -wal/-shm/journal sidecars) so Room
                // recreates a fresh store the sync layer refills from the backend.
                context.deleteDatabase(DB_NAME)
            }
            val factory = SupportFactory(resolution.passphrase)
            return Room.databaseBuilder(context, HfDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                // Known upgrades run additive migrations (the drafts table holds
                // device-only data that a wipe would destroy)…
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                // …while schemaVersion bumps (D13) trigger an explicit wipe+resync
                // at the sync layer, not a Room migration; fall back destructively
                // so a mismatched on-disk schema can never wedge the app.
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
