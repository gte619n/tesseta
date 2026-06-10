package com.gte619n.healthfitness.data.reminders

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Hilt wiring for the medication-reminder feature (IMPL-16 Part A). */
@Module
@InstallIn(SingletonComponent::class)
object RemindersDataModule {

    @Provides
    @Singleton
    fun provideReminderSettingsApi(retrofit: Retrofit): ReminderSettingsApi =
        retrofit.create(ReminderSettingsApi::class.java)
}
