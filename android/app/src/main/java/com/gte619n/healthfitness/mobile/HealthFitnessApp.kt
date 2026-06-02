package com.gte619n.healthfitness.mobile

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

// IMPL-12: activates Hilt for the whole app. The network/DI graph (Retrofit,
// OkHttp auth interceptor, GoalsApi/Repository) is assembled in core-data's
// NetworkModule; AppModule below supplies the app-only bindings (backend URL).
//
// Implements Coil's ImageLoaderFactory so the singleton ImageLoader configured
// in AppModule (memory + disk cache) is the one AsyncImage / HfAsyncImage use
// app-wide, instead of an ad-hoc per-call default loader.
//
// IMPL-AND-20 (Phase 4): also a WorkManager Configuration.Provider so the Hilt
// worker factory injects dependencies into the sync workers (@HiltWorker). The
// default WorkManager initializer is disabled in the manifest so this on-demand
// configuration is the one WorkManager uses.
@HiltAndroidApp
class HealthFitnessApp : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun newImageLoader(): ImageLoader = imageLoader

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
