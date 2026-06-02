package com.gte619n.healthfitness.data.db

import android.content.Context
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
import com.gte619n.healthfitness.data.db.dao.OutboxDao
import com.gte619n.healthfitness.data.db.dao.ProtocolDao
import com.gte619n.healthfitness.data.db.dao.SyncStateDao
import com.gte619n.healthfitness.data.db.dao.UserProfileDao
import com.gte619n.healthfitness.data.db.dao.WeeklyWorkoutAggregateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 3) — Hilt provisioning for the offline store.
 *
 * Provides the singleton [DbKeystore], the singleton SQLCipher-backed
 * [HfDatabase], and every DAO. `DbWipe` is `@Inject`-constructed and consumes a
 * `Provider<HfDatabase>` so sign-out can wipe without forcing the DB to open.
 */
@Module
@InstallIn(SingletonComponent::class)
object DbModule {

    @Provides
    @Singleton
    fun provideDbKeystore(@ApplicationContext context: Context): DbKeystore =
        DbKeystore(context)

    @Provides
    @Singleton
    fun provideHfDatabase(
        @ApplicationContext context: Context,
        keystore: DbKeystore,
    ): HfDatabase = HfDatabase.build(context, keystore)

    @Provides fun provideSyncStateDao(db: HfDatabase): SyncStateDao = db.syncStateDao()
    @Provides fun provideOutboxDao(db: HfDatabase): OutboxDao = db.outboxDao()

    @Provides fun provideBodyCompositionDao(db: HfDatabase): BodyCompositionDao = db.bodyCompositionDao()
    @Provides fun provideBloodReadingDao(db: HfDatabase): BloodReadingDao = db.bloodReadingDao()
    @Provides fun provideBloodTestReportDao(db: HfDatabase): BloodTestReportDao = db.bloodTestReportDao()
    @Provides fun provideMedicationDao(db: HfDatabase): MedicationDao = db.medicationDao()
    @Provides fun provideMedicationAdherenceDao(db: HfDatabase): MedicationAdherenceDao = db.medicationAdherenceDao()
    @Provides fun provideMedicationHistoryDao(db: HfDatabase): MedicationHistoryDao = db.medicationHistoryDao()
    @Provides fun provideProtocolDao(db: HfDatabase): ProtocolDao = db.protocolDao()
    @Provides fun provideGoalDao(db: HfDatabase): GoalDao = db.goalDao()
    @Provides fun provideGoalPhaseDao(db: HfDatabase): GoalPhaseDao = db.goalPhaseDao()
    @Provides fun provideGoalStepDao(db: HfDatabase): GoalStepDao = db.goalStepDao()
    @Provides fun provideGoalChatThreadDao(db: HfDatabase): GoalChatThreadDao = db.goalChatThreadDao()
    @Provides fun provideGoalChatMessageDao(db: HfDatabase): GoalChatMessageDao = db.goalChatMessageDao()
    @Provides fun provideNutritionDailyLogDao(db: HfDatabase): NutritionDailyLogDao = db.nutritionDailyLogDao()
    @Provides fun provideNutritionEntryDao(db: HfDatabase): NutritionEntryDao = db.nutritionEntryDao()
    @Provides fun provideNutritionTargetDao(db: HfDatabase): NutritionTargetDao = db.nutritionTargetDao()
    @Provides fun provideLocationDao(db: HfDatabase): LocationDao = db.locationDao()
    @Provides fun provideDailyMetricDao(db: HfDatabase): DailyMetricDao = db.dailyMetricDao()
    @Provides fun provideDeviceSyncDao(db: HfDatabase): DeviceSyncDao = db.deviceSyncDao()
    @Provides fun provideDexaScanDao(db: HfDatabase): DexaScanDao = db.dexaScanDao()
    @Provides fun provideWeeklyWorkoutAggregateDao(db: HfDatabase): WeeklyWorkoutAggregateDao = db.weeklyWorkoutAggregateDao()
    @Provides fun provideUserProfileDao(db: HfDatabase): UserProfileDao = db.userProfileDao()
}
