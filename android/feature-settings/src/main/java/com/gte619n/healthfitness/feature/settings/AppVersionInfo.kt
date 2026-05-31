package com.gte619n.healthfitness.feature.settings

// App version metadata, injected so feature-settings doesn't depend on app's
// BuildConfig. The app module binds a BuildConfig-backed implementation.
interface AppVersionInfo {
    val versionName: String
    val versionCode: Int
}
