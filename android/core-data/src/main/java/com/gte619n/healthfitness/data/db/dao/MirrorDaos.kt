package com.gte619n.healthfitness.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gte619n.healthfitness.data.db.entity.BloodReadingEntity
import com.gte619n.healthfitness.data.db.entity.BloodTestReportEntity
import com.gte619n.healthfitness.data.db.entity.BodyCompositionEntity
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
import com.gte619n.healthfitness.data.db.entity.NutritionTargetEntity
import com.gte619n.healthfitness.data.db.entity.ProtocolEntity
import com.gte619n.healthfitness.data.db.entity.UserProfileEntity
import com.gte619n.healthfitness.data.db.entity.WeeklyWorkoutAggregateEntity
import kotlinx.coroutines.flow.Flow

/**
 * IMPL-AND-20 (Phase 3) — one focused `@Dao` per mirror table.
 *
 * Room requires concrete (non-generic) DAOs because it generates SQL against a
 * specific `@Entity` type, so each table gets its own interface following the
 * identical contract:
 *  - `observeActive()`  → reactive Flow of non-tombstone rows (status != ARCHIVED),
 *                         newest first; this is the UI source of truth (D8).
 *  - `getById(id)`      → single row (or null).
 *  - `upsert` / `upsertAll` → REPLACE-on-conflict insert used by the pull loop
 *                         and optimistic local writes.
 *  - `markArchived(id, lastUpdate)` → tombstone a row in place (keeps payload so
 *                         a late LWW comparison still has it; sync engine may
 *                         later hard-delete via `delete`).
 *  - `delete(id)`       → hard remove (used by sign-out wipe paths / reducer).
 *
 * The bodies are identical per table; they are concrete only because Room
 * needs a typed entity. Phase 4's sync engine dispatches to the right DAO by
 * table name via [MirrorDaos].
 */

@Dao
interface BodyCompositionDao {
    @Query("SELECT * FROM bodyComposition WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<BodyCompositionEntity>>

    @Query("SELECT * FROM bodyComposition WHERE id = :id")
    suspend fun getById(id: String): BodyCompositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: BodyCompositionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<BodyCompositionEntity>)

