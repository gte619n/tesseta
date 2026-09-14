plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    // IMPL-AND-20 (Phase 6): google-services reads app/google-services.json and
    // auto-initializes FirebaseApp. Declared here (apply false) and applied only
    // in :app, which owns google-services.json.
    alias(libs.plugins.google.services) apply false
    // OBS-002: Firebase Crashlytics plugin. Declared here (apply false) and applied
    // only in :app, alongside google-services.
    alias(libs.plugins.firebase.crashlytics) apply false
}
