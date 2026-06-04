plugins {
    id("healthfitness.android.library")
    // core-ui re-exports the Compose UI stack to features via `api(...)`, so it
    // keeps its Compose deps inline rather than using the (implementation-only)
    // compose convention. It still needs the Compose compiler plugin + build feature.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gte619n.healthfitness.ui"
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    api(libs.compose.material3)
    api(libs.compose.material3.window.size)
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.ui.tooling.preview)
    api(libs.compose.ui.text.google.fonts)
    api(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // IMPL-AND-00: shared image loading + coroutine-backed snackbar controller.
    api(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    // IMPL-AND-20 (Phase 6): JVM unit tests for the pure sync-UX state mappings
    // (syncUiStateOf / badgeSpecOf) — no Compose runtime needed.
    testImplementation(libs.junit)
}
