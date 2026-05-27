plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.gte619n.healthfitness.domain"
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
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    // IMPL-AND-03: pure-Kotlin unit tests for medications domain
    // helpers (DoseFormatter, FrequencyFormatter, FrequencyConfigSchedule).
    testImplementation(libs.junit)
}
