package com.gte619n.healthfitness.data.workouts.program

import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds [WorkoutProgramRepository] → [WorkoutProgramRepositoryImpl]. The
 * [WorkoutProgramApi] itself is provided by NetworkModule's base Retrofit
 * (the program DTOs need no polymorphic adapter, unlike the gym/equipment
 * APIs which use the dedicated workoutsRetrofit).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WorkoutProgramDataModule {
    @Binds
    abstract fun bindWorkoutProgramRepository(
        impl: WorkoutProgramRepositoryImpl,
    ): WorkoutProgramRepository
}
