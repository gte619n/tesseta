package com.gte619n.healthfitness.mobile.di

import com.gte619n.healthfitness.data.net.BackendBaseUrl
import com.gte619n.healthfitness.mobile.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// App-only bindings into the Hilt graph. core-data's NetworkModule consumes the
// backend base URL via the @BackendBaseUrl qualifier; only :app knows the
// concrete value (it lives in app BuildConfig), so it provides it here.
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @BackendBaseUrl
    fun provideBackendBaseUrl(): String = BuildConfig.BACKEND_BASE_URL
}
