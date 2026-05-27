package com.gte619n.healthfitness.data.profile

import com.gte619n.healthfitness.domain.profile.ProfileRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

/**
 * Hilt bindings for the profile slice — `@Binds` for the repository
 * interface, `@Provides` for the Retrofit-built service. Matches the
 * shape of [com.gte619n.healthfitness.data.dashboard.DashboardDataModule].
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProfileDataModule {

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    companion object {
        @Provides
        @Singleton
        internal fun provideProfileService(retrofit: Retrofit): ProfileService =
            retrofit.create(ProfileService::class.java)
    }
}
