package com.gte619n.healthfitness.data.settings

import android.content.Context
import com.gte619n.healthfitness.data.auth.GoogleHealthScopeRepository
import com.gte619n.healthfitness.data.googlehealth.GoogleHealthRepositoryImpl
import com.gte619n.healthfitness.data.googlehealth.GoogleHealthService
import com.gte619n.healthfitness.data.prefs.UnitPreferencesRepositoryImpl
import com.gte619n.healthfitness.data.profile.ProfileRepositoryImpl
import com.gte619n.healthfitness.data.profile.ProfileService
import com.gte619n.healthfitness.domain.googlehealth.GoogleHealthRepository
import com.gte619n.healthfitness.domain.prefs.UnitPreferencesRepository
import com.gte619n.healthfitness.domain.profile.ProfileRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import retrofit2.Retrofit
import retrofit2.create

// IMPL-AND-02's own Hilt module. Lives in the feature's core-data package
// rather than touching NetworkModule. Provides the Retrofit services and the
// GoogleHealthScopeRepository, and binds the repository interfaces.
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsDataModule {

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds
    @Singleton
    abstract fun bindGoogleHealthRepository(impl: GoogleHealthRepositoryImpl): GoogleHealthRepository

    @Binds
    @Singleton
    abstract fun bindUnitPreferencesRepository(impl: UnitPreferencesRepositoryImpl): UnitPreferencesRepository

    companion object {
        @Provides
        @Singleton
        fun provideProfileService(retrofit: Retrofit): ProfileService =
            retrofit.create()

        @Provides
        @Singleton
        fun provideGoogleHealthService(retrofit: Retrofit): GoogleHealthService =
            retrofit.create()

        @Provides
        @Singleton
        fun provideGoogleHealthScopeRepository(
            @ApplicationContext context: Context,
            @Named("webOauthClientId") webOauthClientId: String,
        ): GoogleHealthScopeRepository =
            GoogleHealthScopeRepository(context, webOauthClientId)
    }
}
