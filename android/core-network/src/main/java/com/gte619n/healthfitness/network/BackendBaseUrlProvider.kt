package com.gte619n.healthfitness.network

/**
 * Resolves the backend base URL for the current build variant. The phone
 * and wear modules each ship a `@Provides` implementation that reads
 * `BuildConfig.BACKEND_BASE_URL` — the constant is flavor-scoped, so
 * switching environments is a "change build variant in Android Studio"
 * action rather than a runtime toggle.
 */
interface BackendBaseUrlProvider {
    val baseUrl: String
}
