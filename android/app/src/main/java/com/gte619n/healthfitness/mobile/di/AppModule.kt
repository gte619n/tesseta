package com.gte619n.healthfitness.mobile.di

import android.content.Context
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import com.gte619n.healthfitness.data.auth.IdTokenCache
import com.gte619n.healthfitness.mobile.BuildConfig
import com.gte619n.healthfitness.mobile.wear.PhoneTokenPublisher
import com.gte619n.healthfitness.network.BackendBaseUrlProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Phone-side bindings owned by the `app` module:
 *
 *  - `IdTokenCache` + `GoogleAuthRepository` + `PhoneTokenPublisher` —
 *    previously constructed by hand in `MainActivity`. Hilt now owns them
 *    so feature ViewModels can inject the repo without re-creating it.
 *  - `BackendBaseUrlProvider` — flavor-scoped `BuildConfig.BACKEND_BASE_URL`
 *    is only resolvable in `app/`, so the impl lives here (the contract
 *    lives in `core-network`).
 *  - `@ApplicationScope CoroutineScope` — for fire-and-forget work that
 *    must outlive any screen. `SupervisorJob` so a single failure can't
 *    cancel siblings; `Main.immediate` so observers update synchronously
 *    when already on the main thread.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun idTokenCache(@ApplicationContext ctx: Context): IdTokenCache =
        IdTokenCache(ctx)

    @Provides
    @Singleton
    fun phoneTokenPublisher(@ApplicationContext ctx: Context): PhoneTokenPublisher =
        PhoneTokenPublisher(ctx)

    @Provides
    @Singleton
    fun googleAuthRepository(
        @ApplicationContext ctx: Context,
        cache: IdTokenCache,
        publisher: PhoneTokenPublisher,
    ): GoogleAuthRepository = GoogleAuthRepository(
        context = ctx,
        cache = cache,
        webOauthClientId = BuildConfig.WEB_OAUTH_CLIENT_ID,
        onTokenIssued = { token, _ -> publisher.publish(token) },
    )

    @Provides
    @Singleton
    fun backendBaseUrlProvider(): BackendBaseUrlProvider = object : BackendBaseUrlProvider {
        override val baseUrl: String = BuildConfig.BACKEND_BASE_URL
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
