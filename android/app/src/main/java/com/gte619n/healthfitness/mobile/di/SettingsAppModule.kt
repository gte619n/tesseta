package com.gte619n.healthfitness.mobile.di

import android.content.Context
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import com.gte619n.healthfitness.data.auth.IdTokenCache
import com.gte619n.healthfitness.feature.settings.AppVersionInfo
import com.gte619n.healthfitness.mobile.BuildConfig
import com.gte619n.healthfitness.mobile.wear.PhoneTokenPublisher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// IMPL-AND-02 app bindings: the settings ViewModels inject GoogleAuthRepository
// (for sign-out) and AppVersionInfo (for the About card). GoogleAuthRepository
// is a plain class today (constructed manually in MainActivity for interactive
// sign-in); here we provide an application-scoped instance for the settings
// sign-out path, which only needs silentRefresh()/signOut().
@Module
@InstallIn(SingletonComponent::class)
object SettingsAppModule {

    @Provides
    @Singleton
    fun provideGoogleAuthRepository(
        @ApplicationContext context: Context,
        cache: IdTokenCache,
    ): GoogleAuthRepository = GoogleAuthRepository(
        context = context,
        cache = cache,
        webOauthClientId = BuildConfig.WEB_OAUTH_CLIENT_ID,
        onTokenIssued = { token, _ -> PhoneTokenPublisher(context).publish(token) },
    )

    @Provides
    @Singleton
    fun provideAppVersionInfo(): AppVersionInfo = object : AppVersionInfo {
        override val versionName: String = BuildConfig.VERSION_NAME
        override val versionCode: Int = BuildConfig.VERSION_CODE
    }
}
