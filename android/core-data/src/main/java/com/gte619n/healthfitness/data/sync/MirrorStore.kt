package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.db.dao.BloodReadingDao
import com.gte619n.healthfitness.data.db.dao.BloodTestReportDao
import com.gte619n.healthfitness.data.db.dao.BodyCompositionDao
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
import com.gte619n.healthfitness.data.db.dao.NutritionTargetDao
import com.gte619n.healthfitness.data.db.dao.ProtocolDao
import com.gte619n.healthfitness.data.db.dao.UserProfileDao
import com.gte619n.healthfitness.data.db.dao.WeeklyWorkoutAggregateDao
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
import com.gte619n.healthfitness.data.db.entity.MirrorRow
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.NutritionDailyLogEntity
import com.gte619n.healthfitness.data.db.entity.NutritionEntryEntity
import com.gte619n.healthfitness.data.db.entity.NutritionTargetEntity
import com.gte619n.healthfitness.data.db.entity.ProtocolEntity
import com.gte619n.healthfitness.data.db.entity.UserProfileEntity
import com.gte619n.healthfitness.data.db.entity.WeeklyWorkoutAggregateEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 4) — table-name dispatch over the 21 typed mirror DAOs.
 *
 * Room forces one concrete `@Dao`/`@Entity` per table, but the sync engine and
 * outbox reason generically ("apply this change to table X"). [MirrorStore]
 * bridges the two: it exposes a single set of table-name-keyed operations and
 * fans them out to the right DAO, constructing the right concrete entity from the
 * shared [MirrorRow] fields.
 *
 * This is the only file that has to enumerate all 21 tables; the engine/outbox
 * stay generic. It is interface-backed ([MirrorOps]) so unit tests can supply a
 * fake in-memory implementation and exercise the engine on the pure JVM with no
 * Room/SQLCipher/device.
 */
interface MirrorOps {
    /** Insert-or-replace an ACTIVE/ARCHIVED row keyed by [table]. No-op if table unknown. */
    suspend fun upsert(table: String, row: MirrorRowData)

    /** Tombstone the row (status=ARCHIVED, bump lastUpdate). No-op if table unknown. */
    suspend fun markArchived(table: String, id: String, lastUpdate: Long)

    /** Hard-delete the row (used by sign-out / reducer no-op paths). */
    suspend fun delete(table: String, id: String)

    /** Current local row state for LWW, or null if absent / table unknown. */
    suspend fun getRow(table: String, id: String): MirrorRowData?

    /** Flip a row to SYNCED+clean and adopt the server lastUpdate (outbox success). */
    suspend fun markSynced(table: String, id: String, lastUpdate: Long)

    /** Flip a row to FAILED (outbox failure surfaces the D11 badge). */
    suspend fun markFailed(table: String, id: String)

    /** Clear the dirty flag without touching syncState (LWW discard of a local edit). */
    suspend fun clearDirty(table: String, id: String, lastUpdate: Long)
}

/** Plain, DAO-agnostic carrier of the shared [MirrorRow] columns. */
data class MirrorRowData(
    override val id: String,
    override val payloadJson: String,
    override val lastUpdate: Long,
    override val status: String,
    override val dirty: Boolean,
    override val syncState: String,
) : MirrorRow

