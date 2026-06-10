package com.gte619n.healthfitness.data.workouts.session

import com.gte619n.healthfitness.data.db.dao.WorkoutScheduledDao
import com.gte619n.healthfitness.data.db.dao.WorkoutSessionDraftDao
import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.data.workouts.program.WorkoutProgramApi
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

/**
 * ADR-0012 (IMPL-AND-16) — Hilt wiring for the workout-session draft store.
 * Provided (not @Inject-constructed) so [WorkoutSessionRepositoryImpl]'s
 * `clock` default stays the wall clock in production while tests inject a fake
 * — the same pattern SyncDataModule uses for OutboxRepository.
 */
@Module
@InstallIn(SingletonComponent::class)
object WorkoutSessionDataModule {

    @Provides
    @Singleton
    fun provideWorkoutSessionRepository(
        api: WorkoutProgramApi,
        draftDao: WorkoutSessionDraftDao,
        scheduledDao: WorkoutScheduledDao,
        support: MirrorRepositorySupport,
        moshi: Moshi,
        @IoDispatcher io: CoroutineDispatcher,
    ): WorkoutSessionRepository =
        WorkoutSessionRepositoryImpl(api, draftDao, scheduledDao, support, moshi, io)
}
