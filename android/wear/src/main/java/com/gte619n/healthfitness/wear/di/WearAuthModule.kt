package com.gte619n.healthfitness.wear.di

import android.content.Context
import com.gte619n.healthfitness.wear.auth.WearIdTokenCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wear-side auth bindings. Currently just the [WearIdTokenCache] — the
 * wear-side network module + Retrofit interfaces drop in once any wear
 * surface needs them (IMPL-AND-08 era).
 */
@Module
@InstallIn(SingletonComponent::class)
object WearAuthModule {

    @Provides
    @Singleton
    fun wearIdTokenCache(@ApplicationContext ctx: Context): WearIdTokenCache =
        WearIdTokenCache(ctx)
}
