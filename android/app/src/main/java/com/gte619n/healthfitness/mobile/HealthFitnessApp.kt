package com.gte619n.healthfitness.mobile

import android.app.Application
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
@HiltAndroidApp
class HealthFitnessApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var imageLoader: ImageLoader

    override fun newImageLoader(): ImageLoader = imageLoader
}