@Singleton
class MirrorStore @Inject constructor(
    private val bodyComposition: BodyCompositionDao,
    private val bloodReading: BloodReadingDao,
    private val bloodTestReport: BloodTestReportDao,
    private val medication: MedicationDao,
    private val medicationAdherence: MedicationAdherenceDao,
    private val medicationHistory: MedicationHistoryDao,
    private val protocol: ProtocolDao,
    private val goal: GoalDao,
    private val goalPhase: GoalPhaseDao,
    private val goalStep: GoalStepDao,
    private val goalChatThread: GoalChatThreadDao,
    private val goalChatMessage: GoalChatMessageDao,
    private val nutritionDailyLog: NutritionDailyLogDao,
    private val nutritionEntry: NutritionEntryDao,
    private val nutritionTarget: NutritionTargetDao,
    private val location: LocationDao,
    private val dailyMetric: DailyMetricDao,
    private val deviceSync: DeviceSyncDao,
    private val dexaScan: DexaScanDao,
    private val weeklyWorkoutAggregate: WeeklyWorkoutAggregateDao,
    private val userProfile: UserProfileDao,
) : MirrorOps {

    /**
     * One adapter per table abstracts the four operations the sync layer needs
     * over an otherwise-untyped DAO. Built once; keyed by table name.
     */
    private interface Adapter {
        suspend fun upsert(row: MirrorRowData)
        suspend fun markArchived(id: String, lastUpdate: Long)
        suspend fun delete(id: String)
        suspend fun getRow(id: String): MirrorRowData?
    }

    private val adapters: Map<String, Adapter> = buildMap {
        put(MirrorTables.BODY_COMPOSITION, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = bodyComposition.upsert(row.toBodyComposition())
            override suspend fun markArchived(id: String, lastUpdate: Long) = bodyComposition.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = bodyComposition.delete(id)
            override suspend fun getRow(id: String) = bodyComposition.getById(id)?.toData()
        })
        put(MirrorTables.BLOOD_READINGS, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = bloodReading.upsert(row.toBloodReading())
            override suspend fun markArchived(id: String, lastUpdate: Long) = bloodReading.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = bloodReading.delete(id)
            override suspend fun getRow(id: String) = bloodReading.getById(id)?.toData()
        })
        put(MirrorTables.BLOOD_TEST_REPORTS, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = bloodTestReport.upsert(row.toBloodTestReport())
            override suspend fun markArchived(id: String, lastUpdate: Long) = bloodTestReport.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = bloodTestReport.delete(id)
            override suspend fun getRow(id: String) = bloodTestReport.getById(id)?.toData()
        })
        put(MirrorTables.MEDICATIONS, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = medication.upsert(row.toMedication())
            override suspend fun markArchived(id: String, lastUpdate: Long) = medication.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = medication.delete(id)
            override suspend fun getRow(id: String) = medication.getById(id)?.toData()
        })
        put(MirrorTables.MEDICATION_ADHERENCE, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = medicationAdherence.upsert(row.toMedicationAdherence())
            override suspend fun markArchived(id: String, lastUpdate: Long) = medicationAdherence.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = medicationAdherence.delete(id)
            override suspend fun getRow(id: String) = medicationAdherence.getById(id)?.toData()
        })
        put(MirrorTables.MEDICATION_HISTORY, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = medicationHistory.upsert(row.toMedicationHistory())
            override suspend fun markArchived(id: String, lastUpdate: Long) = medicationHistory.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = medicationHistory.delete(id)
            override suspend fun getRow(id: String) = medicationHistory.getById(id)?.toData()
        })
        put(MirrorTables.PROTOCOLS, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = protocol.upsert(row.toProtocol())
            override suspend fun markArchived(id: String, lastUpdate: Long) = protocol.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = protocol.delete(id)
            override suspend fun getRow(id: String) = protocol.getById(id)?.toData()
        })
        put(MirrorTables.GOALS, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = goal.upsert(row.toGoal())
            override suspend fun markArchived(id: String, lastUpdate: Long) = goal.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = goal.delete(id)
            override suspend fun getRow(id: String) = goal.getById(id)?.toData()
        })
        put(MirrorTables.GOAL_PHASES, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = goalPhase.upsert(row.toGoalPhase())
            override suspend fun markArchived(id: String, lastUpdate: Long) = goalPhase.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = goalPhase.delete(id)
            override suspend fun getRow(id: String) = goalPhase.getById(id)?.toData()
        })
        put(MirrorTables.GOAL_STEPS, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = goalStep.upsert(row.toGoalStep())
            override suspend fun markArchived(id: String, lastUpdate: Long) = goalStep.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = goalStep.delete(id)
            override suspend fun getRow(id: String) = goalStep.getById(id)?.toData()
        })
        put(MirrorTables.GOAL_CHAT_THREADS, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = goalChatThread.upsert(row.toGoalChatThread())
            override suspend fun markArchived(id: String, lastUpdate: Long) = goalChatThread.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = goalChatThread.delete(id)
            override suspend fun getRow(id: String) = goalChatThread.getById(id)?.toData()
        })
        put(MirrorTables.GOAL_CHAT_MESSAGES, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = goalChatMessage.upsert(row.toGoalChatMessage())
            override suspend fun markArchived(id: String, lastUpdate: Long) = goalChatMessage.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = goalChatMessage.delete(id)
            override suspend fun getRow(id: String) = goalChatMessage.getById(id)?.toData()
        })
        put(MirrorTables.NUTRITION_DAILY_LOGS, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = nutritionDailyLog.upsert(row.toNutritionDailyLog())
            override suspend fun markArchived(id: String, lastUpdate: Long) = nutritionDailyLog.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = nutritionDailyLog.delete(id)
            override suspend fun getRow(id: String) = nutritionDailyLog.getById(id)?.toData()
        })
        put(MirrorTables.NUTRITION_ENTRIES, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = nutritionEntry.upsert(row.toNutritionEntry())
            override suspend fun markArchived(id: String, lastUpdate: Long) = nutritionEntry.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = nutritionEntry.delete(id)
            override suspend fun getRow(id: String) = nutritionEntry.getById(id)?.toData()
        })
        put(MirrorTables.NUTRITION_TARGETS, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = nutritionTarget.upsert(row.toNutritionTarget())
            override suspend fun markArchived(id: String, lastUpdate: Long) = nutritionTarget.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = nutritionTarget.delete(id)
            override suspend fun getRow(id: String) = nutritionTarget.getById(id)?.toData()
        })
        put(MirrorTables.LOCATIONS, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = location.upsert(row.toLocation())
            override suspend fun markArchived(id: String, lastUpdate: Long) = location.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = location.delete(id)
            override suspend fun getRow(id: String) = location.getById(id)?.toData()
        })
        put(MirrorTables.DAILY_METRICS, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = dailyMetric.upsert(row.toDailyMetric())
            override suspend fun markArchived(id: String, lastUpdate: Long) = dailyMetric.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = dailyMetric.delete(id)
            override suspend fun getRow(id: String) = dailyMetric.getById(id)?.toData()
        })
        put(MirrorTables.DEVICE_SYNCS, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = deviceSync.upsert(row.toDeviceSync())
            override suspend fun markArchived(id: String, lastUpdate: Long) = deviceSync.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = deviceSync.delete(id)
            override suspend fun getRow(id: String) = deviceSync.getById(id)?.toData()
        })
        put(MirrorTables.DEXA_SCANS, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = dexaScan.upsert(row.toDexaScan())
            override suspend fun markArchived(id: String, lastUpdate: Long) = dexaScan.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = dexaScan.delete(id)
            override suspend fun getRow(id: String) = dexaScan.getById(id)?.toData()
        })
        put(MirrorTables.WEEKLY_WORKOUT_AGGREGATES, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = weeklyWorkoutAggregate.upsert(row.toWeeklyWorkoutAggregate())
            override suspend fun markArchived(id: String, lastUpdate: Long) = weeklyWorkoutAggregate.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = weeklyWorkoutAggregate.delete(id)
            override suspend fun getRow(id: String) = weeklyWorkoutAggregate.getById(id)?.toData()
        })
        put(MirrorTables.USER_PROFILE, object : Adapter {
            override suspend fun upsert(row: MirrorRowData) = userProfile.upsert(row.toUserProfile())
            override suspend fun markArchived(id: String, lastUpdate: Long) = userProfile.markArchived(id, lastUpdate)
            override suspend fun delete(id: String) = userProfile.delete(id)
            override suspend fun getRow(id: String) = userProfile.getById(id)?.toData()
        })
    }

    override suspend fun upsert(table: String, row: MirrorRowData) {
        adapters[table]?.upsert(row)
    }

    override suspend fun markArchived(table: String, id: String, lastUpdate: Long) {
        adapters[table]?.markArchived(id, lastUpdate)
    }

    override suspend fun delete(table: String, id: String) {
        adapters[table]?.delete(id)
    }

    override suspend fun getRow(table: String, id: String): MirrorRowData? =
        adapters[table]?.getRow(id)

    override suspend fun markSynced(table: String, id: String, lastUpdate: Long) {
        val adapter = adapters[table] ?: return
        val current = adapter.getRow(id) ?: return
        adapter.upsert(
            current.copy(lastUpdate = lastUpdate, dirty = false, syncState = "SYNCED"),
        )
    }

    override suspend fun markFailed(table: String, id: String) {
        val adapter = adapters[table] ?: return
        val current = adapter.getRow(id) ?: return
        adapter.upsert(current.copy(syncState = "FAILED"))
    }

    override suspend fun clearDirty(table: String, id: String, lastUpdate: Long) {
        val adapter = adapters[table] ?: return
        val current = adapter.getRow(id) ?: return
        adapter.upsert(current.copy(lastUpdate = lastUpdate, dirty = false, syncState = "SYNCED"))
    }
}

