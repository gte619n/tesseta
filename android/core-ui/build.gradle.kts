plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gte619n.healthfitness.ui"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
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
}
