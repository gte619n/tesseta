package com.gte619n.healthfitness.data.blood

import com.gte619n.healthfitness.domain.blood.BloodReadingRepository
import com.gte619n.healthfitness.domain.blood.BloodTestReportRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
internal abstract class BloodDataModule {

    @Binds
    @Singleton
    abstract fun bindBloodReadingRepository(
        impl: BloodReadingRepositoryImpl,
    ): BloodReadingRepository

    @Binds
    @Singleton
    abstract fun bindBloodTestReportRepository(
        impl: BloodTestReportRepositoryImpl,
    ): BloodTestReportRepository

    companion object {
        @Provides
        @Singleton
        fun provideBloodApi(retrofit: Retrofit): BloodApi =
            retrofit.create(BloodApi::class.java)
    }
}
