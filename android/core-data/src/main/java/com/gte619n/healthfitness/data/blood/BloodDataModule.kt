package com.gte619n.healthfitness.data.blood

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

// BloodReadingRepository / BloodTestReportRepository are concrete @Inject
// classes — no @Binds needed; this module just provides the Retrofit API.
@Module
@InstallIn(SingletonComponent::class)
internal object BloodDataModule {

    @Provides
    @Singleton
    fun provideBloodApi(retrofit: Retrofit): BloodApi =
        retrofit.create(BloodApi::class.java)
}
