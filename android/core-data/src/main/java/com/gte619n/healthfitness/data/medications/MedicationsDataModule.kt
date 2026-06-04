package com.gte619n.healthfitness.data.medications

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Hilt wiring for the medications data layer (IMPL-AND-03). Owns the medication
 * Retrofit API providers; does NOT touch the shared NetworkModule (which provides
 * Retrofit / Moshi / OkHttp / SseClient). The repositories themselves are
 * concrete @Inject classes, so no @Binds are needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object MedicationsDataModule {

    @Provides
    @Singleton
    internal fun provideMedicationsApi(retrofit: Retrofit): MedicationsApi =
        retrofit.create(MedicationsApi::class.java)

    @Provides
    @Singleton
    internal fun provideDrugsApi(retrofit: Retrofit): DrugsApi =
        retrofit.create(DrugsApi::class.java)

    @Provides
    @Singleton
    internal fun provideAdherenceApi(retrofit: Retrofit): AdherenceApi =
        retrofit.create(AdherenceApi::class.java)
}