    @Query("UPDATE bodyComposition SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM bodyComposition WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface BloodReadingDao {
    @Query("SELECT * FROM bloodReadings WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<BloodReadingEntity>>

    @Query("SELECT * FROM bloodReadings WHERE id = :id")
    suspend fun getById(id: String): BloodReadingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: BloodReadingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<BloodReadingEntity>)

    @Query("UPDATE bloodReadings SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM bloodReadings WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface BloodTestReportDao {
    @Query("SELECT * FROM bloodTestReports WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<BloodTestReportEntity>>

    @Query("SELECT * FROM bloodTestReports WHERE id = :id")
    suspend fun getById(id: String): BloodTestReportEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: BloodTestReportEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<BloodTestReportEntity>)

    @Query("UPDATE bloodTestReports SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM bloodTestReports WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medications WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun getById(id: String): MedicationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MedicationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MedicationEntity>)

    @Query("UPDATE medications SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MedicationAdherenceDao {
    @Query("SELECT * FROM medicationAdherence WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<MedicationAdherenceEntity>>

    @Query("SELECT * FROM medicationAdherence WHERE id = :id")
    suspend fun getById(id: String): MedicationAdherenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MedicationAdherenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MedicationAdherenceEntity>)

    @Query("UPDATE medicationAdherence SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM medicationAdherence WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MedicationHistoryDao {
    @Query("SELECT * FROM medicationHistory WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<MedicationHistoryEntity>>

    @Query("SELECT * FROM medicationHistory WHERE id = :id")
    suspend fun getById(id: String): MedicationHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MedicationHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MedicationHistoryEntity>)

    @Query("UPDATE medicationHistory SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM medicationHistory WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ProtocolDao {
    @Query("SELECT * FROM protocols WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<ProtocolEntity>>

    @Query("SELECT * FROM protocols WHERE id = :id")
    suspend fun getById(id: String): ProtocolEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ProtocolEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ProtocolEntity>)

    @Query("UPDATE protocols SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM protocols WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun getById(id: String): GoalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: GoalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<GoalEntity>)

    @Query("UPDATE goals SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface GoalPhaseDao {
    @Query("SELECT * FROM goalPhases WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<GoalPhaseEntity>>

    @Query("SELECT * FROM goalPhases WHERE id = :id")
    suspend fun getById(id: String): GoalPhaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: GoalPhaseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<GoalPhaseEntity>)

    @Query("UPDATE goalPhases SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM goalPhases WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface GoalStepDao {
    @Query("SELECT * FROM goalSteps WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<GoalStepEntity>>

    @Query("SELECT * FROM goalSteps WHERE id = :id")
    suspend fun getById(id: String): GoalStepEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: GoalStepEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<GoalStepEntity>)

    @Query("UPDATE goalSteps SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM goalSteps WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface GoalChatThreadDao {
    @Query("SELECT * FROM goalChatThreads WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<GoalChatThreadEntity>>

    @Query("SELECT * FROM goalChatThreads WHERE id = :id")
    suspend fun getById(id: String): GoalChatThreadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: GoalChatThreadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<GoalChatThreadEntity>)

    @Query("UPDATE goalChatThreads SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM goalChatThreads WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface GoalChatMessageDao {
    @Query("SELECT * FROM goalChatMessages WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<GoalChatMessageEntity>>

    @Query("SELECT * FROM goalChatMessages WHERE id = :id")
    suspend fun getById(id: String): GoalChatMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: GoalChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<GoalChatMessageEntity>)

    @Query("UPDATE goalChatMessages SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM goalChatMessages WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface NutritionDailyLogDao {
    @Query("SELECT * FROM nutritionDailyLogs WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<NutritionDailyLogEntity>>

    @Query("SELECT * FROM nutritionDailyLogs WHERE id = :id")
    suspend fun getById(id: String): NutritionDailyLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: NutritionDailyLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<NutritionDailyLogEntity>)

    @Query("UPDATE nutritionDailyLogs SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM nutritionDailyLogs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface NutritionEntryDao {
    @Query("SELECT * FROM nutritionEntries WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<NutritionEntryEntity>>

    @Query("SELECT * FROM nutritionEntries WHERE id = :id")
    suspend fun getById(id: String): NutritionEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: NutritionEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<NutritionEntryEntity>)

    @Query("UPDATE nutritionEntries SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM nutritionEntries WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface NutritionTargetDao {
    @Query("SELECT * FROM nutritionTargets WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<NutritionTargetEntity>>

    @Query("SELECT * FROM nutritionTargets WHERE id = :id")
    suspend fun getById(id: String): NutritionTargetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: NutritionTargetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<NutritionTargetEntity>)

    @Query("UPDATE nutritionTargets SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM nutritionTargets WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface LocationDao {
    @Query("SELECT * FROM locations WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations WHERE id = :id")
    suspend fun getById(id: String): LocationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LocationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<LocationEntity>)

    @Query("UPDATE locations SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM locations WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DailyMetricDao {
    @Query("SELECT * FROM dailyMetrics WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<DailyMetricEntity>>

    @Query("SELECT * FROM dailyMetrics WHERE id = :id")
    suspend fun getById(id: String): DailyMetricEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DailyMetricEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DailyMetricEntity>)

    @Query("UPDATE dailyMetrics SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM dailyMetrics WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DeviceSyncDao {
    @Query("SELECT * FROM deviceSyncs WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<DeviceSyncEntity>>

    @Query("SELECT * FROM deviceSyncs WHERE id = :id")
    suspend fun getById(id: String): DeviceSyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DeviceSyncEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DeviceSyncEntity>)

    @Query("UPDATE deviceSyncs SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM deviceSyncs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DexaScanDao {
    @Query("SELECT * FROM dexaScans WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<DexaScanEntity>>

    @Query("SELECT * FROM dexaScans WHERE id = :id")
    suspend fun getById(id: String): DexaScanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DexaScanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DexaScanEntity>)

    @Query("UPDATE dexaScans SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM dexaScans WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface WeeklyWorkoutAggregateDao {
    @Query("SELECT * FROM weeklyWorkoutAggregates WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<WeeklyWorkoutAggregateEntity>>

    @Query("SELECT * FROM weeklyWorkoutAggregates WHERE id = :id")
    suspend fun getById(id: String): WeeklyWorkoutAggregateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: WeeklyWorkoutAggregateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<WeeklyWorkoutAggregateEntity>)

    @Query("UPDATE weeklyWorkoutAggregates SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM weeklyWorkoutAggregates WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM userProfile WHERE status != 'ARCHIVED' ORDER BY lastUpdate DESC")
    fun observeActive(): Flow<List<UserProfileEntity>>

    @Query("SELECT * FROM userProfile WHERE id = :id")
    suspend fun getById(id: String): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: UserProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<UserProfileEntity>)

    @Query("UPDATE userProfile SET status = 'ARCHIVED', lastUpdate = :lastUpdate WHERE id = :id")
    suspend fun markArchived(id: String, lastUpdate: Long)

    @Query("DELETE FROM userProfile WHERE id = :id")
    suspend fun delete(id: String)
}
