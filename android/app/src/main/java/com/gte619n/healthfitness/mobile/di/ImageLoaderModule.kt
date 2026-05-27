package com.gte619n.healthfitness.mobile.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Singleton Coil `ImageLoader`. Reuses the shared `OkHttpClient` so
 * image fetches benefit from the same connection pool the API stack uses
 * (and would carry the bearer token automatically if we ever hosted
 * images on the backend). Defaults are kept — 25 % of available memory
 * for the in-memory cache, ~250 MB on disk — which the spec calls out as
 * appropriate for this IMPL.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {

    @Provides
    @Singleton
    fun imageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): ImageLoader = ImageLoader.Builder(context)
        .okHttpClient(okHttpClient)
        .crossfade(true)
        .respectCacheHeaders(false)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("hf_image_cache"))
                .maxSizeBytes(250L * 1024 * 1024)
                .build()
        }
        .build()
}
