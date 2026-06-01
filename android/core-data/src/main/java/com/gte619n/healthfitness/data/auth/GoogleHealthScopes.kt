package com.gte619n.healthfitness.data.auth

// Single source of truth for the Google Health OAuth scope on Android.
// Mirrors the GOOGLE_HEALTH_SCOPE constant in web/auth.ts.
object GoogleHealthScopes {
    const val METRICS_READ_ONLY =
        "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly"
}