// --- Concrete-entity bridges (the only spot that knows each entity type) ---

private fun MirrorRowData.toBodyComposition() = BodyCompositionEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toBloodReading() = BloodReadingEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toBloodTestReport() = BloodTestReportEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toMedication() = MedicationEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toMedicationAdherence() = MedicationAdherenceEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toMedicationHistory() = MedicationHistoryEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toProtocol() = ProtocolEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toGoal() = GoalEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toGoalPhase() = GoalPhaseEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toGoalStep() = GoalStepEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toGoalChatThread() = GoalChatThreadEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toGoalChatMessage() = GoalChatMessageEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toNutritionDailyLog() = NutritionDailyLogEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toNutritionEntry() = NutritionEntryEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toNutritionTarget() = NutritionTargetEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toLocation() = LocationEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toDailyMetric() = DailyMetricEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toDeviceSync() = DeviceSyncEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toDexaScan() = DexaScanEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toWeeklyWorkoutAggregate() = WeeklyWorkoutAggregateEntity(id, payloadJson, lastUpdate, status, dirty, syncState)
private fun MirrorRowData.toUserProfile() = UserProfileEntity(id, payloadJson, lastUpdate, status, dirty, syncState)

private fun MirrorRow.toData() = MirrorRowData(id, payloadJson, lastUpdate, status, dirty, syncState)
