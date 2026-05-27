package com.gte619n.healthfitness.data.googlehealth

import com.gte619n.healthfitness.domain.googlehealth.GoogleHealthRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

/**
 * Hilt bindings for the Google Health backend slice — the Retrofit
 * service + the repository binding. The on-device authorization-code
 * flow lives separately in
 * [com.gte619n.healthfitness.data.auth.GoogleHealthScopeRepository];
 * this module only owns the talk-to-our-backend half.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class GoogleHealthDataModule {

    @Binds
    @Singleton
    abstract fun bindGoogleHealthRepository(
        impl: GoogleHealthRepositoryImpl,
    ): GoogleHealthRepository

    companion object {
        @Provides
        @Singleton
        internal fun provideGoogleHealthService(retrofit: Retrofit): GoogleHealthService =
            retrofit.create(GoogleHealthService::class.java)
    }
}
