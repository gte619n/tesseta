package com.gte619n.healthfitness.data.reminders

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import java.time.Clock
import javax.inject.Singleton

/** Hilt wiring for the medication-reminder feature (IMPL-16 Part A; IMPL-21). */
@Module
@InstallIn(SingletonComponent::class)
object RemindersDataModule {

    @Provides
    @Singleton
    fun provideReminderSettingsApi(retrofit: Retrofit): ReminderSettingsApi =
        retrofit.create(ReminderSettingsApi::class.java)

    // IMPL-21: the engine's framework seams + injectable clock (decision D-5).
    @Provides
    @Singleton
    fun provideReminderNotifier(impl: AndroidReminderNotifier): ReminderNotifier = impl

    @Provides
    @Singleton
    fun provideReminderScheduler(impl: AndroidReminderScheduler): ReminderScheduler = impl

    @Provides
    @Singleton
    fun provideReminderClock(): Clock = Clock.systemDefaultZone()
}
