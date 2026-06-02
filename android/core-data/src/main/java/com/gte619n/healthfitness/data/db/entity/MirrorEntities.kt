package com.gte619n.healthfitness.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * IMPL-AND-20 (Phase 3) — one mirror `@Entity` per in-scope collection.
 *
 * All entities share the [MirrorRow] shape exactly (the spec's `MedicationEntity`
 * template): `id` PK = backend UUID, `payloadJson` (Moshi), `lastUpdate` (server
 * epoch millis), `status` (ACTIVE|ARCHIVED), `dirty`, `syncState`
 * (SYNCED|PENDING|FAILED). They differ only in `tableName`.
 *
 * `lastUpdate` is indexed on every table: the delta-pull cursor and LWW
 * comparison (Phase 4) read rows ordered by / filtered on `lastUpdate`.
 */

@Entity(tableName = MirrorTables.BODY_COMPOSITION, indices = [Index("lastUpdate")])
data class BodyCompositionEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.BLOOD_READINGS, indices = [Index("lastUpdate")])
data class BloodReadingEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.BLOOD_TEST_REPORTS, indices = [Index("lastUpdate")])
data class BloodTestReportEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.MEDICATIONS, indices = [Index("lastUpdate")])
data class MedicationEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.MEDICATION_ADHERENCE, indices = [Index("lastUpdate")])
data class MedicationAdherenceEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.MEDICATION_HISTORY, indices = [Index("lastUpdate")])
data class MedicationHistoryEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.PROTOCOLS, indices = [Index("lastUpdate")])
data class ProtocolEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.GOALS, indices = [Index("lastUpdate")])
data class GoalEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.GOAL_PHASES, indices = [Index("lastUpdate")])
data class GoalPhaseEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.GOAL_STEPS, indices = [Index("lastUpdate")])
data class GoalStepEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.GOAL_CHAT_THREADS, indices = [Index("lastUpdate")])
data class GoalChatThreadEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.GOAL_CHAT_MESSAGES, indices = [Index("lastUpdate")])
data class GoalChatMessageEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.NUTRITION_DAILY_LOGS, indices = [Index("lastUpdate")])
data class NutritionDailyLogEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.NUTRITION_ENTRIES, indices = [Index("lastUpdate")])
data class NutritionEntryEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.NUTRITION_TARGETS, indices = [Index("lastUpdate")])
data class NutritionTargetEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.LOCATIONS, indices = [Index("lastUpdate")])
data class LocationEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.DAILY_METRICS, indices = [Index("lastUpdate")])
data class DailyMetricEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.DEVICE_SYNCS, indices = [Index("lastUpdate")])
data class DeviceSyncEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.DEXA_SCANS, indices = [Index("lastUpdate")])
data class DexaScanEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.WEEKLY_WORKOUT_AGGREGATES, indices = [Index("lastUpdate")])
data class WeeklyWorkoutAggregateEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Entity(tableName = MirrorTables.USER_PROFILE, indices = [Index("lastUpdate")])
data class UserProfileEntity(
    @PrimaryKey override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow
