package com.gte619n.healthfitness.data.auth

// Single source of truth for the Google Health OAuth scopes on Android.
// Mirrors the scopes the web connect flow requests in
// web/app/me/body-composition/page.tsx and web/app/me/profile/page.tsx.
//
// Each Google Health data category is gated behind its own scope. Measurements
// covers weight/body-fat/resting-HR/HRV; STEPS lives under activity_and_fitness
// and SLEEP under its own scope. Requesting measurements alone made the
// backend's daily-metric backfill get 403 MISSING_OAUTH_SCOPE for STEPS and
// SLEEP, so those tiles never populated.
object GoogleHealthScopes {
    const val METRICS_READ_ONLY =
        "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly"
    const val ACTIVITY_AND_FITNESS_READ_ONLY =
        "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly"
    const val SLEEP_READ_ONLY =
        "https://www.googleapis.com/auth/googlehealth.sleep.readonly"

    // All read-only scopes requested when connecting Google Health.
    val ALL_READ_ONLY = listOf(
        METRICS_READ_ONLY,
        ACTIVITY_AND_FITNESS_READ_ONLY,
        SLEEP_READ_ONLY,
    )
}
