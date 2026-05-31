package com.gte619n.healthfitness.mobile.di

import com.gte619n.healthfitness.data.net.BackendBaseUrl
import com.gte619n.healthfitness.mobile.BuildConfig
import com.gte619n.healthfitness.ui.snackbar.SnackbarController
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

    // IMPL-AND-00: single snackbar bus shared between ViewModels (which call
    // show()) and the Compose Scaffold (which collects messages).
    @Provides
    @Singleton
    fun provideSnackbarController(): SnackbarController = SnackbarController()

    // IMPL-AND-02: the Google Health scope upgrade reuses the web OAuth client
    // id as the audience. Only :app sees app BuildConfig, so it provides it.
    @Provides
    @javax.inject.Named("webOauthClientId")
    fun provideWebOauthClientId(): String = BuildConfig.WEB_OAUTH_CLIENT_ID
}
