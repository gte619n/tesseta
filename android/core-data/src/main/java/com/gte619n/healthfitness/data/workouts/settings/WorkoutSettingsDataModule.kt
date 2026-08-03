package com.gte619n.healthfitness.data.workouts.settings

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Hilt wiring for the workout-preferences (streak target) data layer. */
@Module
@InstallIn(SingletonComponent::class)
object WorkoutSettingsDataModule {

    @Provides
    @Singleton
    fun provideWorkoutSettingsApi(retrofit: Retrofit): WorkoutSettingsApi =
        retrofit.create(WorkoutSettingsApi::class.java)
}
