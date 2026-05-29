package com.gte619n.healthfitness.mobile

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

// IMPL-12: activates Hilt for the whole app. The network/DI graph (Retrofit,
// OkHttp auth interceptor, GoalsApi/Repository) is assembled in core-data's
// NetworkModule; AppModule below supplies the app-only bindings (backend URL).
@HiltAndroidApp
class HealthFitnessApp : Application()
