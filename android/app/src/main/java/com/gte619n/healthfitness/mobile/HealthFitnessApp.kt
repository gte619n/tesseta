package com.gte619n.healthfitness.mobile

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

/**
 * Application class. Hosts the Hilt singleton component and configures
 * the singleton Coil `ImageLoader` so every `AsyncImage` / `HfAsyncImage`
 * in the app shares the same cache, executor, and OkHttp client.
 *
 * The `Provider<ImageLoader>` indirection lets Hilt build the loader
 * lazily on first request rather than at application start — the loader's
 * disk cache initialization is non-trivial and we don't want it on the
 * cold-start critical path.
 */
@HiltAndroidApp
class HealthFitnessApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var imageLoaderProvider: Provider<ImageLoader>

    override fun newImageLoader(): ImageLoader = imageLoaderProvider.get()
}
