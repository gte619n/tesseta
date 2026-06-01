package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.domain.medications.AdherenceRepository
import com.gte619n.healthfitness.domain.medications.DrugRepository
import com.gte619n.healthfitness.domain.medications.MedicationRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Hilt wiring for the medications data layer (IMPL-AND-03). Owns the medication
 * Retrofit API providers and repository bindings; does NOT touch the shared
 * NetworkModule (which provides Retrofit / Moshi / OkHttp / SseClient).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MedicationsDataModule {

    @Binds
    @Singleton
    internal abstract fun bindMedicationRepository(
        impl: DefaultMedicationRepository,
    ): MedicationRepository

    @Binds
    @Singleton
    internal abstract fun bindDrugRepository(
        impl: DefaultDrugRepository,
    ): DrugRepository

    @Binds
    @Singleton
    internal abstract fun bindAdherenceRepository(
        impl: DefaultAdherenceRepository,
    ): AdherenceRepository

    companion object {
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
}
