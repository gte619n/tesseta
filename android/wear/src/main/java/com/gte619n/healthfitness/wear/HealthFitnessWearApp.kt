package com.gte619n.healthfitness.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Wear-side Application. Hosts a Hilt SingletonComponent that's entirely
 * separate from the phone's — they share no `@Singleton` bindings. The
 * one piece of shared state (the Google ID token) crosses the gap via
 * the Wearable Data Layer, not Hilt.
 */
@HiltAndroidApp
class HealthFitnessWearApp : Application()
